package to.rtc.cli.migrate;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import to.rtc.cli.migrate.zos.SystemDefinitions;

/**
 * @author florian.buehlmann
 * @author patrick.reinhart
 */
public interface Migrator {

	/**
	 * Initializes the migration implementation with the given <code>sandboxRootDirectory</code>.
	 *
	 * @param sandboxRootDirectory
	 *            the sand box root directory
	 */
	void init(File sandboxRootDirectory);

	/**
	 * Releases all resources
	 */
	void close();

	void createTag(Tag tag);

	void commitChanges(ChangeSet changeSet);

	void intermediateCleanup();

	boolean needsIntermediateCleanup();

	/**
	 * Resolves the z/OS language and data set definitions referenced by file properties; called before
	 * {@link #init(File)}.
	 */
	default void setSystemDefinitions(SystemDefinitions definitions) {
	}

	/**
	 * Tells whether the change set was already migrated by an earlier run.
	 *
	 * @param changeSetUuid
	 *            the EWM change set UUID
	 * @return <code>true</code> if a commit for that change set exists
	 */
	default boolean isMigrated(String changeSetUuid) {
		return false;
	}

	/**
	 * Receives the EWM properties of all files in the sandbox: before {@link #init(File)} for the initial content,
	 * then before each {@link #commitChanges(ChangeSet)}.
	 *
	 * @param files
	 *            all files and folders with their current paths
	 */
	default void updateFileProperties(Collection<FileProperties> files) {
	}

	/**
	 * Reads, without changing anything, what an earlier migration left in the sandbox.
	 */
	default ResumeState inspectResume(File sandboxRootDirectory) {
		return ResumeState.NEW;
	}

	/**
	 * For a new migration: the newest change set of each target component before anything is accepted, recorded so
	 * an interrupted run can later be recovered (see {@link ResumeAnalysis}).
	 *
	 * @param newestChangeSets
	 *            component UUID -> change set UUID ("" for a component without change sets)
	 */
	default void setInitialState(Map<String, String> newestChangeSets) {
	}

	/**
	 * For a run that adds its stream as a new branch to the repository of earlier migrations: the branches there.
	 * Called before the target workspace is loaded.
	 *
	 * @return branch name -> its commits along the first parents, oldest first; <code>null</code> if this run does
	 *         not start a new branch (no such repository configured, or the sandbox has a repository already)
	 */
	default Map<String, List<BranchPoint.Commit>> readBranches(File sandboxRootDirectory) {
		return null;
	}

	/**
	 * Starts the new branch (see {@link #readBranches(File)}) at the commit, after the target workspace was set to
	 * that commit's configuration and loaded; before {@link #inspectResume(File)}.
	 *
	 * @param commitId
	 *            the branch point, <code>null</code> for a branch with its own initial commit
	 */
	default void startBranch(File sandboxRootDirectory, String commitId) {
	}

	/**
	 * @return the id of the commit created by the last {@link #commitChanges(ChangeSet)}, if any
	 */
	default String getLastCommitId() {
		return null;
	}

}
