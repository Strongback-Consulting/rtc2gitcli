package to.rtc.cli.migrate;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Machine readable record of one migration run (written as JSON).
 */
public final class MigrationReport {
	private final Map<String, Object> root = new LinkedHashMap<String, Object>();
	private final List<Object> changeSets = new ArrayList<Object>();
	private final List<Object> tags = new ArrayList<Object>();
	private final List<Object> discarded = new ArrayList<Object>();
	private final List<Object> warnings = new ArrayList<Object>();
	private int migrated;
	private int skipped;

	public MigrationReport(String sourceWorkspace, String targetWorkspace) {
		root.put("tool", "rtc2gitcli migrate-to-git");
		root.put("started", timestamp(System.currentTimeMillis()));
		root.put("sourceWorkspace", sourceWorkspace);
		root.put("targetWorkspace", targetWorkspace);
	}

	public synchronized void changeSetMigrated(ChangeSet changeSet, String component, String tag, String commitId) {
		migrated++;
		changeSets.add(changeSetEntry("migrated", changeSet, component, tag, commitId));
	}

	public synchronized void changeSetSkipped(ChangeSet changeSet, String component, String tag) {
		skipped++;
		changeSets.add(changeSetEntry("alreadyMigrated", changeSet, component, tag, null));
	}

	public synchronized void changeSetDiscarded(String uuid) {
		discarded.add(uuid);
	}

	public synchronized void tagged(Tag tag) {
		Map<String, Object> entry = new LinkedHashMap<String, Object>();
		entry.put("name", tag.getName());
		entry.put("originalName", tag.getOriginalName());
		entry.put("kind", tag.getSnapshotUuid() != null ? "snapshot" : "baseline");
		if (tag.getSnapshotUuid() != null) {
			entry.put("snapshot", tag.getSnapshotUuid());
		}
		entry.put("baselines", new ArrayList<Object>(tag.getBaselineUuids()));
		tags.add(entry);
	}

	public synchronized void warning(String message) {
		warnings.add(message);
	}

	public synchronized void finished(Throwable failure) {
		root.put("finished", timestamp(System.currentTimeMillis()));
		root.put("status", failure == null ? "succeeded" : "failed");
		if (failure != null) {
			root.put("error", String.valueOf(failure.getMessage() != null ? failure.getMessage() : failure));
		}
	}

	private static Map<String, Object> changeSetEntry(String status, ChangeSet changeSet, String component,
			String tag, String commitId) {
		Map<String, Object> entry = new LinkedHashMap<String, Object>();
		entry.put("uuid", changeSet.getUuid());
		entry.put("status", status);
		entry.put("component", component);
		entry.put("tag", tag);
		entry.put("author", changeSet.getCreatorName());
		entry.put("created", timestamp(changeSet.getCreationDate()));
		entry.put("comment", changeSet.getComment());
		if (commitId != null) {
			entry.put("commit", commitId);
		}
		return entry;
	}

	/**
	 * Writes the report into <code>&lt;sandbox&gt;/.git/rtc2git/report-&lt;time&gt;.json</code> (not part of the
	 * migrated history).
	 *
	 * @return the written file
	 */
	public synchronized File write(File sandboxDirectory) throws IOException {
		File directory = new File(sandboxDirectory, ".git/rtc2git");
		directory.mkdirs();
		SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss");
		File file = new File(directory, "report-" + format.format(new Date()) + ".json");
		Map<String, Object> summary = new LinkedHashMap<String, Object>();
		summary.put("migrated", Integer.valueOf(migrated));
		summary.put("alreadyMigrated", Integer.valueOf(skipped));
		summary.put("discardedOnResume", Integer.valueOf(discarded.size()));
		summary.put("tags", Integer.valueOf(tags.size()));
		summary.put("warnings", Integer.valueOf(warnings.size()));
		Map<String, Object> document = new LinkedHashMap<String, Object>(root);
		document.put("summary", summary);
		document.put("changeSets", changeSets);
		document.put("tags", tags);
		document.put("discardedOnResume", discarded);
		document.put("warnings", warnings);
		try (Writer writer = new OutputStreamWriter(Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8)) {
			writeValue(writer, document, "");
			writer.write('\n');
		}
		return file;
	}

	static String timestamp(long millis) {
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		format.setTimeZone(TimeZone.getTimeZone("UTC"));
		return format.format(new Date(millis));
	}

	static void writeValue(Writer writer, Object value, String indent) throws IOException {
		if (value == null) {
			writer.write("null");
		} else if (value instanceof Number || value instanceof Boolean) {
			writer.write(value.toString());
		} else if (value instanceof Map) {
			Iterator<? extends Map.Entry<?, ?>> it = ((Map<?, ?>) value).entrySet().iterator();
			if (!it.hasNext()) {
				writer.write("{}");
				return;
			}
			writer.write("{\n");
			while (it.hasNext()) {
				Map.Entry<?, ?> entry = it.next();
				writer.write(indent + "  ");
				writeString(writer, String.valueOf(entry.getKey()));
				writer.write(": ");
				writeValue(writer, entry.getValue(), indent + "  ");
				writer.write(it.hasNext() ? ",\n" : "\n");
			}
			writer.write(indent + "}");
		} else if (value instanceof Collection) {
			Iterator<?> it = ((Collection<?>) value).iterator();
			if (!it.hasNext()) {
				writer.write("[]");
				return;
			}
			writer.write("[\n");
			while (it.hasNext()) {
				writer.write(indent + "  ");
				writeValue(writer, it.next(), indent + "  ");
				writer.write(it.hasNext() ? ",\n" : "\n");
			}
			writer.write(indent + "]");
		} else {
			writeString(writer, value.toString());
		}
	}

	private static void writeString(Writer writer, String text) throws IOException {
		StringBuilder sb = new StringBuilder("\"");
		for (char c : text.toCharArray()) {
			switch (c) {
			case '"':
				sb.append("\\\"");
				break;
			case '\\':
				sb.append("\\\\");
				break;
			case '\n':
				sb.append("\\n");
				break;
			case '\r':
				sb.append("\\r");
				break;
			case '\t':
				sb.append("\\t");
				break;
			default:
				if (c < 0x20) {
					sb.append(String.format("\\u%04x", Integer.valueOf(c)));
				} else {
					sb.append(c);
				}
			}
		}
		writer.write(sb.append('"').toString());
	}
}
