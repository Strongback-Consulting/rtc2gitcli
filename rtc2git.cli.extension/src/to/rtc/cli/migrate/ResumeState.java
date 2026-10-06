package to.rtc.cli.migrate;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * What an earlier migration left in the sandbox: the change sets with a commit and the state of the target workspace
 * when it started.
 */
public final class ResumeState {
	public static final ResumeState NEW = new ResumeState(false, Collections.<String> emptySet(),
			Collections.<String, String> emptyMap());

	private final boolean resume;
	private final Set<String> migrated;
	private final Map<String, String> base;

	public ResumeState(boolean resume, Set<String> migrated, Map<String, String> base) {
		this.resume = resume;
		this.migrated = Collections.unmodifiableSet(migrated);
		this.base = Collections.unmodifiableMap(base);
	}

	/**
	 * @return whether the sandbox already contains a migration
	 */
	public boolean isResume() {
		return resume;
	}

	public Set<String> getMigrated() {
		return migrated;
	}

	/**
	 * @return component UUID -> newest change set of the target workspace when the migration started ("" if none)
	 */
	public Map<String, String> getBase() {
		return base;
	}
}
