package to.rtc.cli.migrate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ibm.team.filesystem.common.internal.rest.client.changelog.ChangeLogBaselineEntryDTO;
import com.ibm.team.filesystem.common.internal.rest.client.changelog.ChangeLogChangeSetEntryDTO;
import com.ibm.team.filesystem.common.internal.rest.client.changelog.ChangeLogComponentEntryDTO;
import com.ibm.team.filesystem.common.internal.rest.client.changelog.ChangeLogEntryDTO;
import com.ibm.team.filesystem.common.internal.rest.client.changelog.ChangeLogWorkItemEntryDTO;
import com.ibm.team.filesystem.rcp.core.internal.changelog.BaseChangeLogEntryVisitor;
import com.ibm.team.filesystem.rcp.core.internal.changelog.IChangeLogOutput;

public class HistoryEntryVisitor extends BaseChangeLogEntryVisitor {

	private final RtcTagList tags;
	private String component;
	private String componentUuid;
	// change set UUID -> position in its component's history (delivery order)
	private final Map<String, Integer> historyIndex;
	// UUIDs of the newest change set of each component
	private final Set<String> lastChangeSets;

	/**
	 * @param history
	 *            component UUID -> change set UUIDs of the source workspace, oldest first
	 */
	public HistoryEntryVisitor(RtcTagList tagList, Map<String, List<String>> history, IChangeLogOutput out) {
		this.tags = tagList;
		setOutput(out);
		this.historyIndex = new HashMap<String, Integer>();
		this.lastChangeSets = new HashSet<String>();
		for (List<String> changeSets : history.values()) {
			for (int i = 0; i < changeSets.size(); i++) {
				historyIndex.put(changeSets.get(i), Integer.valueOf(i));
			}
			if (!changeSets.isEmpty()) {
				lastChangeSets.add(changeSets.get(changeSets.size() - 1));
			}
		}
	}

	public void acceptInto(ChangeLogEntryDTO root) {
		if (!enter(root)) {
			return;
		}
		for (Iterator<?> iterator = root.getChildEntries().iterator(); iterator.hasNext();) {
			ChangeLogEntryDTO child = (ChangeLogEntryDTO) iterator.next();
			visitChild(root, child);
			acceptInto(child);
		}

		exit(root);
	}

	@Override
	protected void visitChangeSet(ChangeLogEntryDTO parent, ChangeLogChangeSetEntryDTO changeSetDto) {
		String changeSetUuid = changeSetDto.getItemId();
		RtcChangeSet changeSet = new RtcChangeSet(changeSetUuid).setText(changeSetDto.getEntryName())
				.setCreatorName(changeSetDto.getCreator().getFullName())
				.setCreatorEMail(changeSetDto.getCreator().getEmailAddress())
				.setCreationDate(changeSetDto.getCreationDate()).setComponent(component)
				.setComponentUuid(componentUuid).setCreatorUserId(changeSetDto.getCreator().getUserId());
		@SuppressWarnings("unchecked")
		List<ChangeLogWorkItemEntryDTO> workItems = changeSetDto.getWorkItems();
		if (workItems != null && !workItems.isEmpty()) {
			for (ChangeLogWorkItemEntryDTO workItem : workItems) {
				changeSet.addWorkItem(workItem.getWorkItemNumber(), workItem.getEntryName());
			}
		}
		Integer index = historyIndex.get(changeSetUuid);
		if (index != null) {
			changeSet.setHistoryIndex(index.intValue());
		}
		RtcTag actualTag = getActualTag(parent);
		actualTag.add(changeSet);
		if (lastChangeSets.contains(changeSetUuid)) {
			actualTag.setContainLastChangeset(true);
		}
	}

	private RtcTag getActualTag(ChangeLogEntryDTO parent) {
		if (parent instanceof ChangeLogBaselineEntryDTO) {
			final ChangeLogBaselineEntryDTO dto = (ChangeLogBaselineEntryDTO) parent;
			return tags.getTag(dto.getItemId(), dto.getEntryName(), dto.getCreationDate());
		} else {
			return tags.getHeadTag();
		}
	}

	@Override
	protected void visitComponent(ChangeLogEntryDTO parent, ChangeLogComponentEntryDTO dto) {
		component = dto.getEntryName();
		componentUuid = dto.getItemId();
	}
}
