package to.rtc.cli.migrate;

import java.io.File;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import to.rtc.cli.migrate.command.RtcCommands;
import to.rtc.cli.migrate.util.Files;

import com.ibm.team.filesystem.cli.core.Constants;
import com.ibm.team.filesystem.rcp.core.internal.changelog.IChangeLogOutput;
import com.ibm.team.rtc.cli.infrastructure.internal.core.CLIClientException;

/**
 * Replays change sets into the sandbox (accept, load) and hands each one to the {@link Migrator} for committing.
 */
@SuppressWarnings("restriction")
public class RtcMigrator {

	private static final int ACCEPTS_BEFORE_LOCAL_HISTORY_CLEAN = 1000;
	protected final IChangeLogOutput output;
	private final RtcCommands commands;
	private final Migrator migrator;
	private final Set<String> initiallyLoadedComponents;
	private final boolean acceptMissingChangeSets;
	private ChangeSetDetails details;
	private final FilePropertiesModel model = new FilePropertiesModel();
	private File sandboxDirectory;

	/**
	 * @param initiallyLoadedComponents
	 *            UUIDs of the components already loaded in the sandbox
	 * @param acceptMissingChangeSets
	 *            on a gap, accept the missing change sets together with the requested one (they end up in a single
	 *            commit); otherwise a gap stops the migration
	 */
	public RtcMigrator(IChangeLogOutput output, RtcCommands commands, Migrator migrator, File sandboxDirectory,
			Collection<String> initiallyLoadedComponents, boolean acceptMissingChangeSets) {
		this.output = output;
		this.commands = commands;
		this.migrator = migrator;
		this.sandboxDirectory = sandboxDirectory;
		this.initiallyLoadedComponents = new HashSet<String>(initiallyLoadedComponents);
		this.acceptMissingChangeSets = acceptMissingChangeSets;
	}

	/**
	 * Reads the EWM file properties after each accept and passes them to the migrator.
	 *
	 * @param initial
	 *            the properties of the files already in the sandbox
	 */
	public void useChangeSetDetails(ChangeSetDetails changeSetDetails, Collection<FileProperties> initial) {
		this.details = changeSetDetails;
		model.reset(initial);
	}

	public void migrateTag(RtcTag tag) throws CLIClientException {
		List<RtcChangeSet> changeSets = tag.getOrderedChangeSets();
		int changeSetCounter = 0;
		int numberOfChangesets = changeSets.size();
		String tagName = tag.getName();
		for (RtcChangeSet changeSet : changeSets) {
			boolean accepted = false;
			boolean committed = false;
			try {
				long startAccept = System.currentTimeMillis();
				acceptAndLoadChangeSet(changeSet);
				accepted = true;
				handleInitialLoad(changeSet);
				if (details != null) {
					ChangeSetDetails.Update update = details.read(changeSet.getUuid());
					changeSet.setLastChangeDate(update.getLastChangeDate());
					model.apply(update);
					migrator.updateFileProperties(model.getAll());
				}
				long acceptDuration = System.currentTimeMillis() - startAccept;
				long commitDuration = 0;
				if (migrator.isMigrated(changeSet.getUuid())) {
					output.writeLine("Change set [" + changeSet.getUuid() + "] was already migrated, no new commit");
				} else {
					commitDuration = commit(changeSet);
				}
				committed = true;
				changeSetCounter++;
				output.writeLine("Migrated [" + tagName + "] [" + changeSetCounter + "]/[" + numberOfChangesets
						+ "] changesets. Accept took " + acceptDuration + "ms commit took " + commitDuration + "ms");
				if (migrator.needsIntermediateCleanup()) {
					intermediateCleanup();
				}
				if (changeSetCounter % ACCEPTS_BEFORE_LOCAL_HISTORY_CLEAN == 0) {
					cleanLocalHistory();
				}
			} catch (CLIClientException | RuntimeException failure) {
				output.writeLine("Changeset details:");
				output.writeLine("  Tag original name       : " + tag.getOriginalName());
				output.writeLine("  Tag creation date       : " + new Date(tag.getCreationDate()));
				output.writeLine("  Changeset comment       : " + changeSet.getComment());
				output.writeLine("  Changeset creator       : " + changeSet.getCreatorName());
				output.writeLine("  Changeset creation date : " + new Date(changeSet.getCreationDate()));
				output.writeLine("  Changeset component     : " + changeSet.getComponent());
				output.writeLine("  Changeset UUID          : " + changeSet.getUuid());
				if (accepted && !committed) {
					// a rerun would not see it as incoming any more and it would be missing in git
					output.writeLine("The change set is accepted in workspace [" + commands.getWorkspace()
							+ "] but not committed. Before running the migration again, discard it with:");
					output.writeLine("  scm discard -w \"" + commands.getWorkspace() + "\" " + changeSet.getUuid()
							+ "   (plus your -r/login options)");
				}
				throw failure;
			}
		}
		cleanLocalHistory();
		if (tag.doCreateTag()) {
			migrator.createTag(tag);
		}
	}

