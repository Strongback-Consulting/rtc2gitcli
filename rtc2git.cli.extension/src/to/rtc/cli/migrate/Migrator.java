package to.rtc.cli.migrate;

import java.io.File;
import java.util.Collection;

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

}
