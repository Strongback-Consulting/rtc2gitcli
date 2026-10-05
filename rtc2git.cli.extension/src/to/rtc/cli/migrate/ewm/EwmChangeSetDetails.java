package to.rtc.cli.migrate.ewm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import to.rtc.cli.migrate.ChangeSetDetails;
import to.rtc.cli.migrate.FileProperties;
import to.rtc.cli.migrate.FileProperties.LineDelimiter;

import com.ibm.team.filesystem.common.FileLineDelimiter;
import com.ibm.team.filesystem.common.IFileContent;
import com.ibm.team.filesystem.common.IFileItem;
import com.ibm.team.repository.client.IItemManager;
import com.ibm.team.repository.client.ITeamRepository;
import com.ibm.team.repository.common.TeamRepositoryException;
import com.ibm.team.repository.common.UUID;
import com.ibm.team.scm.client.IConfiguration;
import com.ibm.team.scm.client.IWorkspaceConnection;
import com.ibm.team.scm.client.SCMPlatform;
import com.ibm.team.scm.common.IChange;
import com.ibm.team.scm.common.IChangeSet;
import com.ibm.team.scm.common.IChangeSetHandle;
import com.ibm.team.scm.common.IComponentHandle;
import com.ibm.team.scm.common.IFolder;
import com.ibm.team.scm.common.IFolderHandle;
import com.ibm.team.scm.common.IVersionable;
import com.ibm.team.scm.common.IVersionableHandle;
import com.ibm.team.scm.common.IWorkspace;
import com.ibm.team.scm.common.dto.IAncestorReport;
import com.ibm.team.scm.common.dto.INameItemPair;

/**
 * {@link ChangeSetDetails} read with the EWM Java API from the target workspace. Paths are those in the target
 * workspace after the change set was accepted, which are the sandbox paths (component root folders are loaded at the
 * sandbox root).
 */
public class EwmChangeSetDetails implements ChangeSetDetails {
	private static final int BATCH = 500;

	private final ITeamRepository repository;
	private final IWorkspaceConnection target;
	private final IProgressMonitor monitor = new NullProgressMonitor();

	public EwmChangeSetDetails(ITeamRepository repository, IWorkspace targetWorkspace) {
		this.repository = repository;
		try {
			this.target = SCMPlatform.getWorkspaceManager(repository).getWorkspaceConnection(targetWorkspace,
					monitor);
		} catch (TeamRepositoryException e) {
			throw new RuntimeException("Unable to connect to the target workspace", e);
		}
	}

	@Override
	public Collection<FileProperties> readAll() {
		try {
			List<FileProperties> all = new ArrayList<FileProperties>();
			for (Object component : target.getComponents()) {
				all.addAll(readComponent((IComponentHandle) component));
			}
			return all;
		} catch (TeamRepositoryException e) {
			throw new RuntimeException("Unable to read the target workspace", e);
		}
	}

	/**
	 * @return the properties of every file and folder of the component in the workspace, with paths relative to the
	 *         component root folder (the sandbox root)
	 */
	public List<FileProperties> readComponent(IComponentHandle component) throws TeamRepositoryException {
		List<FileProperties> all = new ArrayList<FileProperties>();
		IConfiguration configuration = target.configuration(component);
		Map<IVersionableHandle, String> paths = new HashMap<IVersionableHandle, String>();
		collect(configuration, configuration.rootFolderHandle(monitor), "", paths);
		List<IVersionableHandle> handles = new ArrayList<IVersionableHandle>(paths.keySet());
		for (int i = 0; i < handles.size(); i += BATCH) {
			List<IVersionableHandle> batch = handles.subList(i, Math.min(handles.size(), i + BATCH));
			List<?> items = configuration.fetchCompleteItems(batch, monitor);
			for (int j = 0; j < batch.size(); j++) {
				IVersionable item = (IVersionable) items.get(j);
				if (item != null) {
					all.add(toProperties(item, paths.get(batch.get(j))));
				}
			}
		}
		return all;
	}

