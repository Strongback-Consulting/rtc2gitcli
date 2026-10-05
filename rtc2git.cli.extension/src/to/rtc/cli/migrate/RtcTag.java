package to.rtc.cli.migrate;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class RtcTag implements Tag {

	/**
    *
    */
	private static final long TIME_DIFFERENCE_PLUS_MINUS_MILLISECONDS = TimeUnit.SECONDS.toMillis(90);
	private static final RtcChangeSet EARLYEST_CHANGESET = new RtcChangeSet("").setCreationDate(Long.MAX_VALUE);
	private String uuid;
	// all baselines (one per component) grouped into this tag
	private final Set<String> baselineUuids;
	private String originalName;
	private boolean makeNameUnique;
	private long creationDate;
	private final Map<String, List<RtcChangeSet>> components;
	private long totalChangeSetCount;
	private boolean doCreateTag;
	private boolean containLastChangeset;

	RtcTag(String uuid) {
		this.uuid = uuid;
		baselineUuids = new LinkedHashSet<String>();
		addBaselineUuid(uuid);
		components = new HashMap<String, List<RtcChangeSet>>();
		totalChangeSetCount = 0;
		makeNameUnique = false;
		doCreateTag = true;
		containLastChangeset = false;
	}

	RtcTag setCreationDate(long creationDate) {
		this.creationDate = creationDate;
		return this;
	}

	RtcTag setOriginalName(String originalName) {
		this.originalName = originalName;
		return this;
	}

	String getUuid() {
		return uuid;
	}

	RtcTag setUuid(String uuid) {
		this.uuid = uuid;
		addBaselineUuid(uuid);
		return this;
	}

	RtcTag addBaselineUuid(String baselineUuid) {
		if (baselineUuid != null) {
			baselineUuids.add(baselineUuid);
		}
		return this;
	}

	RtcTag addBaselineUuids(RtcTag other) {
		baselineUuids.addAll(other.baselineUuids);
		return this;
	}

	@Override
	public Collection<String> getBaselineUuids() {
		return Collections.unmodifiableSet(baselineUuids);
	}

	boolean hasBaseline(String baselineUuid) {
		return baselineUuids.contains(baselineUuid);
	}

	/**
	 * Baselines of different components that share a name and were created within
	 * {@link #TIME_DIFFERENCE_PLUS_MINUS_MILLISECONDS} of each other belong to the same tag.
	 */
	boolean matches(String otherOriginalName, long otherCreationDate) {
		return originalName.equals(otherOriginalName)
				&& Math.abs(otherCreationDate - creationDate) <= TIME_DIFFERENCE_PLUS_MINUS_MILLISECONDS;
	}

	void add(RtcChangeSet changeSet) {
		List<RtcChangeSet> changesets = null;
		String component = changeSet.getComponentKey();
		if (components.containsKey(component)) {
			changesets = components.get(component);
		} else {
			changesets = new ArrayList<RtcChangeSet>();
			components.put(component, changesets);
		}
		changesets.add(changeSet);
		totalChangeSetCount++;
	}

	Map<String, List<RtcChangeSet>> getComponentsChangeSets() {
		return components;
	}

	List<RtcChangeSet> getOrderedChangeSets() {
		List<RtcChangeSet> changeSets = new ArrayList<RtcChangeSet>();
		Map<String, AtomicInteger> changeSetOrderIndex = new HashMap<String, AtomicInteger>();

		for (Entry<String, List<RtcChangeSet>> entry : components.entrySet()) {
			changeSetOrderIndex.put(entry.getKey(), new AtomicInteger(0));
			sortByHistory(entry.getValue());
		}

		while (changeSets.size() < totalChangeSetCount) {
			changeSets.add(getLatestChangeSet(changeSetOrderIndex));
		}
		return changeSets;
	}

	/**
	 * Within a component, change sets must be accepted in the order they were delivered, which can differ from the
	 * order the change log lists them in (creation date).
	 */
	private static void sortByHistory(List<RtcChangeSet> changeSets) {
		for (RtcChangeSet changeSet : changeSets) {
			if (changeSet.getHistoryIndex() < 0) {
				return; // order unknown, keep the change log order
			}
		}
		Collections.sort(changeSets, new Comparator<RtcChangeSet>() {
			@Override
			public int compare(RtcChangeSet a, RtcChangeSet b) {
				return Integer.compare(a.getHistoryIndex(), b.getHistoryIndex());
			}
		});
	}

	private RtcChangeSet getLatestChangeSet(Map<String, AtomicInteger> changeSetOrderIndex) {
		RtcChangeSet earlyestChangeSet = EARLYEST_CHANGESET;
		for (Entry<String, List<RtcChangeSet>> entry : components.entrySet()) {
			AtomicInteger index = changeSetOrderIndex.get(entry.getKey());
			List<RtcChangeSet> changeSets = entry.getValue();
			int changeSetIndex = index.get();
			if (changeSetIndex < changeSets.size()) {
				RtcChangeSet changeSet = changeSets.get(changeSetIndex);
				if (earlyestChangeSet.getCreationDate() > changeSet.getCreationDate()) {
					earlyestChangeSet = changeSet;
				}
			}
		}
		changeSetOrderIndex.get(earlyestChangeSet.getComponentKey()).incrementAndGet();
		return earlyestChangeSet;
	}

	@Override
	public String getName() {
		if (makeNameUnique) {
			SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd-HHmmss");
			return originalName + "_" + dateFormat.format(new Date(creationDate));
		} else {
			return originalName;
		}
	}

	@Override
	public long getCreationDate() {
		return creationDate;
	}

	public boolean isEmpty() {
		return totalChangeSetCount <= 0;
	}

	RtcTag setMakeNameUnique(boolean makeNameUnique) {
		this.makeNameUnique = makeNameUnique;
		return this;
	}

	boolean isMakeNameUnique() {
		return makeNameUnique;
	}

	boolean isContainingLastChangeset() {
		return containLastChangeset;
	}

	@Override
	public String toString() {
		return (new StringBuilder(getName())).append('@').append(new Date(creationDate)).toString();
	}

	@Override
	public String getOriginalName() {
		return originalName;
	}

	public void addAll(Map<String, List<RtcChangeSet>> componentsChangeSets) {
		for (Entry<String, List<RtcChangeSet>> changesetList : componentsChangeSets.entrySet()) {
			for (RtcChangeSet changeset : changesetList.getValue()) {
				add(changeset);
			}
		}
	}

	RtcTag setDoCreateTag(boolean doCreateTag) {
		this.doCreateTag = doCreateTag;
		return this;
	}

	boolean doCreateTag() {
		return doCreateTag;
	}

	RtcTag setContainLastChangeset(boolean containLastChangeset) {
		this.containLastChangeset = containLastChangeset;
		return this;
	}
}
