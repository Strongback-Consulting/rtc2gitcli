package to.rtc.cli.migrate.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.errors.InvalidPatternException;
import org.eclipse.jgit.fnmatch.FileNameMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author christoph.erni
 */
public class JazzignoreTranslator {
	private static final Logger LOGGER = LoggerFactory.getLogger(JazzignoreTranslator.class);
	private static final Pattern EXCLUSION = Pattern.compile("\\{(.*?)\\}");

	/**
	 * Translates a .jazzignore file to .gitignore
	 * 
	 * @param jazzignore
	 *            the input .jazzignore file
	 * 
	 * @return the translation, as a list of lines
	 */
	public static List<String> toGitignore(File jazzignore) {
		List<String> gitignoreLines = new ArrayList<String>();
		try {
			// default charset should be ok here
			List<String> jazzignoreLines = Files.readLines(jazzignore, Charset.defaultCharset());
			String lineForRegex = "";
			boolean needToTransform = false;
			for (String line : jazzignoreLines) {
				line = line.trim();
				if (!line.startsWith("#")) {
					needToTransform = true;
					lineForRegex += line;
					if (!line.endsWith("\\")) {
						addGroupsToList(lineForRegex, gitignoreLines);
						lineForRegex = "";
						needToTransform = false;
					}
				}
			}
			if (needToTransform) {
				gitignoreLines = addGroupsToList(lineForRegex, gitignoreLines);
			}
		} catch (IOException ioe) {
			throw new RuntimeException("unable to read .jazzignore file " + jazzignore.getAbsolutePath(), ioe);
		}
		return gitignoreLines;
	}

	private static List<String> addGroupsToList(String lineToMatch, List<String> ignoreLines) {
		boolean recursive = lineToMatch.startsWith("core.ignore.recursive");
		Matcher matcher = EXCLUSION.matcher(lineToMatch);
		while (matcher.find()) {
			String pattern = (!recursive ? "/" : "").concat(matcher.group(1));
			if (checkPattern(pattern)) {
				ignoreLines.add(pattern);
			}
		}
		return ignoreLines;
	}

	static boolean checkPattern(String pattern) {
		try {
			if (endsWithEscape(pattern)) {
				throw new InvalidPatternException("Trailing escape character", pattern);
			}
			new FileNameMatcher(pattern, null);
			return true;
		} catch (InvalidPatternException e) {
			LOGGER.warn("Ignoring uncompilable pattern: {}", pattern);
			return false;
		}
	}

	// git treats a pattern ending in an unescaped backslash as invalid
	private static boolean endsWithEscape(String pattern) {
		int backslashes = 0;
		for (int i = pattern.length() - 1; i >= 0 && pattern.charAt(i) == '\\'; i--) {
			backslashes++;
		}
		return backslashes % 2 == 1;
	}
}
