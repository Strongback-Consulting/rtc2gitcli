package to.rtc.cli.migrate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads what the change log does not contain: the EWM properties of the files in the target workspace and of the
 * files a change set touched.
 */
public interface ChangeSetDetails {

	/**
	 * @return all files and folders currently in the target workspace
	 */
	Collection<FileProperties> readAll();

	/**
	 * Called after the change set was accepted into the target workspace.
	 */
	Update read(String changeSetUuid);

	/** What one change set changed. */
	final class Update {
		private final long lastChangeDate;
		private final List<FileProperties> changed = new ArrayList<FileProperties>();
		private final Set<String> removedItemIds = new HashSet<String>();

		public Update(long lastChangeDate) {
			this.lastChangeDate = lastChangeDate;
		}

		public Update changed(FileProperties properties) {
			changed.add(properties);
			return this;
		}

		public Update removed(String itemId) {
			removedItemIds.add(itemId);
			return this;
		}

		/**
		 * @return when the change set was completed (0 if unknown)
		 */
		public long getLastChangeDate() {
			return lastChangeDate;
		}

		/**
		 * @return the added or modified items with their properties after the change
		 */
		public List<FileProperties> getChanged() {
			return changed;
		}

		public Set<String> getRemovedItemIds() {
			return removedItemIds;
		}
	}
}
