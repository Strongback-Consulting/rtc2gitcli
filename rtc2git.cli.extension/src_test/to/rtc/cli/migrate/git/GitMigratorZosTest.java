package to.rtc.cli.migrate.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import to.rtc.cli.migrate.FileProperties;
import to.rtc.cli.migrate.FileProperties.LineDelimiter;
import to.rtc.cli.migrate.zos.SystemDefinitions;
import to.rtc.cli.migrate.zos.ZosDefinition;
import to.rtc.cli.migrate.zos.ZosMetadata;
import to.rtc.cli.migrate.zos.ZosProperties;

/**
 * z/OS members get their code page in .gitattributes, and .ewm/zos-metadata.json records the definitions in every
 * commit where they change.
 */
public class GitMigratorZosTest {
	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();

	private static final SystemDefinitions DEFINITIONS = new SystemDefinitions() {
		@Override
		public String getUnavailableReason() {
			return null;
		}

		@Override
		public Map<String, ZosDefinition> resolve(Collection<String> languages, Collection<String> dataSets) {
			Map<String, ZosDefinition> result = new HashMap<String, ZosDefinition>();
			result.put("_batch", ZosDefinition.language("_batch", "COBOL batch").languageCode("COB").build());
			result.put("_cics", ZosDefinition.language("_cics", "COBOL CICS").languageCode("COB").build());
			result.put("_dsd", ZosDefinition.dataSet("_dsd", "COBOL sources").dataSet("COBOL", "", true, 0, 0).build());
			return result;
		}
	};

	private GitMigrator migrator;
	private File basedir;

	@Before
	public void setUp() {
		migrator = new GitMigrator(new Properties());
		migrator.setSystemDefinitions(DEFINITIONS);
		basedir = tempFolder.getRoot();
	}

	@After
	public void tearDown() {
		migrator.close();
	}

	@Test
	public void testLanguageDefinitionChangeIsACommitOfTheMetadata() throws Exception {
		migrator.init(basedir);
		write("App/zOSsrc/COBOL/A.cbl", "       IDENTIFICATION DIVISION.\n");
		migrator.updateFileProperties(model("_batch"));
		migrator.commitChanges(GitMigratorHistoryTest.changeSet("cs-1", "add A"));

		String metadata = read(ZosMetadata.PATH);
		assertTrue(metadata, metadata.contains("\"App/zOSsrc/COBOL/A.cbl\""));
		assertTrue(metadata, metadata.contains("\"name\": \"COBOL batch\""));
		assertTrue(metadata, metadata.contains("\"name\": \"COBOL sources\""));
		assertTrue(metadata, metadata.contains("\"CBLCMPOPTS\": \"LIB\""));
		assertTrue(read(".gitattributes"),
				read(".gitattributes").contains("zos-working-tree-encoding=IBM-1047 git-encoding=utf-8"));

		// property-only change set: the member is reassigned to another language definition
		migrator.updateFileProperties(model("_cics"));
		migrator.commitChanges(GitMigratorHistoryTest.changeSet("cs-2", "reassign A"));

		assertEquals(Arrays.asList(ZosMetadata.PATH), changedInHead());
		assertTrue(read(ZosMetadata.PATH).contains("\"name\": \"COBOL CICS\""));
	}

	@Test
	public void testNoZosMetadataWithoutZosProperties() throws Exception {
		migrator.init(basedir);
		write("src/Main.java", "class Main {}\n");
		migrator.updateFileProperties(Arrays.asList(FileProperties.file("1", "src/Main.java", LineDelimiter.LF,
				"text/plain", "UTF-8", false, Collections.<String, String> emptyMap())));
		migrator.commitChanges(GitMigratorHistoryTest.changeSet("cs-1", "java"));

		assertFalse(new File(basedir, ZosMetadata.PATH).exists());
		assertFalse(read(".gitattributes").contains("zos-working-tree-encoding"));
	}

	@Test
	public void testCodePageCanBeChangedOrDisabled() throws Exception {
		Properties props = new Properties();
		props.setProperty("zos.codepage", "IBM-037");
		GitMigrator other = new GitMigrator(props);
		try {
			other.setSystemDefinitions(DEFINITIONS);
			other.init(basedir);
			write("App/zOSsrc/COBOL/A.cbl", "x\n");
			other.updateFileProperties(model("_batch"));
			assertTrue(read(".gitattributes").contains("zos-working-tree-encoding=IBM-037"));
		} finally {
			other.close();
		}

		props.setProperty("zos.codepage", "none");
		GitMigrator disabled = new GitMigrator(props);
		try {
			disabled.updateFileProperties(model("_batch"));
			assertNull(new GitattributesGenerator(false, null).generate(model("_batch")).stream()
					.filter(line -> line.contains("zos-working-tree-encoding")).findAny().orElse(null));
		} finally {
			disabled.close();
		}
	}

	private static List<FileProperties> model(String language) {
		Map<String, String> member = new HashMap<String, String>();
		member.put(ZosProperties.LANGUAGE_DEFINITION, language);
		member.put(ZosProperties.BUILD_VARIABLE_PREFIX + "CBLCMPOPTS", "LIB");
		return Arrays.asList(
				FileProperties.folder("d", "App/zOSsrc/COBOL",
						Collections.singletonMap(ZosProperties.RESOURCE_DEFINITION, "_dsd")),
				FileProperties.file("a", "App/zOSsrc/COBOL/A.cbl", LineDelimiter.LF, "text/text", "UTF-8", false,
						member));
	}

	private List<String> changedInHead() throws Exception {
		try (Git git = Git.open(basedir); RevWalk walk = new RevWalk(git.getRepository())) {
			Repository repository = git.getRepository();
			RevCommit head = walk.parseCommit(repository.resolve("HEAD"));
			RevCommit parent = walk.parseCommit(head.getParent(0));
			List<String> paths = new java.util.ArrayList<String>();
			for (DiffEntry entry : git.diff().setOldTree(tree(repository, parent.getTree()))
					.setNewTree(tree(repository, head.getTree())).call()) {
				paths.add(entry.getNewPath());
			}
			return paths;
		}
	}

	private static CanonicalTreeParser tree(Repository repository, ObjectId tree) throws Exception {
		CanonicalTreeParser parser = new CanonicalTreeParser();
		try (org.eclipse.jgit.lib.ObjectReader reader = repository.newObjectReader()) {
			parser.reset(reader, tree);
		}
		return parser;
	}

	private void write(String name, String content) throws Exception {
		File file = new File(basedir, name);
		file.getParentFile().mkdirs();
		Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
	}

	private String read(String name) throws Exception {
		return new String(Files.readAllBytes(new File(basedir, name).toPath()), StandardCharsets.UTF_8);
	}
}
