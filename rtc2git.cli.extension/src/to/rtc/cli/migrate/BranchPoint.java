package to.rtc.cli.migrate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Where the branch of a stream starts in a repository that already holds the branches of other streams: the newest
 * commit whose EWM configuration the stream fully contains, in the order the stream received the change sets.
 * <p>
 * Walking a branch from its first commit, every commit of a change set must be the next change set of its component
 * in the stream's history; the walk stops at the first one that is not (a change set the stream does not have, or got
 * in another order). Commits without a change set (generated files) do not stop it. The branch with the longest match
 * wins.
 */
public final class BranchPoint {

	/**
	 * A commit of an existing branch, as far as the branch point needs it.
	 */
	public static final class Commit {
		private final String id;
		private final String changeSet;
		private final Map<String, String> base;

		/**
		 * @param id
		 *            commit id
		 * @param changeSet
		 *            the change set UUID of the commit, <code>null</code> for commits without one
		 * @param base
		 *            component UUID -> newest change set of the target workspace when the migration started (the
		 *            <code>EWM-Base</code> trailers of an initial commit; "" for a component without change sets)
		 */
		public Commit(String id, String changeSet, Map<String, String> base) {
			this.id = id;
			this.changeSet = changeSet;
			this.base = base == null ? Collections.<String, String> emptyMap() : base;
		}

		public String getId() {
			return id;
		}
	}

	private final String branch;
	private final String commitId;
	private final int changeSets;
	private final Map<String, List<String>> configuration;
	private final String stop;

	private BranchPoint(String branch, String commitId, int changeSets, Map<String, List<String>> configuration,
			String stop) {
		this.branch = branch;
		this.commitId = commitId;
		this.changeSets = changeSets;
		this.configuration = configuration;
		this.stop = stop;
	}

	/**
	 * @param branches
	 *            branch name -> its commits along the first parents, oldest first
	 * @param streamHistory
	 *            component UUID -> change set UUIDs of the stream, in delivery order (oldest first)
	 * @return the best branch point, or <code>null</code> if no branch starts with a configuration of the stream
	 */
	public static BranchPoint find(Map<String, List<Commit>> branches, Map<String, List<String>> streamHistory) {
		Map<String, String> owners = new HashMap<String, String>();
		Map<String, Integer> index = new HashMap<String, Integer>();
		for (Map.Entry<String, List<String>> component : streamHistory.entrySet()) {
			List<String> history = component.getValue();
			for (int i = 0; i < history.size(); i++) {
				owners.put(history.get(i), component.getKey());
				index.put(history.get(i), Integer.valueOf(i));
			}
		}
		BranchPoint best = null;
		for (Map.Entry<String, List<Commit>> branch : new TreeMap<String, List<Commit>>(branches).entrySet()) {
			BranchPoint candidate = walk(branch.getKey(), branch.getValue(), streamHistory, owners, index);
			if (candidate != null && (best == null || candidate.changeSets > best.changeSets)) {
				best = candidate;
			}
		}
		return best;
	}

	private static BranchPoint walk(String branch, List<Commit> commits, Map<String, List<String>> streamHistory,
			Map<String, String> owners, Map<String, Integer> index) {
		if (commits.isEmpty()) {
			return null;
		}
		// the first commit holds the start state of the migration: the stream must contain it
		Map<String, Integer> next = new LinkedHashMap<String, Integer>();
		for (Map.Entry<String, String> start : new TreeMap<String, String>(commits.get(0).base).entrySet()) {
			String component = start.getKey();
			String changeSet = start.getValue();
			if (changeSet.isEmpty()) {
				next.put(component, Integer.valueOf(0));
				continue;
			}
			if (!component.equals(owners.get(changeSet))) {
				return null;
			}
			next.put(component, Integer.valueOf(index.get(changeSet).intValue() + 1));
		}
		Commit last = commits.get(0);
		int matched = 0;
		String stop = null;
		for (Commit commit : commits.subList(1, commits.size())) {
			if (commit.changeSet != null) {
				String owner = owners.get(commit.changeSet);
				if (owner == null) {
					stop = "change set " + commit.changeSet + " is not in the stream";
					break;
				}
				Integer expected = next.get(owner);
				int at = index.get(commit.changeSet).intValue();
				if (expected == null || expected.intValue() != at) {
					stop = "change set " + commit.changeSet + " is in the stream in another order";
					break;
				}
				next.put(owner, Integer.valueOf(at + 1));
				matched++;
			}
			last = commit;
		}
		Map<String, List<String>> configuration = new TreeMap<String, List<String>>();
		for (Map.Entry<String, Integer> component : next.entrySet()) {
			List<String> history = streamHistory.get(component.getKey());
			configuration.put(component.getKey(), history == null ? Collections.<String> emptyList()
					: Collections.unmodifiableList(new ArrayList<String>(history.subList(0,
							component.getValue().intValue()))));
		}
		return new BranchPoint(branch, last.id, matched, Collections.unmodifiableMap(configuration), stop);
	}

	/**
	 * @return the branch the new branch starts from
	 */
	public String getBranch() {
		return branch;
	}

	/**
	 * @return the commit the new branch starts at
	 */
	public String getCommitId() {
		return commitId;
	}

	/**
	 * @return how many change sets the new branch shares with the branch it starts from
	 */
	public int getChangeSets() {
		return changeSets;
	}

	/**
	 * @return component UUID -> the change sets (in stream order) the target workspace must contain to match the
	 *         commit; components the existing branch did not start with are not listed
	 */
	public Map<String, List<String>> getConfiguration() {
		return configuration;
	}

	/**
	 * @return why the shared history ends there, <code>null</code> if it is the whole branch
	 */
	public String getStop() {
		return stop;
	}
}
