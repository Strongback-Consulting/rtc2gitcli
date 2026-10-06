package to.rtc.cli.migrate.git;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import to.rtc.cli.migrate.FileProperties;
import to.rtc.cli.migrate.FileProperties.LineDelimiter;

public class GitattributesGeneratorTest {

	private final GitattributesGenerator generator = new GitattributesGenerator(false, null);

	@Test
	public void testAttributesFromLineDelimiter() {
		assertEquals("-text", generator.attributesFor(file("a", LineDelimiter.NONE, "text/text", "UTF-8")));
		assertEquals("-text", generator.attributesFor(file("a", LineDelimiter.CR, "text/text", "UTF-8")));
		assertEquals("text eol=lf", generator.attributesFor(file("a", LineDelimiter.LF, "text/plain", "UTF-8")));
		assertEquals("text eol=crlf", generator.attributesFor(file("a", LineDelimiter.CRLF, "text/plain", "UTF-8")));
		assertEquals("text", generator.attributesFor(file("a", LineDelimiter.PLATFORM, "text/plain", "UTF-8")));
	}

	@Test
	public void testNonTextContentTypeIsBinary() {
		assertEquals("binary", generator.attributesFor(file("a", LineDelimiter.LF, "application/unknown", null)));
		assertEquals("binary", generator.attributesFor(file("a", LineDelimiter.NONE, "image/png", null)));
	}

	@Test
	public void testWorkingTreeEncodingOnlyWhenEnabledAndNotUtf8() {
		GitattributesGenerator withEncoding = new GitattributesGenerator(true, null);
		assertEquals("text eol=lf working-tree-encoding=IBM-1047",
				withEncoding.attributesFor(file("a", LineDelimiter.LF, "text/plain", "IBM-1047")));
		assertEquals("text eol=lf", withEncoding.attributesFor(file("a", LineDelimiter.LF, "text/plain", "utf-8")));
		assertEquals("text eol=lf", generator.attributesFor(file("a", LineDelimiter.LF, "text/plain", "IBM-1047")));
		assertEquals("-text", withEncoding.attributesFor(file("a", LineDelimiter.NONE, "text/plain", "IBM-1047")));
	}

	@Test
	public void testUniformTreeBecomesOneLine() {
		assertEquals(Arrays.asList("* -text"), generator.generate(Arrays.asList(
				file("p/a.java", LineDelimiter.NONE, "text/text", "UTF-8"),
				file("q/r/b.java", LineDelimiter.NONE, "text/text", "UTF-8"))));
	}

	@Test
	public void testMajorityPerFolderWithExceptions() {
		assertEquals(Arrays.asList("* -text", "/README text eol=lf", "/lib/** binary",
				"/web/b.js text eol=crlf"),
				generator.generate(Arrays.asList(file("src/x/A.java", LineDelimiter.NONE, "text/text", "UTF-8"),
						file("src/B.java", LineDelimiter.NONE, "text/text", "UTF-8"),
						file("lib/x.jar", LineDelimiter.NONE, "application/octet-stream", null),
						file("web/a.js", LineDelimiter.NONE, "text/text", "UTF-8"),
						file("web/b.js", LineDelimiter.CRLF, "text/text", "UTF-8"),
						file("README", LineDelimiter.LF, "text/plain", "UTF-8"),
						FileProperties.folder("f", "empty", Collections.<String, String> emptyMap()))));
	}

	@Test
	public void testExceptionResetsAttributesOfTheFolderLine() {
		assertEquals("text eol=lf !diff !merge", GitattributesGenerator.override("text eol=lf", "binary"));
		assertEquals("-text !eol", GitattributesGenerator.override("-text", "text eol=crlf"));
		assertEquals("text eol=crlf", GitattributesGenerator.override("text eol=crlf", "-text"));
	}

	@Test
	public void testNoFilesNoLines() {
		assertEquals(Collections.emptyList(), generator.generate(
				Arrays.asList(FileProperties.folder("f", "empty", Collections.<String, String> emptyMap()))));
	}

	@Test
	public void testEscaping() {
		assertEquals("/my[[:space:]]dir/a\\*b\\?c\\[d",
				"/" + GitattributesGenerator.escape("my dir/a*b?c[d"));
	}

	static FileProperties file(String path, LineDelimiter delimiter, String contentType, String encoding) {
		return FileProperties.file("id-" + path, path, delimiter, contentType, encoding, false,
				Collections.<String, String> emptyMap());
	}

	@Test
	public void testZosMembersGetCodePage() {
		GitattributesGenerator zos = new GitattributesGenerator(false, "IBM-1047");
		java.util.Map<String, String> cobolFolder = Collections.singletonMap(
				"team.enterprise.resource.definition", "_dsd");
		java.util.Map<String, String> ownCodePage = new java.util.HashMap<String, String>();
		ownCodePage.put("team.enterprise.language.definition", "_ld");
		ownCodePage.put("mvsCodePage", "IBM-037");

		assertEquals(Arrays.asList("* text eol=lf",
				"/App/zOSsrc/** text eol=lf zos-working-tree-encoding=IBM-1047 git-encoding=utf-8",
				"/App/zOSsrc/COBOL/B.cbl text eol=lf zos-working-tree-encoding=IBM-037 git-encoding=utf-8",
				"/App/zOSsrc/COBOL/NODELIM.cbl -text zos-working-tree-encoding=IBM-1047 git-encoding=utf-8 !eol",
				"/App/zOSsrc/COBOL/obj.bin binary !eol !git-encoding !zos-working-tree-encoding"),
				zos.generate(Arrays.asList(file("App/.project", LineDelimiter.LF, "text/text", "UTF-8"),
						file("App/zOSsrc/COBOL/A.cbl", LineDelimiter.LF, "text/text", "UTF-8"),
						file("App/zOSsrc/COBOL/C.cbl", LineDelimiter.LF, "text/text", "UTF-8"),
						FileProperties.file("b", "App/zOSsrc/COBOL/B.cbl", LineDelimiter.LF, "text/text", "UTF-8",
								false, ownCodePage),
						file("App/zOSsrc/COBOL/NODELIM.cbl", LineDelimiter.NONE, "text/text", "UTF-8"),
						file("App/zOSsrc/COBOL/obj.bin", LineDelimiter.NONE, "application/octet-stream", null),
						file("App/readme.txt", LineDelimiter.LF, "text/text", "UTF-8"),
						FileProperties.folder("d", "App/zOSsrc/COBOL", cobolFolder))));
		// without a code page: unchanged
		assertEquals("text eol=lf", generator.attributesFor(
				FileProperties.file("b", "x.cbl", LineDelimiter.LF, "text/text", "UTF-8", false, ownCodePage), true));
	}
}