	private void collect(IConfiguration configuration, IFolderHandle folder, String path,
			Map<IVersionableHandle, String> paths) throws TeamRepositoryException {
		Map<?, ?> children = configuration.childEntries(folder, monitor);
		if (children == null) {
			return;
		}
		for (Map.Entry<?, ?> child : children.entrySet()) {
			String childPath = path.isEmpty() ? (String) child.getKey() : path + "/" + child.getKey();
			IVersionableHandle handle = (IVersionableHandle) child.getValue();
			paths.put(handle, childPath);
			if (handle instanceof IFolderHandle) {
				collect(configuration, (IFolderHandle) handle, childPath, paths);
			}
		}
	}

	@Override
	public Update read(String changeSetUuid) {
		try {
			IItemManager itemManager = repository.itemManager();
			IChangeSetHandle handle = (IChangeSetHandle) IChangeSet.ITEM_TYPE
					.createItemHandle(UUID.valueOf(changeSetUuid), null);
			IChangeSet changeSet = (IChangeSet) itemManager.fetchCompleteItem(handle, IItemManager.DEFAULT, monitor);
			Update update = new Update(
					changeSet.getLastChangeDate() == null ? 0 : changeSet.getLastChangeDate().getTime());
			List<IVersionableHandle> afterStates = new ArrayList<IVersionableHandle>();
			for (Object object : changeSet.changes()) {
				IChange change = (IChange) object;
				if (change.afterState() == null) {
					update.removed(change.item().getItemId().getUuidValue());
				} else {
					afterStates.add(change.afterState());
				}
			}
			if (afterStates.isEmpty()) {
				return update;
			}
			IConfiguration configuration = target.configuration(changeSet.getComponent());
			List<?> states = SCMPlatform.getWorkspaceManager(repository).versionableManager()
					.fetchCompleteStates(afterStates, monitor);
			List<?> ancestors = configuration.locateAncestors(afterStates, monitor);
			for (int i = 0; i < afterStates.size(); i++) {
				String path = toPath((IAncestorReport) ancestors.get(i));
				if (path != null && states.get(i) != null) {
					update.changed(toProperties((IVersionable) states.get(i), path));
				}
			}
			return update;
		} catch (TeamRepositoryException e) {
			throw new RuntimeException("Unable to read change set " + changeSetUuid, e);
		}
	}

	private static String toPath(IAncestorReport report) {
		if (report == null || report.getNameItemPairs().isEmpty()) {
			return null; // no longer in the workspace
		}
		StringBuilder path = new StringBuilder();
		for (Object object : report.getNameItemPairs()) {
			String name = ((INameItemPair) object).getName();
			if (name == null || name.isEmpty()) {
				continue; // component root folder
			}
			if (path.length() > 0) {
				path.append('/');
			}
			path.append(name);
		}
		return path.length() == 0 ? null : path.toString();
	}

	@SuppressWarnings("unchecked")
	private static FileProperties toProperties(IVersionable item, String path) {
		String itemId = item.getItemId().getUuidValue();
		Map<String, String> userProperties = item.getUserProperties();
		if (item instanceof IFolder) {
			return FileProperties.folder(itemId, path, userProperties);
		}
		if (item instanceof IFileItem) {
			IFileItem file = (IFileItem) item;
			IFileContent content = file.getContent();
			return FileProperties.file(itemId, path,
					content == null ? LineDelimiter.NONE : toLineDelimiter(content.getLineDelimiter()),
					file.getContentType(), content == null ? null : content.getCharacterEncoding(),
					file.isExecutable(), userProperties);
		}
		// symbolic links and other versionables: keep bytes as they are
		return FileProperties.file(itemId, path, LineDelimiter.NONE, null, null, false, userProperties);
	}

	static LineDelimiter toLineDelimiter(FileLineDelimiter delimiter) {
		if (delimiter == null) {
			return LineDelimiter.NONE;
		}
		switch (delimiter) {
		case LINE_DELIMITER_LF:
			return LineDelimiter.LF;
		case LINE_DELIMITER_CR:
			return LineDelimiter.CR;
		case LINE_DELIMITER_CRLF:
			return LineDelimiter.CRLF;
		case LINE_DELIMITER_PLATFORM:
			return LineDelimiter.PLATFORM;
		default:
			return LineDelimiter.NONE;
		}
	}
}
