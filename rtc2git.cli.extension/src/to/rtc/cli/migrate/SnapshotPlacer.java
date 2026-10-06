package to.rtc.cli.migrate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Decides when a snapshot can be tagged: after the migrated tag that contains the last of its baselines. Baselines
 * that no migrated tag contains (for example the start state of the target) do not hold a snapshot back.
 */
public final class SnapshotPlacer {
	private final List<SnapshotTag> waiting;
	private final Set<String> migratedTagBaselines;
	private final Set<String> reached = new HashSet<String>();

	/**
	 * @param snapshots
	 *            the snapshots to tag, oldest first
	 * @param migratedTagBaselines
	 *            the baselines of all tags that will be migrated
	 */
	public SnapshotPlacer(List<SnapshotTag> snapshots, Collection<String> migratedTagBaselines) {
		this.waiting = new ArrayList<SnapshotTag>(snapshots);
		this.migratedTagBaselines = new HashSet<String>(migratedTagBaselines);
	}

	/**
	 * @param baselines
	 *            the baselines of the tag that was just migrated (empty before the first tag)
	 * @return the snapshots that are complete now, in creation order
	 */
	public List<SnapshotTag> reached(Collection<String> baselines) {
		reached.addAll(baselines);
		List<SnapshotTag> complete = new ArrayList<SnapshotTag>();
		for (Iterator<SnapshotTag> it = waiting.iterator(); it.hasNext();) {
			SnapshotTag snapshot = it.next();
			boolean ready = true;
			for (String baseline : snapshot.getBaselineUuids()) {
				if (migratedTagBaselines.contains(baseline) && !reached.contains(baseline)) {
					ready = false;
					break;
				}
			}
			if (ready) {
				complete.add(snapshot);
				it.remove();
			}
		}
		return complete;
	}

	/**
	 * @return snapshots that could not be placed (their baselines are in tags that were not migrated)
	 */
	public List<SnapshotTag> getWaiting() {
		return waiting;
	}
}
