package to.rtc.cli.migrate;

import java.util.ArrayList;
import java.util.List;

/**
 * @author florian.buehlmann
 */
final class RtcChangeSet implements ChangeSet {
	private final String uuid;
	private final List<WorkItem> workItems;

	private long creationDate;
	private String entryName;
	private String creatorName;
	private String emailAddress;
	private String component;
	private String componentUuid;
	private String creatorUserId;
	private int historyIndex = -1;
	private long lastChangeDate;

	RtcChangeSet(String changeSetUuid) {
		uuid = changeSetUuid;
		workItems = new ArrayList<WorkItem>();
	}

	RtcChangeSet addWorkItem(long workItem, String workItemText) {
		workItems.add(new RtcWorkItem(workItem, workItemText));
		return this;
	}

	RtcChangeSet setText(String entryName) {
		this.entryName = entryName;
		return this;
	}

	RtcChangeSet setCreatorName(String creatorName) {
		this.creatorName = creatorName;
		return this;
	}

	RtcChangeSet setCreatorEMail(String emailAddress) {
		this.emailAddress = emailAddress;
		return this;
	}

	RtcChangeSet setCreationDate(long creationDate) {
		this.creationDate = creationDate;
		return this;
	}

	RtcChangeSet setComponent(String component) {
		this.component = component;
		return this;
	}

	RtcChangeSet setComponentUuid(String componentUuid) {
		this.componentUuid = componentUuid;
		return this;
	}

	RtcChangeSet setCreatorUserId(String creatorUserId) {
		this.creatorUserId = creatorUserId;
		return this;
	}

	/**
	 * Key that identifies the component: its UUID, or the name when the UUID is unknown.
	 */
	String getComponentKey() {
		return componentUuid != null ? componentUuid : component;
	}

	/**
	 * @param historyIndex
	 *            position of the change set in its component's history (delivery order), oldest = 0
	 */
	RtcChangeSet setHistoryIndex(int historyIndex) {
		this.historyIndex = historyIndex;
		return this;
	}

	RtcChangeSet setLastChangeDate(long lastChangeDate) {
		this.lastChangeDate = lastChangeDate;
		return this;
	}

	@Override
	public long getLastChangeDate() {
		return lastChangeDate > 0 ? lastChangeDate : creationDate;
	}

	int getHistoryIndex() {
		return historyIndex;
	}

	String getComponentUuid() {
		return componentUuid;
	}

	@Override
	public String getUuid() {
		return uuid;
	}

	String getComponent() {
		return component;
	}

	@Override
	public String getComment() {
		return entryName;
	}

	@Override
	public String getCreatorName() {
		return creatorName;
	}

	@Override
	public String getCreatorUserId() {
		return creatorUserId;
	}

	@Override
	public String getEmailAddress() {
		return emailAddress;
	}

	@Override
	public long getCreationDate() {
		return creationDate;
	}

	@Override
	public List<WorkItem> getWorkItems() {
		return workItems;
	}
}
