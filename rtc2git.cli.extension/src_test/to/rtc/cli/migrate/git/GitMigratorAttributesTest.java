package to.rtc.cli.migrate.git;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import to.rtc.cli.migrate.ChangeSet;
import to.rtc.cli.migrate.FileProperties;
import to.rtc.cli.migrate.FileProperties.LineDelimiter;

/**
 * EWM file properties end up in .gitattributes and the file mode, so the repository reproduces what scm loads.
 */
public class GitMigratorAttributesTest {
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
	public void testDelimiterNoneKeepsCrlfBytes() throws Exception {
		migrator.init(basedir);
		write("win.txt", "a\r\nb\r\n");
		write("unix.txt", "a\r\nb\r\n");

		migrator.updateFileProperties(Arrays.asList(file("1", "win.txt", LineDelimiter.NONE, false),
				file("2", "unix.txt", LineDelimiter.LF, false)));
		migrator.commitChanges(GitMigratorHistoryTest.changeSet("cs-1", "files"));

		assertArrayEquals("a\r\nb\r\n".getBytes(StandardCharsets.US_ASCII), blob("win.txt"));
		assertArrayEquals("a\nb\n".getBytes(StandardCharsets.US_ASCII), blob("unix.txt"));
		assertTrue(read(".gitattributes").contains("* -text"));
		assertTrue(read(".gitattributes").contains("/unix.txt text eol=lf"));
	}

	@Test
	public void testPropertiesKnownBeforeInitApplyToInitialCommit() throws Exception {
		write("initial.bat", "x\r\n");

		migrator.updateFileProperties(Arrays.asList(file("1", "initial.bat", LineDelimiter.NONE, false)));
		migrator.init(basedir);

		assertArrayEquals("x\r\n".getBytes(StandardCharsets.US_ASCII), blob("initial.bat"));
	}

	@Test
	public void testExecutableFlagBecomesFileMode() throws Exception {
		migrator.init(basedir);
		write("run.sh", "echo\n");

		migrator.updateFileProperties(Arrays.asList(file("1", "run.sh", LineDelimiter.LF, true)));
		migrator.commitChanges(GitMigratorHistoryTest.changeSet("cs-1", "script"));

		assertEquals(FileMode.EXECUTABLE_FILE, mode("run.sh"));
	}

	@Test
	public void testPropertyOnlyChangeIsCommitted() throws Exception {
		props.setProperty("commit.empty.changesets", "false");
		migrator.initialize(props);
		migrator.init(basedir);
		write("a.txt", "a\n");
		migrator.updateFileProperties(Arrays.asList(file("1", "a.txt", LineDelimiter.LF, false)));
		migrator.commitChanges(GitMigratorHistoryTest.changeSet("cs-1", "add"));

		migrator.updateFileProperties(Arrays.asList(file("1", "a.txt", LineDelimiter.CRLF, false)));
		migrator.commitChanges(GitMigratorHistoryTest.changeSet("cs-2", "line delimiter changed"));

		assertEquals(Arrays.asList("cs-2"), head().getFooterLines(GitMigrator.CHANGE_SET_TRAILER));
		assertTrue(read(".gitattributes").contains("* text eol=crlf"));
	}

	@Test
	public void testUserLinesStayInFrontOfGeneratedBlock() throws Exception {
		props.setProperty("gitattributes", "*.sql text");
		migrator.initialize(props);
		migrator.init(basedir);
		write("a.txt", "a\n");

		migrator.updateFileProperties(Arrays.asList(file("1", "a.txt", LineDelimiter.LF, false)));
		migrator.commitChanges(GitMigratorHistoryTest.changeSet("cs-1", "add"));

		assertEquals(Arrays.asList("*.sql text", GitattributesGenerator.BEGIN, "* text eol=lf",
				GitattributesGenerator.END), lines(".gitattributes"));
	}

	@Test
	public void testGenerationCanBeDisabled() throws Exception {
		props.setProperty("gitattributes.from.ewm", "false");
		migrator.initialize(props);
		migrator.init(basedir);
		write("win.txt", "a\r\n");

		migrator.updateFileProperties(Arrays.asList(file("1", "win.txt", LineDelimiter.NONE, false)));
		migrator.commitChanges(GitMigratorHistoryTest.changeSet("cs-1", "files"));

		assertTrue(!new File(basedir, ".gitattributes").exists() || !read(".gitattributes").contains("-text"));
	}

	@Test
	public void testCommitterDateIsCompletionDate() throws Exception {
		migrator.init(basedir);
		write("a.txt", "a\n");
		final ChangeSet base = GitMigratorHistoryTest.changeSet("cs-1", "done later");

		migrator.commitChanges(new DelegatingChangeSet(base) {
			@Override
			public long getLastChangeDate() {
				return base.getCreationDate() + 3600000;
			}
		});

		RevCommit commit = head();
		assertEquals(base.getCreationDate(), commit.getAuthorIdent().getWhenAsInstant().toEpochMilli());
		assertEquals(base.getCreationDate() + 3600000,
				commit.getCommitterIdent().getWhenAsInstant().toEpochMilli());
	}

	private static FileProperties file(String id, String path, LineDelimiter delimiter, boolean executable) {
		return FileProperties.file(id, path, delimiter, "text/plain", "UTF-8", executable,
				Collections.<String, String> emptyMap());
	}

	private byte[] blob(String path) throws Exception {
		try (Git git = Git.open(basedir)) {
			Repository repository = git.getRepository();
			try (TreeWalk walk = TreeWalk.forPath(repository, path,
					repository.parseCommit(repository.resolve("HEAD")).getTree())) {
				return repository.open(walk.getObjectId(0)).getBytes();
			}
		}
	}

	private FileMode mode(String path) throws Exception {
		try (Git git = Git.open(basedir)) {
			Repository repository = git.getRepository();
			try (TreeWalk walk = TreeWalk.forPath(repository, path,
					repository.parseCommit(repository.resolve("HEAD")).getTree())) {
				return walk.getFileMode(0);
			}
		}
	}

	private RevCommit head() throws Exception {
		try (Git git = Git.open(basedir)) {
			return git.log().setMaxCount(1).call().iterator().next();
		}
	}

	private void write(String name, String content) throws Exception {
		java.nio.file.Files.write(new File(basedir, name).toPath(), content.getBytes(StandardCharsets.US_ASCII));
	}

	private String read(String name) throws Exception {
		return new String(java.nio.file.Files.readAllBytes(new File(basedir, name).toPath()), StandardCharsets.UTF_8);
	}

	private List<String> lines(String name) throws Exception {
		return java.nio.file.Files.readAllLines(new File(basedir, name).toPath(), StandardCharsets.UTF_8);
	}

	private static class DelegatingChangeSet implements ChangeSet {
		private final ChangeSet delegate;

		DelegatingChangeSet(ChangeSet delegate) {
			this.delegate = delegate;
		}

		@Override
		public String getUuid() {
			return delegate.getUuid();
		}

		@Override
		public String getComment() {
			return delegate.getComment();
		}

		@Override
		public String getCreatorName() {
			return delegate.getCreatorName();
		}

		@Override
		public String getEmailAddress() {
			return delegate.getEmailAddress();
		}

		@Override
		public long getCreationDate() {
			return delegate.getCreationDate();
		}

		@Override
		public List<WorkItem> getWorkItems() {
			return delegate.getWorkItems();
		}
	}
}
