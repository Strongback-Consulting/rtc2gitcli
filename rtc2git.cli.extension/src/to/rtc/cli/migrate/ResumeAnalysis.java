package to.rtc.cli.migrate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds change sets that an interrupted migration accepted into the target workspace but did not commit: per
 * component, everything in the target history after the newest change set that is in git (or, if none is, after the
 * change set the target workspace started with).
 */
public final class ResumeAnalysis {
	private final List<String> pending = new ArrayList<String>();
	private final Set<String> undetermined = new TreeSet<String>();

	private ResumeAnalysis() {
	}

	/**
	 * @param targetHistory
	 *            component UUID -> change set UUIDs of the target workspace, oldest first
	 * @param migrated
	 *            change sets that have a commit
	 * @param base
	 *            component UUID -> newest change set of the target workspace when the migration started (empty string
	 *            if the component had none); components missing here were not recorded
	 */
	public static ResumeAnalysis analyse(Map<String, List<String>> targetHistory, Set<String> migrated,
			Map<String, String> base) {
		ResumeAnalysis analysis = new ResumeAnalysis();
		for (Map.Entry<String, List<String>> component : targetHistory.entrySet()) {
			List<String> history = component.getValue();
			String start = base.get(component.getKey());
			int last = -2; // -2: no reference point, -1: before the first change set
			if (start != null && start.isEmpty()) {
				last = -1;
			}
			for (int i = history.size() - 1; i >= 0; i--) {
				String changeSet = history.get(i);
				if (migrated.contains(changeSet) || changeSet.equals(start)) {
					last = i;
					break;
				}
			}
			if (last == -2) {
				if (!history.isEmpty()) {
					analysis.undetermined.add(component.getKey());
				}
				continue;
			}
			analysis.pending.addAll(history.subList(last + 1, history.size()));
		}
		return analysis;
	}

	/**
	 * @return accepted but not committed change sets, oldest first within each component
	 */
	public List<String> getPending() {
		return Collections.unmodifiableList(pending);
	}

	/**
	 * @return components without a reference point (no commit and no recorded start), which cannot be checked
	 */
	public Set<String> getUndeterminedComponents() {
		return Collections.unmodifiableSet(undetermined);
	}
}
