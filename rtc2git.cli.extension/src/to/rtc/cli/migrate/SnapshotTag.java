package to.rtc.cli.migrate;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * An EWM snapshot (baseline set) of the migrated stream, tagged once all of its baselines are migrated.
 */
public final class SnapshotTag implements Tag {
	private final String uuid;
	private final String originalName;
	private final String tagName;
	private final long creationDate;
	private final Set<String> baselineUuids;

	public SnapshotTag(String uuid, String originalName, String tagPrefix, long creationDate,
			Collection<String> baselineUuids) {
		this.uuid = uuid;
		this.originalName = originalName;
		this.tagName = tagPrefix + originalName;
		this.creationDate = creationDate;
		this.baselineUuids = Collections.unmodifiableSet(new LinkedHashSet<String>(baselineUuids));
	}

	@Override
	public String getName() {
		return tagName;
	}

	@Override
	public String getOriginalName() {
		return originalName;
	}

	@Override
	public long getCreationDate() {
		return creationDate;
	}

	@Override
	public Collection<String> getBaselineUuids() {
		return baselineUuids;
	}

	@Override
	public String getSnapshotUuid() {
		return uuid;
	}

	@Override
	public String toString() {
		return "snapshot " + originalName + " (" + uuid + ")";
	}
}
