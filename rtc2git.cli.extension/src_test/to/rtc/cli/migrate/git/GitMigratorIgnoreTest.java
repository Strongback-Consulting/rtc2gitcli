package to.rtc.cli.migrate.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import to.rtc.cli.migrate.util.Files;

/**
 * Files that are versioned in EWM but match a .gitignore pattern must still be committed.
 */
public class GitMigratorIgnoreTest {
	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();

	private Properties props;
	private GitMigrator migrator;
	private File basedir;

	@Before
	public void setUp() {
		props = new Properties();
		migrator = new GitMigrator(props);
		basedir = tempFolder.getRoot();
	}

	@After
	public void tearDown() {
		migrator.close();
	}

	@Test
	public void testVersionedFileMatchingJazzignoreIsCommitted() throws Exception {
		migrator.init(basedir);
		write(".jazzignore", "core.ignore = {*.class}");
		migrator.commitChanges(GitMigratorHistoryTest.changeSet("cs-1", "ignore classes"));

		write("A.class", "binary");
		migrator.commitChanges(GitMigratorHistoryTest.changeSet("cs-2", "versioned class file"));

		assertTrue(isTracked("A.class"));
	}

	@Test
	public void testVersionedFilesInIgnoredFolderAreCommitted() throws Exception {
		migrator.init(basedir);
		write(".jazzignore", "core.ignore = {bin}");
		migrator.commitChanges(GitMigratorHistoryTest.changeSet("cs-1", "ignore bin"));

		write("bin/run.sh", "echo");
		write("bin/sub/tool.sh", "echo");
		migrator.commitChanges(GitMigratorHistoryTest.changeSet("cs-2", "versioned scripts"));

		assertTrue(isTracked("bin/run.sh"));
		assertTrue(isTracked("bin/sub/tool.sh"));
	}

	@Test
	public void testIntentionalExclusionsStayIgnored() throws Exception {
		props.setProperty("global.gitignore.entries", "*.log");
		props.setProperty("ignore.file.extensions", ".zip");
		migrator.initialize(props);
		migrator.init(basedir);
		write(".jazz5/state", "scm metadata");
		write(".metadata/.plugins/x", "eclipse metadata");
		write("build.log", "log");
		write("dist/archive.zip", "zip");
		write("a.txt", "a");
		migrator.commitChanges(GitMigratorHistoryTest.changeSet("cs-1", "first"));

		write("b.txt", "b");
		migrator.commitChanges(GitMigratorHistoryTest.changeSet("cs-2", "second"));

		assertTrue(isTracked("b.txt"));
		assertFalse(isTracked(".jazz5/state"));
		assertFalse(isTracked(".metadata/.plugins/x"));
		assertFalse(isTracked("build.log"));
		assertFalse(isTracked("dist/archive.zip"));
	}

	@Test
	public void testForceAddCanBeDisabled() throws Exception {
		props.setProperty("commit.force.add.ignored", "false");
		migrator.initialize(props);
		migrator.init(basedir);
		write(".jazzignore", "core.ignore = {*.class}");
		migrator.commitChanges(GitMigratorHistoryTest.changeSet("cs-1", "ignore classes"));

		write("A.class", "binary");
		migrator.commitChanges(GitMigratorHistoryTest.changeSet("cs-2", "versioned class file"));

		assertFalse(isTracked("A.class"));
	}

	@Test
	public void testIsIntentionallyIgnored() throws Exception {
		props.setProperty("global.gitignore.entries", "/target; *.tmp");
		migrator.initialize(props);
		migrator.init(basedir);

		assertTrue(migrator.isIntentionallyIgnored(".jazz5"));
		assertTrue(migrator.isIntentionallyIgnored(".metadata/.plugins/org.eclipse.core.resources"));
		assertTrue(migrator.isIntentionallyIgnored("target/classes/A.class"));
		assertTrue(migrator.isIntentionallyIgnored("src/x.tmp"));
		assertFalse(migrator.isIntentionallyIgnored("src/target/A.class"));
		assertFalse(migrator.isIntentionallyIgnored("A.class"));
		assertEquals(false, migrator.isIntentionallyIgnored("bin"));
	}

	private boolean isTracked(String path) throws Exception {
		try (Git git = Git.open(basedir)) {
			Repository repository = git.getRepository();
			try (TreeWalk walk = TreeWalk.forPath(repository, path,
					repository.parseCommit(repository.resolve("HEAD")).getTree())) {
				return walk != null;
			}
		}
	}

	private void write(String name, String content) throws Exception {
		File file = new File(basedir, name);
		file.getParentFile().mkdirs();
		Files.writeLines(file, Arrays.asList(content), StandardCharsets.UTF_8, false);
	}
}