	void intermediateCleanup() {
		long startCleanup = System.currentTimeMillis();
		migrator.intermediateCleanup();
		output.writeLine("Intermediate cleanup had ["
				+ (TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - startCleanup)) + "]sec");
	}

	long commit(RtcChangeSet changeSet) {
		long startCommit = System.currentTimeMillis();
		migrator.commitChanges(changeSet);
		return System.currentTimeMillis() - startCommit;
	}

	private void cleanLocalHistory() {
		File localHistoryDirectory = new File(sandboxDirectory,
				".metadata/.plugins/org.eclipse.core.resources/.history");
		if (localHistoryDirectory.exists() && localHistoryDirectory.isDirectory()) {
			long start = System.currentTimeMillis();
			Files.delete(localHistoryDirectory);
			output.writeLine("Cleanup of local history had ["
					+ (TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - start)) + "]sec");
		}
	}

	private void acceptAndLoadChangeSet(RtcChangeSet changeSet) throws CLIClientException {
		output.setIndent(2);
		String uuid = changeSet.getUuid();
		int result = commands.accept(uuid, false);
		if (result == Constants.STATUS_OUT_OF_SYNC) {
			output.writeLine("Sandbox out of sync, loading the workspace again with --force");
			requireSuccess("load --force", commands.load(null, true));
			result = commands.accept(uuid, false);
		}
		if (result == Constants.STATUS_GAP) {
			if (!acceptMissingChangeSets) {
				throw new CLIClientException("Accepting change set [" + uuid
						+ "] requires earlier change sets that are not accepted yet (gap). Set"
						+ " rtc.accept.missing.changesets=true to accept them together with this change set;"
						+ " they are then committed as a single commit.");
			}
			output.writeLine("WARNING: gap in change set [" + uuid
					+ "]; accepting the missing change sets into the same commit");
			result = commands.accept(uuid, true);
		}
		if (result != Constants.STATUS_WORKSPACE_UNCHANGED) {
			requireSuccess("accept " + uuid, result);
		}
	}

	private void handleInitialLoad(RtcChangeSet changeSet) throws CLIClientException {
		String component = changeSet.getComponentKey();
		if (!initiallyLoadedComponents.contains(component)) {
			int result = commands.load(component, false);
			if (result != Constants.STATUS_OK.intValue()) {
				throw new CLIClientException("Loading component [" + changeSet.getComponent()
						+ "] failed with status [" + result + "]. Is the sandbox valid? Run [scm load] of the target"
						+ " workspace before [scm migrate-to-git].");
			}
			initiallyLoadedComponents.add(component);
		}
	}

	private static void requireSuccess(String operation, int result) throws CLIClientException {
		if (result != Constants.STATUS_OK.intValue()) {
			throw new CLIClientException("[" + operation + "] failed with status [" + result + "]");
		}
	}
}
