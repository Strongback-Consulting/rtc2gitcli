package to.rtc.cli.migrate.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import to.rtc.cli.migrate.ChangeSet;
import to.rtc.cli.migrate.Tag;
import to.rtc.cli.migrate.util.Files;

/**
 * Commit/tag behaviour that keeps a migration repeatable: one commit per change set, change set trailers, resume
 * checks and tag naming.
 */
public class GitMigratorHistoryTest {
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
	public void testCommitHasChangeSetTrailer() throws Exception {
		migrator.init(basedir);
		write("a.txt", "a");

		migrator.commitChanges(changeSet("cs-1", "first"));

		RevCommit head = head();
		assertEquals(Arrays.asList("cs-1"), head.getFooterLines(GitMigrator.CHANGE_SET_TRAILER));
		assertTrue(head.getFullMessage().startsWith("first"));
		assertTrue(migrator.isMigrated("cs-1"));
	}

	@Test
	public void testTrailerCanBeDisabled() throws Exception {
		props.setProperty("commit.changeset.trailer", "false");
		migrator.initialize(props);
		migrator.init(basedir);
		write("a.txt", "a");

		migrator.commitChanges(changeSet("cs-1", "first"));

		assertTrue(head().getFooterLines(GitMigrator.CHANGE_SET_TRAILER).isEmpty());
	}

	@Test
	public void testChangeSetWithoutContentChangeStillGetsACommit() throws Exception {
		migrator.init(basedir);
		write("a.txt", "a");
		migrator.commitChanges(changeSet("cs-1", "first"));

		migrator.commitChanges(changeSet("cs-2", "only properties changed"));

		assertEquals(Arrays.asList("cs-2"), head().getFooterLines(GitMigrator.CHANGE_SET_TRAILER));
	}

	@Test
	public void testEmptyChangeSetCommitsCanBeDisabled() throws Exception {
		props.setProperty("commit.empty.changesets", "false");
		migrator.initialize(props);
		migrator.init(basedir);
		write("a.txt", "a");
		migrator.commitChanges(changeSet("cs-1", "first"));

		migrator.commitChanges(changeSet("cs-2", "nothing"));

		assertEquals(Arrays.asList("cs-1"), head().getFooterLines(GitMigrator.CHANGE_SET_TRAILER));
	}

	@Test
	public void testResumeKnowsMigratedChangeSets() throws Exception {
		migrator.init(basedir);
		write("a.txt", "a");
		migrator.commitChanges(changeSet("cs-1", "first"));
		migrator.close();

		migrator = new GitMigrator(props);
		migrator.init(basedir);

		assertTrue(migrator.isMigrated("cs-1"));
		assertFalse(migrator.isMigrated("cs-2"));
	}

	@Test
	public void testResumeRefusesUncommittedChanges() throws Exception {
		migrator.init(basedir);
		write("a.txt", "a");
		migrator.commitChanges(changeSet("cs-1", "first"));
		migrator.close();
		write("a.txt", "accepted but not committed");

		migrator = new GitMigrator(props);
		try {
			migrator.init(basedir);
			fail("expected IllegalStateException");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("a.txt"));
		}
	}

	@Test
	public void testCommitAfterIntermediateCleanup() throws Exception {
		migrator.init(basedir);
		migrator.intermediateCleanup();
		write("a.txt", "a");

		migrator.commitChanges(changeSet("cs-1", "after gc"));

		assertEquals(Arrays.asList("cs-1"), head().getFooterLines(GitMigrator.CHANGE_SET_TRAILER));
	}

	@Test
	public void testCreateTagNameSanitizes() {
		assertEquals("a_.b", GitMigrator.createTagName("a..b"));
		assertEquals("x_y_z_w__", GitMigrator.createTagName("x~y^z:w?*"));
		assertEquals("hidden", GitMigrator.createTagName(".hidden"));
		assertEquals("rel/1.0", GitMigrator.createTagName("rel/1.0"));
		assertEquals("name.lock_", GitMigrator.createTagName("name.lock"));
		assertEquals("x", GitMigrator.createTagName("-x"));
		assertEquals("_{a}", GitMigrator.createTagName("@{a}"));
		assertEquals("Release_1_[final]".replace('[', '_').replace(']', '_'),
				GitMigrator.createTagName("Release 1 [final]"));
	}

	@Test
	public void testCreateTagIsAnnotatedWithBaselineDetails() throws Exception {
		migrator.init(basedir);
		long created = 1500000000000L;

		migrator.createTag(tag("R 1.0", created, "bl-1", "bl-2"));

		try (Git git = Git.open(basedir); RevWalk walk = new RevWalk(git.getRepository())) {
			Ref ref = git.getRepository().exactRef(Constants.R_TAGS + "R_1.0");
			RevTag revTag = walk.parseTag(ref.getObjectId());
			assertEquals(created, revTag.getTaggerIdent().getWhenAsInstant().toEpochMilli());
			assertTrue(revTag.getFullMessage().startsWith("EWM baseline: R 1.0"));
			assertTrue(revTag.getFullMessage().contains(GitMigrator.BASELINE_TRAILER + ": bl-1"));
			assertTrue(revTag.getFullMessage().contains(GitMigrator.BASELINE_TRAILER + ": bl-2"));
		}
	}

	@Test
	public void testCreateTagTwiceOnSameCommitIsIdempotent() throws Exception {
		migrator.init(basedir);

		migrator.createTag(tag("R1", 1L));
		migrator.createTag(tag("R1", 1L));

		assertEquals(Arrays.asList("refs/tags/R1"), tagNames());
	}

	@Test
	public void testCreateTagWithSameNameOnOtherCommitGetsSuffix() throws Exception {
		migrator.init(basedir);
		migrator.createTag(tag("R1", 1L));
		write("a.txt", "a");
		migrator.commitChanges(changeSet("cs-1", "next"));

		migrator.createTag(tag("R1", 2L));

		assertEquals(Arrays.asList("refs/tags/R1", "refs/tags/R1_2"), tagNames());
	}

	private List<String> tagNames() throws Exception {
		try (Git git = Git.open(basedir)) {
			List<String> names = new ArrayList<String>();
			for (Ref ref : git.tagList().call()) {
				names.add(ref.getName());
			}
			Collections.sort(names);
			return names;
		}
	}

	private RevCommit head() throws Exception {
		try (Git git = Git.open(basedir)) {
			return git.log().setMaxCount(1).call().iterator().next();
		}
	}

	private void write(String name, String content) throws Exception {
		Files.writeLines(new File(basedir, name), Arrays.asList(content), StandardCharsets.UTF_8, false);
	}

	static ChangeSet changeSet(final String uuid, final String comment) {
		return new ChangeSet() {
			@Override
			public String getUuid() {
				return uuid;
			}

			@Override
			public String getComment() {
				return comment;
			}

			@Override
			public String getCreatorName() {
				return "Jane Doe";
			}

			@Override
			public String getEmailAddress() {
				return "jane@example.com";
			}

			@Override
			public long getCreationDate() {
				return 1500000000000L;
			}

			@Override
			public List<WorkItem> getWorkItems() {
				return Collections.emptyList();
			}
		};
	}

	private static Tag tag(final String name, final long created, final String... baselines) {
		return new Tag() {
			@Override
			public String getName() {
				return name;
			}

			@Override
			public long getCreationDate() {
				return created;
			}

			@Override
			public Collection<String> getBaselineUuids() {
				return Arrays.asList(baselines);
			}
		};
	}
}
