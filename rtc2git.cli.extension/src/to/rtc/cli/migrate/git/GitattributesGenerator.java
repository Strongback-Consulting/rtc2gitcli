package to.rtc.cli.migrate.git;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import to.rtc.cli.migrate.FileProperties;

/**
 * Turns EWM file properties into <code>.gitattributes</code> lines, so that a checkout produces the same bytes as
 * <code>scm load</code>:
 * <ul>
 * <li>line delimiter None/CR: <code>-text</code> (content stored and checked out unchanged)</li>
 * <li>LF: <code>text eol=lf</code>, CRLF: <code>text eol=crlf</code>, Platform: <code>text</code></li>
 * <li>content type other than <code>text/*</code>: <code>binary</code></li>
 * <li>optionally a non UTF-8 encoding: <code>working-tree-encoding=...</code></li>
 * </ul>
 * Each folder gets a single <code>/folder/**</code> line with its most common attributes plus its exceptions.
 */
final class GitattributesGenerator {
	static final String BEGIN = "# >>> generated from EWM file properties";
	static final String END = "# <<< generated from EWM file properties";

	private final boolean workingTreeEncoding;

	GitattributesGenerator(boolean workingTreeEncoding) {
		this.workingTreeEncoding = workingTreeEncoding;
	}

	String attributesFor(FileProperties file) {
		String contentType = file.getContentType();
		if (contentType != null && !contentType.toLowerCase(Locale.ROOT).startsWith("text/")) {
			return "binary";
		}
		String attributes;
		switch (file.getLineDelimiter() == null ? FileProperties.LineDelimiter.NONE : file.getLineDelimiter()) {
		case LF:
			attributes = "text eol=lf";
			break;
		case CRLF:
			attributes = "text eol=crlf";
			break;
		case PLATFORM:
			attributes = "text";
			break;
		default:
			return "-text";
		}
		String encoding = file.getEncoding();
		if (workingTreeEncoding && encoding != null && !isUtf8Compatible(encoding)) {
			attributes += " working-tree-encoding=" + encoding;
		}
		return attributes;
	}

	private static boolean isUtf8Compatible(String encoding) {
		String normalized = encoding.toUpperCase(Locale.ROOT).replace("-", "").replace("_", "");
		return normalized.equals("UTF8") || normalized.equals("USASCII") || normalized.equals("ASCII");
	}

	List<String> generate(Collection<FileProperties> items) {
		Node root = new Node();
		for (FileProperties item : items) {
			if (!item.isFolder()) {
				root.add(item.getPath().split("/"), 0, attributesFor(item));
			}
		}
		List<String> lines = new ArrayList<String>();
		if (root.majority() != null) { // null: no files at all
			emit(root, "", null, lines);
		}
		return lines;
	}

	/**
	 * Each folder gets one line with the most common attributes below it, followed by the exceptions. Later lines win
	 * per attribute, so an exception also resets (<code>!attr</code>) what the folder line set and it does not.
	 */
	private static void emit(Node node, String prefix, String inherited, List<String> lines) {
		String majority = node.majority();
		if (!majority.equals(inherited)) {
			lines.add((prefix.isEmpty() ? "*" : "/" + escape(prefix) + "**") + " " + override(majority, inherited));
		}
		for (Map.Entry<String, String> file : node.files.entrySet()) {
			if (!file.getValue().equals(majority)) {
				lines.add("/" + escape(prefix + file.getKey()) + " " + override(file.getValue(), majority));
			}
		}
		for (Map.Entry<String, Node> dir : node.dirs.entrySet()) {
			emit(dir.getValue(), prefix + dir.getKey() + "/", majority, lines);
		}
	}

	static String override(String attributes, String inherited) {
		if (inherited == null) {
			return attributes;
		}
		Set<String> reset = new TreeSet<String>(names(inherited));
		reset.removeAll(names(attributes));
		StringBuilder sb = new StringBuilder(attributes);
		for (String name : reset) {
			sb.append(" !").append(name);
		}
		return sb.toString();
	}

	private static Set<String> names(String attributes) {
		Set<String> names = new TreeSet<String>();
		for (String token : attributes.split(" ")) {
			if (token.equals("binary")) {
				names.add("text");
				names.add("diff");
				names.add("merge");
				continue;
			}
			String name = token.replaceFirst("^[-!]", "");
			int equals = name.indexOf('=');
			names.add(equals < 0 ? name : name.substring(0, equals));
		}
		return names;
	}

	static String escape(String path) {
		StringBuilder sb = new StringBuilder();
		for (char c : path.toCharArray()) {
			if (c == ' ') {
				sb.append("[[:space:]]");
			} else if ("*?[\\".indexOf(c) >= 0) {
				sb.append('\\').append(c);
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private static final class Node {
		final Map<String, Node> dirs = new TreeMap<String, Node>();
		final Map<String, String> files = new TreeMap<String, String>();
		private Map<String, Integer> counts;

		void add(String[] segments, int index, String attributes) {
			if (index == segments.length - 1) {
				files.put(segments[index], attributes);
				return;
			}
			Node child = dirs.get(segments[index]);
			if (child == null) {
				child = new Node();
				dirs.put(segments[index], child);
			}
			child.add(segments, index + 1, attributes);
		}

		/**
		 * @return the most common attributes of all files below (ties: the alphabetically first)
		 */
		String majority() {
			String best = null;
			int bestCount = 0;
			for (Map.Entry<String, Integer> entry : counts().entrySet()) {
				if (entry.getValue().intValue() > bestCount) {
					best = entry.getKey();
					bestCount = entry.getValue().intValue();
				}
			}
			return best;
		}

		private Map<String, Integer> counts() {
			if (counts == null) {
				counts = new TreeMap<String, Integer>();
				for (String attributes : files.values()) {
					increment(attributes, 1);
				}
				for (Node dir : dirs.values()) {
					for (Map.Entry<String, Integer> entry : dir.counts().entrySet()) {
						increment(entry.getKey(), entry.getValue().intValue());
					}
				}
			}
			return counts;
		}

		private void increment(String attributes, int by) {
			Integer count = counts.get(attributes);
			counts.put(attributes, Integer.valueOf((count == null ? 0 : count.intValue()) + by));
		}
	}
}
