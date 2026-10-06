package to.rtc.cli.migrate.git;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import to.rtc.cli.migrate.FileProperties;
import to.rtc.cli.migrate.FileProperties.LineDelimiter;

/**
 * Folders that are empty in EWM get an index-only .gitkeep when keep.empty.folders is set.
 */
public class GitMigratorEmptyFolderTest {
	private static final Map<String, String> NONE = Collections.emptyMap();

	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();

	private Properties props;
	private GitMigrator migrator;
	private File basedir;

	@Before
	public void setUp() {
		props = new Properties();
		props.setProperty("keep.empty.folders", "true");
		migrator = new GitMigrator(props);
		basedir = tempFolder.getRoot();
	}

	@After
	public void tearDown() {
		migrator.close();
	}

	@Test
	public void testEmptyFolderGetsPlaceholderOnlyInGit() throws Exception {
		migrator.init(basedir);
		new File(basedir, "proj/bin/com").mkdirs();
		write("proj/a.txt", "a");

		migrator.updateFileProperties(Arrays.asList(folder("1", "proj"), folder("2", "proj/bin"),
				folder("3", "proj/bin/com"), file("4", "proj/a.txt")));
		migrator.commitChanges(GitMigratorHistoryTest.changeSet("cs-1", "add"));

		assertTrue(isTracked("proj/bin/com/.gitkeep"));
		assertFalse(isTracked("proj/bin/.gitkeep"));
		assertFalse(new File(basedir, "proj/bin/com/.gitkeep").exists());
		try (Git git = Git.open(basedir)) {
			assertTrue(git.status().call().getUncommittedChanges().toString(), git.status().call().isClean());
		}
	}

	@Test
	public void testPlaceholderSurvivesLaterCommitsAndGoesWhenFolderFills() throws Exception {
		migrator.init(basedir);
		new File(basedir, "empty").mkdirs();
		List<FileProperties> files = Arrays.asList(folder("1", "empty"), file("2", "a.txt"));
		write("a.txt", "a");
		migrator.updateFileProperties(files);
		migrator.commitChanges(GitMigratorHistoryTest.changeSet("cs-1", "add"));

		write("a.txt", "b");
		migrator.updateFileProperties(files);
		migrator.commitChanges(GitMigratorHistoryTest.changeSet("cs-2", "modify"));
		assertTrue(isTracked("empty/.gitkeep"));

		write("empty/now.txt", "content");
		migrator.updateFileProperties(Arrays.asList(folder("1", "empty"), file("2", "a.txt"), file("3", "empty/now.txt")));
		migrator.commitChanges(GitMigratorHistoryTest.changeSet("cs-3", "fill"));
		assertFalse(isTracked("empty/.gitkeep"));
		assertTrue(isTracked("empty/now.txt"));
	}

	@Test
	public void testResumeIgnoresIndexOnlyPlaceholders() throws Exception {
		migrator.init(basedir);
		new File(basedir, "empty").mkdirs();
		migrator.updateFileProperties(Arrays.asList(folder("1", "empty")));
		migrator.commitChanges(GitMigratorHistoryTest.changeSet("cs-1", "add"));
		migrator.close();

		migrator = new GitMigrator(props);
		migrator.init(basedir); // must not complain about the missing .gitkeep

		assertTrue(migrator.isMigrated("cs-1"));
	}

	@Test
	public void testOffByDefault() throws Exception {
		migrator = new GitMigrator(new Properties());
		migrator.init(basedir);
		new File(basedir, "empty").mkdirs();
		write("a.txt", "a");

		migrator.updateFileProperties(Arrays.asList(folder("1", "empty"), file("2", "a.txt")));
		migrator.commitChanges(GitMigratorHistoryTest.changeSet("cs-1", "add"));

		assertFalse(isTracked("empty/.gitkeep"));
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
		java.nio.file.Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
	}

	private static FileProperties folder(String id, String path) {
		return FileProperties.folder(id, path, NONE);
	}

	private static FileProperties file(String id, String path) {
		return FileProperties.file(id, path, LineDelimiter.LF, "text/plain", "UTF-8", false, NONE);
	}
}
