package to.rtc.cli.migrate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import to.rtc.cli.migrate.command.RtcCommands;

import com.ibm.team.filesystem.cli.core.Constants;
import com.ibm.team.rtc.cli.infrastructure.internal.core.CLIClientException;

/**
 * How {@link RtcMigrator} reacts to the status codes of <code>scm accept</code> and <code>scm load</code>.
 */
@SuppressWarnings("restriction")
public class RtcMigratorTest {
	private static final int OK = Constants.STATUS_OK.intValue();

	private final ScriptedCommands commands = new ScriptedCommands();
	private final RecordingMigrator migrator = new RecordingMigrator();

	@Test
	public void testAcceptLoadsNewComponentOnceAndCommits() throws Throwable {
		commands.acceptResults(OK, OK);
		commands.loadResults(OK);

		migrator(false).migrateTag(tag("cs-1", "cs-2"));

		assertEquals(Arrays.asList("accept cs-1", "load comp-uuid", "accept cs-2"), commands.calls);
		assertEquals(Arrays.asList("cs-1", "cs-2"), migrator.commits);
		assertEquals(Arrays.asList("R1"), migrator.tags);
	}

	@Test
	public void testAlreadyLoadedComponentIsNotLoadedAgain() throws Throwable {
		commands.acceptResults(OK);

		migrator(false, "comp-uuid").migrateTag(tag("cs-1"));

		assertEquals(Arrays.asList("accept cs-1"), commands.calls);
	}

	@Test
	public void testAcceptFailureStopsWithoutCommit() throws Throwable {
		for (int status : new int[] { Constants.STATUS_FAILURE, Constants.STATUS_CONFLICT,
				Constants.STATUS_NWAY_CONFLICT, Constants.STATUS_INTERNAL_ERROR }) {
			ScriptedCommands failing = new ScriptedCommands();
			failing.acceptResults(status);
			RecordingMigrator recording = new RecordingMigrator();
			try {
				new RtcMigrator(new StreamOutput(System.out), failing, recording, new File("."),
						Collections.singleton("comp-uuid"), false).migrateTag(tag("cs-1"));
				fail("status " + status + " must stop the migration");
			} catch (CLIClientException e) {
				assertTrue(e.getMessage(), e.getMessage().contains("[" + status + "]"));
			}
			assertTrue(recording.commits.isEmpty());
		}
	}

	@Test
	public void testOutOfSyncReloadsAndAcceptsAgain() throws Throwable {
		commands.acceptResults(Constants.STATUS_OUT_OF_SYNC, OK);
		commands.loadResults(OK);

		migrator(false, "comp-uuid").migrateTag(tag("cs-1"));

		assertEquals(Arrays.asList("accept cs-1", "load (all) --force", "accept cs-1"), commands.calls);
		assertEquals(Arrays.asList("cs-1"), migrator.commits);
	}

	@Test
	public void testOutOfSyncWithFailingReloadStops() throws Throwable {
		commands.acceptResults(Constants.STATUS_OUT_OF_SYNC);
		commands.loadResults(Constants.STATUS_FAILURE);

		try {
			migrator(false, "comp-uuid").migrateTag(tag("cs-1"));
			fail();
		} catch (CLIClientException e) {
			assertTrue(e.getMessage().contains("load --force"));
		}
		assertTrue(migrator.commits.isEmpty());
	}

	@Test
	public void testGapStopsByDefault() throws Throwable {
		commands.acceptResults(Constants.STATUS_GAP);

		try {
			migrator(false, "comp-uuid").migrateTag(tag("cs-1"));
			fail();
		} catch (CLIClientException e) {
			assertTrue(e.getMessage().contains("rtc.accept.missing.changesets"));
		}
		assertEquals(Arrays.asList("accept cs-1"), commands.calls);
		assertTrue(migrator.commits.isEmpty());
	}

