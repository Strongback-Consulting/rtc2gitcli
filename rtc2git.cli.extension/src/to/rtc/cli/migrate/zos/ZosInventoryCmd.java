package to.rtc.cli.migrate.zos;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.NullProgressMonitor;

import to.rtc.cli.migrate.FileProperties;
import to.rtc.cli.migrate.ewm.EwmChangeSetDetails;
import to.rtc.cli.migrate.util.JsonWriter;

import com.ibm.team.filesystem.cli.client.AbstractSubcommand;
import com.ibm.team.filesystem.cli.core.internal.ScmCommandLineArgument;
import com.ibm.team.filesystem.cli.core.util.RepoUtil;
import com.ibm.team.filesystem.cli.core.util.RepoUtil.ItemType;
import com.ibm.team.filesystem.cli.core.util.SubcommandUtil;
import com.ibm.team.filesystem.client.FileSystemException;
import com.ibm.team.filesystem.client.rest.IFilesystemRestClient;
import com.ibm.team.filesystem.rcp.core.internal.changelog.ChangeLogStreamOutput;
import com.ibm.team.repository.client.IItemManager;
import com.ibm.team.repository.client.ITeamRepository;
import com.ibm.team.repository.common.TeamRepositoryException;
import com.ibm.team.rtc.cli.infrastructure.internal.core.ISubcommand;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.ICommandLine;
import com.ibm.team.scm.client.SCMPlatform;
import com.ibm.team.scm.common.IComponent;
import com.ibm.team.scm.common.IWorkspace;

/**
 * <code>scm migrate-inventory &lt;stream&gt;</code>: read-only z/OS inventory of a stream or workspace (files, folders,
 * EWM properties, language and data set definitions). Shows what a migration will carry over before it is run.
 */
@SuppressWarnings("restriction")
public class ZosInventoryCmd extends AbstractSubcommand implements ISubcommand {

	@Override
	public void run() throws FileSystemException {
		ChangeLogStreamOutput output = new ChangeLogStreamOutput(config.getContext().stdout());
		ICommandLine subargs = config.getSubcommandCommandLine();
		ScmCommandLineArgument selector = ScmCommandLineArgument
				.create(subargs.getOptionValue(ZosInventoryOptions.OPT_SELECTOR), config);
		SubcommandUtil.validateArgument(selector, ItemType.WORKSPACE);
		IFilesystemRestClient client = SubcommandUtil.setupDaemon(config);
		ITeamRepository repository = RepoUtil.loginUrlArgAncestor(config, client, selector);
		IWorkspace workspace = RepoUtil.getWorkspace(selector.getItemSelector(), true, true, repository, config);
		Set<String> only = new HashSet<String>();
		if (subargs.hasOption(ZosInventoryOptions.OPT_COMPONENTS)) {
			only.addAll(subargs.getOptions(ZosInventoryOptions.OPT_COMPONENTS));
		}

		try {
			EwmChangeSetDetails reader = new EwmChangeSetDetails(repository, workspace);
			Map<String, List<FileProperties>> components = new LinkedHashMap<String, List<FileProperties>>();
			List<?> handles = SCMPlatform.getWorkspaceManager(repository)
					.getWorkspaceConnection(workspace, new NullProgressMonitor()).getComponents();
			List<?> items = repository.itemManager().fetchCompleteItems(handles, IItemManager.DEFAULT,
					new NullProgressMonitor());
			for (Object item : items) {
				IComponent component = (IComponent) item;
				if (only.isEmpty() || only.contains(component.getName())) {
					output.writeLine("Reading component [" + component.getName() + "]");
					components.put(component.getName(), reader.readComponent(component));
				}
			}
			ZosInventory inventory = new ZosInventory(components, SystemDefinitions.create(repository));
			Map<String, Object> document = new LinkedHashMap<String, Object>();
			document.put("tool", "rtc2gitcli migrate-inventory");
			document.put("selector", selector.getStringValue());
			document.put("workspace", workspace.getName());
			document.putAll(inventory.toMap());
			printSummary(output, document);
			if (subargs.hasOption(ZosInventoryOptions.OPT_OUTPUT)) {
				File file = new File(subargs.getOption(ZosInventoryOptions.OPT_OUTPUT));
				JsonWriter.write(file, document);
				output.writeLine("Inventory written to " + file.getAbsolutePath());
			}
		} catch (TeamRepositoryException | IOException e) {
			throw new RuntimeException(e);
		}
	}

	@SuppressWarnings("unchecked")
	private static void printSummary(ChangeLogStreamOutput output, Map<String, Object> document) {
		output.writeLine("System definitions: " + document.get("systemDefinitions"));
		Map<String, Object> summary = (Map<String, Object>) document.get("summary");
		for (Map.Entry<String, Object> entry : summary.entrySet()) {
			if (entry.getValue() instanceof Map) {
				output.writeLine(entry.getKey() + ":");
				for (Map.Entry<String, Object> value : ((Map<String, Object>) entry.getValue()).entrySet()) {
					output.writeLine("  " + value.getKey() + ": " + value.getValue());
				}
			} else {
				output.writeLine(entry.getKey() + ": " + entry.getValue());
			}
		}
	}
}
