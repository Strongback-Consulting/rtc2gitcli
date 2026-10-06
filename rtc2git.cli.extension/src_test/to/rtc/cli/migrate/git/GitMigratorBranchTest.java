package to.rtc.cli.migrate.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static to.rtc.cli.migrate.git.GitMigratorHistoryTest.changeSet;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import to.rtc.cli.migrate.BranchPoint;
import to.rtc.cli.migrate.MigrationReport;
import to.rtc.cli.migrate.util.Files;

/**
 * A stream added as a branch to the repository of an earlier migration: the new sandbox is a linked worktree.
 */
public class GitMigratorBranchTest {
	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();

	private final Properties props = new Properties();
	private File main;
	private File child;
	private GitMigrator migrator;

	@Before
	public void setUp() throws Exception {
		main = tempFolder.newFolder("main");
		child = tempFolder.newFolder("child");
		GitMigrator primary = new GitMigrator(props);
		primary.setBranch("main");
		primary.setInitialState(Collections.singletonMap("comp", "initial"));
		primary.init(main);
		write(main, "src/a.txt", "a1");
		write(main, "src/.jazzignore", "core.ignore = {*.class}");
		primary.commitChanges(changeSet("cs-1", "first"));
		write(main, "src/a.txt", "a2");
		primary.commitChanges(changeSet("cs-2", "second"));
		primary.close();
	}

	@After
	public void tearDown() {
		if (migrator != null) {
			migrator.close();
		}
	}

	@Test
	public void testBranchesOfTheRepository() {
		migrator = branchMigrator("dev");

		Map<String, List<BranchPoint.Commit>> branches = migrator.readBranches(child);

		assertEquals(Collections.singleton("main"), branches.keySet());
		List<BranchPoint.Commit> commits = branches.get("main");
		assertEquals(3, commits.size());
		BranchPoint point = BranchPoint.find(branches,
				Collections.singletonMap("comp", Arrays.asList("initial", "cs-1", "cs-x")));
		assertEquals(commits.get(1).getId(), point.getCommitId());
		migrator = null;
	}

	@Test
	public void testBranchStartsAtTheBranchPoint() throws Exception {
		migrator = branchMigrator("dev");
		String cs1 = commitOf("cs-1");
		// what scm loads for the configuration of cs-1: no generated files
		write(child, "src/a.txt", "a1");
		write(child, "src/.jazzignore", "core.ignore = {*.class}");

		migrator.startBranch(child, cs1);
		migrator.init(child);
		write(child, "src/b.txt", "b");
		migrator.commitChanges(changeSet("cs-3", "on dev"));

		assertTrue(new File(child, ".git").isFile());
		assertTrue(new File(child, ".gitignore").isFile());
		assertTrue(new File(child, "src/.gitignore").isFile());
		try (Git git = Git.open(main)) {
			RevCommit dev = git.log().add(git.getRepository().resolve("refs/heads/dev")).setMaxCount(1).call()
					.iterator().next();
			assertEquals(cs1, dev.getParent(0).name());
			assertEquals("main", git.getRepository().getBranch());
		}
		assertTrue(migrator.isMigrated("cs-1"));
		assertFalse(migrator.isMigrated("cs-2"));
		assertEquals(new File(main, ".git/worktrees/dev").getCanonicalFile(),
				MigrationReport.gitDirectory(child).getCanonicalFile());
	}

	@Test
	public void testDifferentContentIsRefused() throws Exception {
		migrator = branchMigrator("dev");
		write(child, "src/a.txt", "a2");
		write(child, "src/.jazzignore", "core.ignore = {*.class}");

		migrator.startBranch(child, commitOf("cs-1"));
		try {
			migrator.init(child);
			fail();
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("differs from the branch point"));
			assertTrue(e.getMessage(), e.getMessage().contains("src/a.txt"));
		}
	}

	@Test
	public void testBranchWithoutSharedHistory() throws Exception {
		migrator = branchMigrator("other");
		write(child, "x.txt", "x");

		migrator.startBranch(child, null);
		migrator.init(child);
		migrator.commitChanges(changeSet("cs-9", "own"));

		try (Git git = Git.open(child)) {
			assertEquals("other", git.getRepository().getBranch());
			RevCommit head = git.log().call().iterator().next();
			assertEquals(1, head.getParentCount());
			assertNull(git.getRepository().resolve("HEAD~2"));
		}
	}

	@Test
	public void testExistingBranchIsRefused() {
		migrator = branchMigrator("main");
		try {
			migrator.readBranches(child);
			fail();
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage().contains("exists already"));
		}
		migrator = null;
	}

	@Test
	public void testSandboxOnAnotherBranchIsRefused() {
		migrator = new GitMigrator(props);
		migrator.setBranch("dev");
		try {
			migrator.init(main);
			fail();
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage().contains("not on [dev]"));
		}
		migrator = null;
	}

	@Test
	public void testExistingSandboxDoesNotStartABranch() throws Exception {
		migrator = branchMigrator("dev");
		write(child, "src/a.txt", "a1");
		write(child, "src/.jazzignore", "core.ignore = {*.class}");
		migrator.startBranch(child, commitOf("cs-1"));
		migrator.init(child);
		migrator.close();

		migrator = branchMigrator("dev");
		assertNull(migrator.readBranches(child));
		migrator.init(child);
		assertTrue(migrator.isMigrated("cs-1"));
	}

	private GitMigrator branchMigrator(String branch) {
		GitMigrator result = new GitMigrator(props);
		result.setBranch(branch);
		result.setBranchRepository(main);
		return result;
	}

	private String commitOf(String changeSet) throws Exception {
		try (Git git = Git.open(main)) {
			for (RevCommit commit : git.log().add(git.getRepository().resolve(Constants.HEAD)).call()) {
				if (commit.getFooterLines(GitMigrator.CHANGE_SET_TRAILER).contains(changeSet)) {
					return commit.name();
				}
			}
		}
		throw new AssertionError(changeSet);
	}

	private static void write(File root, String name, String content) throws Exception {
		File file = new File(root, name);
		file.getParentFile().mkdirs();
		Files.writeLines(file, Arrays.asList(content), StandardCharsets.UTF_8, false);
	}
}