	@Test
	public void testGapAcceptsMissingChangeSetsWhenEnabled() throws Throwable {
		commands.acceptResults(Constants.STATUS_GAP, OK);

		migrator(true, "comp-uuid").migrateTag(tag("cs-1"));

		assertEquals(Arrays.asList("accept cs-1", "accept cs-1 --accept-missing-changesets"), commands.calls);
		assertEquals(Arrays.asList("cs-1"), migrator.commits);
	}

	@Test
	public void testUnchangedWorkspaceStillCommits() throws Throwable {
		commands.acceptResults(Constants.STATUS_WORKSPACE_UNCHANGED);

		migrator(false, "comp-uuid").migrateTag(tag("cs-1"));

		assertEquals(Arrays.asList("cs-1"), migrator.commits);
	}

	@Test
	public void testAlreadyMigratedChangeSetIsAcceptedButNotCommittedAgain() throws Throwable {
		commands.acceptResults(OK, OK);
		migrator.migrated.add("cs-1");

		migrator(false, "comp-uuid").migrateTag(tag("cs-1", "cs-2"));

		assertEquals(Arrays.asList("accept cs-1", "accept cs-2"), commands.calls);
		assertEquals(Arrays.asList("cs-2"), migrator.commits);
	}

	@Test
	public void testFailingComponentLoadStops() throws Throwable {
		commands.acceptResults(OK);
		commands.loadResults(Constants.STATUS_FAILURE);

		try {
			migrator(false).migrateTag(tag("cs-1"));
			fail();
		} catch (CLIClientException e) {
			assertTrue(e.getMessage().contains("Loading component"));
		}
		assertTrue(migrator.commits.isEmpty());
	}

	private RtcMigrator migrator(boolean acceptMissing, String... loadedComponents) {
		return new RtcMigrator(new StreamOutput(System.out), commands, migrator, new File("."),
				Arrays.asList(loadedComponents), acceptMissing);
	}

	private static RtcTag tag(String... changeSetUuids) {
		RtcTag tag = new RtcTag("bl-1").setOriginalName("R1").setCreationDate(1000);
		long date = 1;
		for (String uuid : changeSetUuids) {
			tag.add(new RtcChangeSet(uuid).setComponent("comp").setComponentUuid("comp-uuid")
					.setCreationDate(date++).setText("comment " + uuid));
		}
		return tag;
	}

	private static final class ScriptedCommands implements RtcCommands {
		final List<String> calls = new ArrayList<String>();
		private final Deque<Integer> acceptResults = new ArrayDeque<Integer>();
		private final Deque<Integer> loadResults = new ArrayDeque<Integer>();

		void acceptResults(int... results) {
			for (int result : results) {
				acceptResults.add(result);
			}
		}

		void loadResults(int... results) {
			for (int result : results) {
				loadResults.add(result);
			}
		}

		@Override
		public int accept(String changeSetUuid, boolean acceptMissingChangeSets) {
			calls.add("accept " + changeSetUuid + (acceptMissingChangeSets ? " --accept-missing-changesets" : ""));
			return acceptResults.remove();
		}

		@Override
		public int load(String component, boolean force) {
			calls.add("load " + (component == null ? "(all)" : component) + (force ? " --force" : ""));
			return loadResults.remove();
		}
	}

	private static final class RecordingMigrator implements Migrator {
		final List<String> commits = new ArrayList<String>();
		final List<String> tags = new ArrayList<String>();
		final Set<String> migrated = new HashSet<String>();

		@Override
		public void init(File sandboxRootDirectory) {
		}

		@Override
		public void close() {
		}

		@Override
		public void createTag(Tag tag) {
			tags.add(tag.getName());
		}

		@Override
		public void commitChanges(ChangeSet changeSet) {
			commits.add(changeSet.getUuid());
		}

		@Override
		public void intermediateCleanup() {
		}

		@Override
		public boolean needsIntermediateCleanup() {
			return false;
		}

		@Override
		public boolean isMigrated(String changeSetUuid) {
			return migrated.contains(changeSetUuid);
		}
	}
}
