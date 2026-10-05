package to.rtc.cli.migrate.ewm;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.ibm.team.filesystem.rcp.core.internal.changelog.IChangeLogOutput;
import com.ibm.team.repository.client.IItemManager;
import com.ibm.team.repository.client.ITeamRepository;
import com.ibm.team.repository.common.TeamRepositoryException;
import com.ibm.team.scm.client.IBaselineConnection;
import com.ibm.team.scm.client.IFlowNodeConnection.IComponentOpFactory;
import com.ibm.team.scm.client.IWorkspaceConnection;
import com.ibm.team.scm.client.IWorkspaceManager;
import com.ibm.team.scm.client.SCMPlatform;
import com.ibm.team.scm.common.IComponent;
import com.ibm.team.scm.common.IFlowTable;
import com.ibm.team.scm.common.IWorkspaceHandle;
import com.ibm.team.scm.common.dto.IWorkspaceSearchCriteria;

/**
 * Creates the two repository workspaces a migration needs, from a stream:
 * <ul>
 * <li>the source workspace with the stream's components, flowing with the stream</li>
 * <li>the target workspace with every component at its initial baseline, flowing with the source workspace</li>
 * </ul>
 */
@SuppressWarnings("restriction")
public class WorkspaceProvisioner {
	private final ITeamRepository repository;
	private final IWorkspaceManager workspaceManager;
	private final IChangeLogOutput output;
	private final IProgressMonitor monitor = new NullProgressMonitor();

	public WorkspaceProvisioner(ITeamRepository repository, IChangeLogOutput output) {
		this.repository = repository;
		this.workspaceManager = SCMPlatform.getWorkspaceManager(repository);
		this.output = output;
	}

	/**
	 * @return <code>true</code> if the workspaces were created, <code>false</code> if both exist already
	 */
	public boolean ensureWorkspaces(String streamName, String sourceName, String targetName)
			throws TeamRepositoryException {
		IWorkspaceHandle source = find(sourceName, IWorkspaceSearchCriteria.WORKSPACES);
		IWorkspaceHandle target = find(targetName, IWorkspaceSearchCriteria.WORKSPACES);
		if (source != null && target != null) {
			output.writeLine("Workspaces [" + sourceName + "] and [" + targetName + "] exist, using them");
			return false;
		}
		if (source != null || target != null) {
			throw new IllegalStateException("Only one of the workspaces [" + sourceName + "] and [" + targetName
					+ "] exists; create both with --stream or neither");
		}
		IWorkspaceHandle stream = find(streamName, IWorkspaceSearchCriteria.STREAMS);
		if (stream == null) {
			throw new IllegalStateException("Stream [" + streamName + "] not found");
		}
		IWorkspaceConnection streamConnection = workspaceManager.getWorkspaceConnection(stream, monitor);
		String description = "rtc2git migration of stream " + streamName;

		IWorkspaceConnection sourceConnection = workspaceManager.createWorkspace(
				repository.loggedInContributor(), sourceName, description + " (source)", streamConnection,
				streamConnection, monitor);
		output.writeLine("Created workspace [" + sourceName + "] from stream [" + streamName + "]");

		IWorkspaceConnection targetConnection = workspaceManager.createWorkspace(
				repository.loggedInContributor(), targetName, description + " (target)", monitor);
		IFlowTable flows = targetConnection.getFlowTable().getWorkingCopy();
		flows.addAcceptFlow(sourceConnection.getResolvedWorkspace(), repository.getId(),
				repository.getRepositoryURI(), null, null);
		flows.addDeliverFlow(sourceConnection.getResolvedWorkspace(), repository.getId(),
				repository.getRepositoryURI(), null, null);
		flows.setDefault(flows.getAcceptFlow(sourceConnection.getResolvedWorkspace()));
		flows.setCurrent(flows.getAcceptFlow(sourceConnection.getResolvedWorkspace()));
		targetConnection.setFlowTable(flows, monitor);

		List<?> components = repository.itemManager().fetchCompleteItems(streamConnection.getComponents(),
				IItemManager.DEFAULT, monitor);
		IComponentOpFactory operations = targetConnection.componentOpFactory();
		List<Object> additions = new ArrayList<Object>();
		for (Object object : components) {
			IComponent component = (IComponent) object;
			IBaselineConnection initial = workspaceManager.getBaselineConnection(component.getInitialBaseline(),
					monitor);
			additions.add(operations.addComponent(component, initial, false));
			output.writeLine("  component [" + component.getName() + "] at its initial baseline");
		}
		targetConnection.applyComponentOperations(additions, monitor);
		output.writeLine("Created workspace [" + targetName + "] flowing with [" + sourceName + "]");
		return true;
	}

	private IWorkspaceHandle find(String name, int kind) throws TeamRepositoryException {
		IWorkspaceSearchCriteria criteria = IWorkspaceSearchCriteria.FACTORY.newInstance().setKind(kind);
		criteria.setExactName(name);
		List<IWorkspaceHandle> found = workspaceManager.findWorkspaces(criteria, 2, monitor);
		if (found.size() > 1) {
			throw new IllegalStateException("More than one " + (kind == IWorkspaceSearchCriteria.STREAMS
					? "stream" : "workspace") + " is named [" + name + "]");
		}
		return found.isEmpty() ? null : found.get(0);
	}
}
