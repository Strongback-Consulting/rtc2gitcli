package to.rtc.cli.migrate.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static to.rtc.cli.migrate.git.GitMigratorHistoryTest.changeSet;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Large files and configured patterns go to Git LFS: pointers in git, content in the LFS object store.
 */
public class GitMigratorLfsTest {
	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();

	private final Properties props = new Properties();
	private GitMigrator migrator;
	private File basedir;

	@Before
	public void setUp() {
		props.setProperty("lfs.threshold", "64");
		props.setProperty("lfs.patterns", "*.zip");
		basedir = tempFolder.getRoot();
	}

	@After
	public void tearDown() {
		if (migrator != null) {
			migrator.close();
		}
	}

	@Test
	public void testLargeFilesAndPatternsAreStoredInLfs() throws Exception {
		write("small.txt", "small");
		write("big.bin", repeat('x', 100));
		write("tiny.zip", "zip");
		start();

		String pointer = blob("big.bin");
		assertTrue(pointer, pointer.startsWith(LfsCleanFilter.POINTER_VERSION + "\noid sha256:"));
		assertTrue(pointer, pointer.endsWith("\nsize 100\n"));
		assertTrue(blob("tiny.zip").endsWith("\nsize 3\n"));
		assertEquals("small", blob("small.txt"));
		String oid = pointer.substring(pointer.indexOf("sha256:") + 7, pointer.indexOf("\nsize"));
		File object = LfsCleanFilter.objectFile(new File(basedir, ".git/lfs/objects"), oid);
		assertEquals(repeat('x', 100), new String(Files.readAllBytes(object.toPath()), StandardCharsets.UTF_8));
		String attributes = blob(".gitattributes");
		assertTrue(attributes, attributes.contains(GitMigrator.LFS_BEGIN + "\n*.zip" + GitMigrator.LFS_ATTRIBUTES
				+ "\n/big.bin" + GitMigrator.LFS_ATTRIBUTES + "\n" + GitMigrator.LFS_END));
		assertClean();
	}

	@Test
	public void testFilesGoToLfsWhenTheyGrowAndLeaveWhenDeleted() throws Exception {
		write("data.bin", "small");
		start();
		assertEquals("small", blob("data.bin"));

		write("data.bin", repeat('y', 80));
		migrator.commitChanges(changeSet("cs-1", "grow"));
		assertTrue(blob("data.bin").endsWith("\nsize 80\n"));
		assertClean();

		write("other.txt", "other");
		ObjectId before = blobId("data.bin");
		migrator.commitChanges(changeSet("cs-2", "unrelated"));
		assertEquals(before, blobId("data.bin"));

		new File(basedir, "data.bin").delete();
		migrator.commitChanges(changeSet("cs-3", "delete"));
		assertFalse(blob(".gitattributes").contains("/data.bin"));
	}

	@Test
	public void testResumeKeepsLfsFiles() throws Exception {
		write("big.bin", repeat('x', 100));
		start();
		migrator.close();

		migrator = new GitMigrator(props);
		migrator.init(basedir);
		write("big.bin", repeat('z', 10));
		migrator.commitChanges(changeSet("cs-1", "shrink"));

		// once in LFS, a file stays there
		assertTrue(blob("big.bin").endsWith("\nsize 10\n"));
		assertTrue(blob(".gitattributes").contains("/big.bin" + GitMigrator.LFS_ATTRIBUTES));
	}

	@Test
	public void testOffByDefault() throws Exception {
		props.clear();
		write("big.bin", repeat('x', 100));
		start();

		assertEquals(repeat('x', 100), blob("big.bin"));
		assertFalse(new File(basedir, ".git/lfs").exists());
	}

	@Test
	public void testUnescape() {
		String path = "a b/c*d[1].bin";
		assertEquals(path, GitattributesGenerator.unescape(GitattributesGenerator.escape(path)));
	}

	private void start() {
		migrator = new GitMigrator(props);
		migrator.init(basedir);
	}

	private void assertClean() throws Exception {
		try (Git git = Git.open(basedir)) {
			assertTrue(git.status().call().getUncommittedChanges().toString(), git.status().call().isClean());
		}
	}

	private ObjectId blobId(String path) throws Exception {
		try (Git git = Git.open(basedir)) {
			Repository repository = git.getRepository();
			try (TreeWalk walk = TreeWalk.forPath(repository, path,
					repository.parseCommit(repository.resolve("HEAD")).getTree())) {
				return walk.getObjectId(0);
			}
		}
	}

	private String blob(String path) throws Exception {
		try (Git git = Git.open(basedir)) {
			return new String(git.getRepository().open(blobId(path)).getBytes(), StandardCharsets.UTF_8);
		}
	}

	private void write(String name, String content) throws Exception {
		File file = new File(basedir, name);
		file.getParentFile().mkdirs();
		Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
	}

	private static String repeat(char c, int count) {
		return String.valueOf(c).repeat(count);
	}
}
