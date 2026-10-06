package to.rtc.cli.migrate;

import java.util.Collection;
import java.util.Collections;

/**
 * Represents a tag.
 *
 * @author florian.buehlmann
 * @author patrick.reinhart
 */
public interface Tag {
	/**
	 * Returns the actual tag name.
	 * 
	 * @return the name of the tag
	 */
	public String getName();

	/**
	 * Returns the tag set creation time stamp.
	 * 
	 * @return the creation date time stamp
	 */
	public long getCreationDate();

	/**
	 * Returns the name of the tag as defined in EWM, before it was made unique or sanitized.
	 *
	 * @return the original name
	 */
	default String getOriginalName() {
		return getName();
	}

	/**
	 * Returns the UUIDs of the EWM baselines represented by this tag.
	 *
	 * @return the baseline UUIDs, possibly empty
	 */
	default Collection<String> getBaselineUuids() {
		return Collections.emptyList();
	}

	/**
	 * Returns the UUID of the EWM snapshot if this tag represents one.
	 *
	 * @return the snapshot UUID or <code>null</code> for a baseline tag
	 */
	default String getSnapshotUuid() {
		return null;
	}
}
