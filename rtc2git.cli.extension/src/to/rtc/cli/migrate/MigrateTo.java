package to.rtc.cli.migrate;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import to.rtc.cli.migrate.command.CliRtcCommands;
import to.rtc.cli.migrate.command.RtcCommands;
import to.rtc.cli.migrate.command.RtcConnection;
import to.rtc.cli.migrate.ewm.EwmChangeSetDetails;
import to.rtc.cli.migrate.ewm.WorkspaceProvisioner;
import to.rtc.cli.migrate.zos.SystemDefinitions;

import com.ibm.team.filesystem.cli.client.AbstractSubcommand;
import com.ibm.team.filesystem.cli.core.internal.ScmCommandLineArgument;
import com.ibm.team.filesystem.cli.core.Constants;
import com.ibm.team.filesystem.cli.core.subcommands.CommonOptions;
import com.ibm.team.filesystem.cli.core.util.RepoUtil;
import com.ibm.team.filesystem.cli.core.util.RepoUtil.ItemType;
import com.ibm.team.filesystem.cli.core.util.SubcommandUtil;
import com.ibm.team.filesystem.client.FileSystemException;
import com.ibm.team.filesystem.client.internal.PathLocation;
import com.ibm.team.filesystem.client.internal.snapshot.FlowType;
import com.ibm.team.filesystem.client.internal.snapshot.SnapshotId;
import com.ibm.team.filesystem.client.internal.snapshot.SnapshotSyncReport;
import com.ibm.team.filesystem.client.rest.IFilesystemRestClient;
import com.ibm.team.filesystem.client.rest.parameters.ParmsGetBaselines;
import com.ibm.team.filesystem.common.changemodel.IPathResolver;
import com.ibm.team.filesystem.common.internal.rest.client.changelog.ChangeLogEntryDTO;
import com.ibm.team.filesystem.common.internal.rest.client.core.BaselineDTO;
import com.ibm.team.filesystem.common.internal.rest.client.sync.BaselineHistoryEntryDTO;
import com.ibm.team.filesystem.common.internal.rest.client.sync.GetBaselinesDTO;
import com.ibm.team.filesystem.rcp.core.internal.changelog.ChangeLogCustomizer;
import com.ibm.team.filesystem.rcp.core.internal.changelog.ChangeLogStreamOutput;
import com.ibm.team.filesystem.rcp.core.internal.changelog.GenerateChangeLogOperation;
import com.ibm.team.filesystem.rcp.core.internal.changelog.IChangeLogOutput;
import com.ibm.team.filesystem.rcp.core.internal.changes.model.CopyFileAreaPathResolver;
import com.ibm.team.filesystem.rcp.core.internal.changes.model.FallbackPathResolver;
import com.ibm.team.filesystem.rcp.core.internal.changes.model.SnapshotPathResolver;
import com.ibm.team.repository.client.IItemManager;
import com.ibm.team.repository.client.ITeamRepository;
import com.ibm.team.repository.common.TeamRepositoryException;
import com.ibm.team.rtc.cli.infrastructure.internal.core.CLIClientException;
import com.ibm.team.rtc.cli.infrastructure.internal.core.ISubcommand;
import com.ibm.team.rtc.cli.infrastructure.internal.core.LocalContext;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.ICommandLine;
import com.ibm.team.scm.client.IWorkspaceConnection;
import com.ibm.team.scm.client.IWorkspaceManager;
import com.ibm.team.scm.client.SCMPlatform;
import com.ibm.team.scm.client.IChangeHistory;
import com.ibm.team.scm.common.IChangeHistoryEntryChange;
import com.ibm.team.scm.common.IBaselineHandle;
import com.ibm.team.scm.common.IBaselineSet;
import com.ibm.team.scm.common.IChangeSetHandle;
import com.ibm.team.scm.common.dto.IBaselineSetSearchCriteria;
import com.ibm.team.scm.common.IComponent;
import com.ibm.team.scm.common.IComponentHandle;
import com.ibm.team.scm.common.IWorkspace;
import com.ibm.team.scm.common.IWorkspaceHandle;

@SuppressWarnings("restriction")
public abstract class MigrateTo extends AbstractSubcommand implements ISubcommand {

	private StreamOutput output;
	private boolean listTagsOnly = false;
	private MigrationReport report;
	private File reportDirectory;

	private IProgressMonitor getMonitor() {
		return new LogTaskMonitor(new StreamOutput(config.getContext().stdout()));
	}

	public abstract Migrator getMigrator();

	public abstract Pattern getBaselineIncludePattern();

	/**
	 * @return whether a gap is resolved by accepting the missing change sets together with the requested one
	 */
	protected boolean isAcceptMissingChangeSets() {
		return false;
	}

	@Override
	public void run() throws FileSystemException {
		boolean isUpdateMigration = false;
		long start = System.currentTimeMillis();
		setStdOut();
		output = new StreamOutput(config.getContext().stdout());

		try {
			// Consume the command-line
			ICommandLine subargs = config.getSubcommandCommandLine();
			// before any sub-command replaces the command line of the shared configuration
			RtcConnection connection = RtcConnection.from(config);

			int timeout = 900;
			if (subargs.hasOption(MigrateToOptions.OPT_RTC_CONNECTION_TIMEOUT)) {
				String timeoutOptionValue = subargs.getOptionValue(MigrateToOptions.OPT_RTC_CONNECTION_TIMEOUT)
						.getValue();
				timeout = Integer.parseInt(timeoutOptionValue);
			}

			if (subargs.hasOption(MigrateToOptions.OPT_RTC_LIST_TAGS_ONLY)) {
				listTagsOnly = true;
				output.writeLine("***** LIST ONLY THE TAGS *****");
			}

			if (subargs.hasOption(MigrateToOptions.OPT_RTC_IS_UPDATE_MIGRATION)) {
				isUpdateMigration = true;
				output.writeLine("***** IS UPDATE MIGRATION *****");
			}

			final ScmCommandLineArgument sourceWsOption = ScmCommandLineArgument.create(
					subargs.getOptionValue(MigrateToOptions.OPT_SRC_WS), config);
			SubcommandUtil.validateArgument(sourceWsOption, ItemType.WORKSPACE);
			final ScmCommandLineArgument destinationWsOption = ScmCommandLineArgument.create(
					subargs.getOptionValue(MigrateToOptions.OPT_DEST_WS), config);
			SubcommandUtil.validateArgument(destinationWsOption, ItemType.WORKSPACE);

			final File sandboxDirectory;
			if (subargs.hasOption(CommonOptions.OPT_DIRECTORY)) {
				sandboxDirectory = new File(subargs.getOption(CommonOptions.OPT_DIRECTORY));
			} else {
				sandboxDirectory = new File(System.getProperty("user.dir"));
			}
			boolean provision = subargs.hasOption(MigrateToOptions.OPT_STREAM);
			if (provision && !subargs.hasOption(CommonOptions.OPT_DIRECTORY)
					&& SubcommandUtil.findAncestorCFARoot(sandboxDirectory.getAbsolutePath()) == null) {
				// outside a sandbox, scm runs every sub-command in a scratch area: the load of the target would
				// register the new sandbox with a second daemon of this process and fail
				throw new IllegalStateException("--stream needs the sandbox directory: pass -d <existing empty"
						+ " directory>, or run inside a sandbox the target workspace is loaded in");
			}

			// Initialize connection to RTC
			output.writeLine("Initialize RTC connection with connection timeout of " + timeout + "s");
			IFilesystemRestClient client = SubcommandUtil.setupDaemon(config);
			ITeamRepository repo = RepoUtil.loginUrlArgAncestor(config, client, destinationWsOption);
			repo.setConnectionTimeout(timeout);

			if (provision) {
				new WorkspaceProvisioner(repo, output).ensureWorkspaces(
						subargs.getOption(MigrateToOptions.OPT_STREAM), subargs.getOption(MigrateToOptions.OPT_SRC_WS),
						subargs.getOption(MigrateToOptions.OPT_DEST_WS));
			}

			IWorkspace sourceWs = RepoUtil.getWorkspace(sourceWsOption.getItemSelector(), true, false, repo, config);
			IWorkspace destinationWs = RepoUtil.getWorkspace(destinationWsOption.getItemSelector(), true, false, repo,
					config);

			Migrator migrator = getMigrator();
			if (!listTagsOnly) {
				report = new MigrationReport(sourceWsOption.getStringValue(), destinationWsOption.getStringValue());
				reportDirectory = sandboxDirectory;
			}
			RtcCommands commands = new CliRtcCommands(config, output, connection,
					destinationWsOption.getStringValue(), sandboxDirectory.getAbsolutePath());
			// also on a rerun after the workspaces were created but the load failed
			if (provision && RepoUtil.getComponentsInSandbox(destinationWs.getItemId().getUuidValue(),
					new PathLocation(sandboxDirectory.getAbsolutePath()), client, config).isEmpty()) {
				if (new File(sandboxDirectory, ".git").exists()) {
					throw new IllegalStateException("The target workspace is not loaded in the sandbox "
							+ sandboxDirectory + " but it contains a git repository; use an empty directory");
				}
				output.writeLine("Load the target workspace into " + sandboxDirectory.getAbsolutePath());
				int loaded = commands.load(null, false);
				if (loaded != Constants.STATUS_OK.intValue()) {
					throw new CLIClientException("Loading the new target workspace failed with status [" + loaded + "]");
				}
			}
			// before the incoming change sets are computed: a change set discarded here must be incoming again
			recoverInterruptedMigration(migrator, commands, sandboxDirectory,
					getChangeSetHistory(repo, destinationWs, "target"));

			output.writeLine("Get full history information from RTC. This could take a large amount of time.");
			output.writeLine("Create the list of baselines");
			RtcTagList tagList = createTagListFromBaselines(client, repo, sourceWs);

			output.writeLine("Get changeset information for all baselines");
			addChangeSetInfo(tagList, repo, sourceWs, destinationWs);

			tagList.printTagList(listTagsOnly);

			output.writeLine("Filter included baselines...");

			// Sorting is required berore pruning if migration from multiple components should be done. Otherwise tags
			// of some code could be wrong.
			tagList.sortByCreationDate();
			tagList.pruneInactiveTags();
			tagList.pruneExcludedTags(getBaselineIncludePattern());

			tagList.printTagList(true);

			List<SnapshotTag> snapshots = readSnapshots(repo, sourceWs);
			Set<String> tagBaselines = new HashSet<String>();
			for (RtcTag tag : tagList) {
				tagBaselines.addAll(tag.getBaselineUuids());
			}
			SnapshotPlacer snapshotPlacer = new SnapshotPlacer(snapshots, tagBaselines);
			for (SnapshotTag snapshot : snapshots) {
				output.writeLine("  Snapshot [" + snapshot.getOriginalName() + "] created at ["
						+ new Date(snapshot.getCreationDate()) + "] will be tagged as [" + snapshot.getName() + "]");
			}

			if (listTagsOnly) {
				// Stop here before migration of any data
				return;
			}

			output.writeLine("Start migration of tags.");
			// file properties of what is already in the sandbox, so that the initial commit gets them too
			migrator.setSystemDefinitions(SystemDefinitions.create(repo));
			ChangeSetDetails details = new EwmChangeSetDetails(repo, destinationWs);
			Collection<FileProperties> initialFiles = details.readAll();
			output.writeLine("Read the properties of " + initialFiles.size() + " files and folders of the target workspace");
			migrator.updateFileProperties(initialFiles);
			migrator.init(sandboxDirectory);

			Map<String, String> destinationWsComponents = RepoUtil.getComponentsInSandbox(
					destinationWs.getItemId().getUuidValue(), new PathLocation(sandboxDirectory.getAbsolutePath()),
					client, config);

			RtcMigrator rtcMigrator = new RtcMigrator(output, commands, migrator, sandboxDirectory,
					destinationWsComponents.keySet(), isAcceptMissingChangeSets());
			rtcMigrator.useChangeSetDetails(details, initialFiles);
			rtcMigrator.setReport(report);
			createSnapshotTags(migrator, snapshotPlacer.reached(Collections.<String> emptySet()));
			boolean isFirstTag = true;
			int numberOfTags = tagList.size();
			int tagCounter = 0;
			for (RtcTag tag : tagList) {
				if (isUpdateMigration && isFirstTag && tag.isEmpty()) {
					output.writeLine("Ignore migration of tag [" + tag.toString() + "] because it is empty.");
					tagCounter++;
					createSnapshotTags(migrator, snapshotPlacer.reached(tag.getBaselineUuids()));
					continue;
				}
				isFirstTag = false;
				final long startTag = System.currentTimeMillis();
				output.writeLine("Start migration of Tag [" + tag.getName() + "] [" + (tagCounter + 1) + "/"
						+ numberOfTags + "]");
				try {
					rtcMigrator.migrateTag(tag);
					tagCounter++;
				} catch (CLIClientException e) {
					e.printStackTrace(output.getOutputStream());
					throw new RuntimeException(e);
				}
				output.writeLine("Migration of tag [" + tag.getName() + "] [" + (tagCounter) + "/" + numberOfTags
						+ "] took [" + (System.currentTimeMillis() - startTag) / 1000 + "] s");
				createSnapshotTags(migrator, snapshotPlacer.reached(tag.getBaselineUuids()));
			}
			for (SnapshotTag snapshot : snapshotPlacer.getWaiting()) {
				warn(snapshot + " was not tagged, not all of its baselines were migrated");
			}
			finishReport(null);
		} catch (Throwable t) {
			finishReport(t);
			t.printStackTrace(output.getOutputStream());
			throw new RuntimeException(t);
		} finally {
			output.writeLine("Migration took [" + (System.currentTimeMillis() - start) / 1000 + "] s");
		}
	}

	private void createSnapshotTags(Migrator migrator, List<SnapshotTag> snapshots) {
		for (SnapshotTag snapshot : snapshots) {
			output.writeLine("Tagging " + snapshot + " as [" + snapshot.getName() + "]");
			migrator.createTag(snapshot);
			if (report != null) {
				report.tagged(snapshot);
			}
		}
	}

	private void warn(String message) {
		output.writeLine("WARNING: " + message);
		if (report != null) {
			report.warning(message);
		}
	}

	/**
	 * Writes the report into the git directory of the sandbox; skipped if there is no repository yet (writing would
	 * create one).
	 */
	private void finishReport(Throwable failure) {
		if (report == null) {
			return;
		}
		MigrationReport finished = report;
		report = null;
		finished.finished(failure);
		if (!new File(reportDirectory, ".git/HEAD").isFile()) {
			return;
		}
		try {
			output.writeLine("Migration report: " + finished.write(reportDirectory));
		} catch (IOException e) {
			output.writeLine("WARNING: unable to write the migration report: " + e);
		}
	}

	/**
	 * @return snapshot tag names are this prefix plus the snapshot name
	 */
	protected String getSnapshotTagPrefix() {
		return "snapshot/";
	}

	/**
	 * @return the snapshots of the stream to tag (by name); by default none
	 */
	protected Pattern getSnapshotIncludePattern() {
		return Pattern.compile("");
	}

	/**
	 * Reads the snapshots owned by the stream the source workspace flows with, oldest first.
	 */
	List<SnapshotTag> readSnapshots(ITeamRepository repo, IWorkspace sourceWs) throws TeamRepositoryException {
		IProgressMonitor monitor = getMonitor();
		IWorkspaceManager workspaceManager = SCMPlatform.getWorkspaceManager(repo);
		IWorkspaceConnection sourceWsConnection = workspaceManager.getWorkspaceConnection(sourceWs, monitor);
		IWorkspaceHandle stream = (IWorkspaceHandle) sourceWsConnection.getFlowTable().getCurrentAcceptFlow()
				.getFlowNode();
		IBaselineSetSearchCriteria criteria = IBaselineSetSearchCriteria.FACTORY.newInstance()
				.setOwnerWorkspaceOptional(stream);
		List<?> handles = workspaceManager.findBaselineSets(criteria, Integer.MAX_VALUE, monitor);
		List<?> sets = repo.itemManager().fetchCompleteItems(handles, IItemManager.DEFAULT, monitor);
		Pattern include = getSnapshotIncludePattern();
		List<SnapshotTag> snapshots = new ArrayList<SnapshotTag>();
		for (Object object : sets) {
			IBaselineSet set = (IBaselineSet) object;
			if (set == null || !include.matcher(set.getName()).matches()) {
				continue;
			}
			List<String> baselines = new ArrayList<String>();
			for (Object baseline : set.getBaselines()) {
				baselines.add(((IBaselineHandle) baseline).getItemId().getUuidValue());
			}
			snapshots.add(new SnapshotTag(set.getItemId().getUuidValue(), set.getName(), getSnapshotTagPrefix(),
					set.getCreationDate().getTime(), baselines));
		}
		Collections.sort(snapshots, new Comparator<SnapshotTag>() {
			@Override
			public int compare(SnapshotTag a, SnapshotTag b) {
				return Long.compare(a.getCreationDate(), b.getCreationDate());
			}
		});
		return snapshots;
	}

	private void setStdOut() {
		Class<?> c = LocalContext.class;
		Field subargs;
		try {
			subargs = c.getDeclaredField("stdout");
			subargs.setAccessible(true);
			subargs.set(config.getContext(), new LoggingPrintStream(config.getContext().stdout()));
		} catch (Exception e) {
			// only adds time stamps to the output; the migration works without it
			config.getContext().stdout().println("WARNING: output without time stamps (" + e + ")");
		}
	}

	/**
	 * @return whether change sets that an interrupted run accepted but did not commit are discarded automatically
	 */
	protected boolean isDiscardPendingChangeSets() {
		return true;
	}

	/**
	 * A run that stopped between accepting a change set and committing it leaves the change set in the target
	 * workspace; it would not be incoming any more and so be missing in git. Such change sets are discarded again.
	 */
	private void recoverInterruptedMigration(Migrator migrator, RtcCommands commands, File sandboxDirectory,
			Map<String, List<String>> targetHistory) throws CLIClientException {
		ResumeState state = migrator.inspectResume(sandboxDirectory);
		if (!state.isResume()) {
			Map<String, String> newest = new HashMap<String, String>();
			for (Map.Entry<String, List<String>> component : targetHistory.entrySet()) {
				List<String> history = component.getValue();
				newest.put(component.getKey(), history.isEmpty() ? "" : history.get(history.size() - 1));
			}
			migrator.setInitialState(newest);
			return;
		}
		ResumeAnalysis analysis = ResumeAnalysis.analyse(targetHistory, state.getMigrated(), state.getBase());
		for (String component : analysis.getUndeterminedComponents()) {
			warn("cannot check component [" + component
					+ "] for change sets accepted by an interrupted run (no commit and no recorded start state)");
		}
		List<String> pending = analysis.getPending();
		if (pending.isEmpty()) {
			output.writeLine("Resuming: the target workspace matches the git history");
			return;
		}
		output.writeLine("Resuming: " + pending.size()
				+ " change set(s) were accepted by an interrupted run but not committed: " + pending);
		if (listTagsOnly) {
			output.writeLine("They would be discarded from the target workspace before migrating (not in list mode)");
			return;
		}
		if (!isDiscardPendingChangeSets()) {
			throw new CLIClientException("Discard these change sets from workspace [" + commands.getWorkspace()
					+ "] with 'scm discard' or set resume.discard.pending=true, then run the migration again");
		}
		for (int i = pending.size() - 1; i >= 0; i--) {
			if (report != null) {
				report.changeSetDiscarded(pending.get(i));
			}
			int result = commands.discard(pending.get(i));
			if (result != Constants.STATUS_OK.intValue()) {
				throw new CLIClientException("Discarding change set [" + pending.get(i) + "] failed with status ["
						+ result + "]");
			}
		}
	}

	/**
	 * Reads the complete change history of every component of the source workspace, oldest change set first. This
	 * is the order in which the change sets were delivered, which can differ from their creation order.
	 *
	 * @return component UUID -> change set UUIDs in delivery order
	 */
	Map<String, List<String>> getChangeSetHistory(ITeamRepository repo, IWorkspace sourceWs, String label)
			throws TeamRepositoryException {
		IWorkspaceManager workspaceManager = SCMPlatform.getWorkspaceManager(repo);
		IItemManager itemManager = repo.itemManager();
		Map<String, List<String>> history = new HashMap<String, List<String>>();
		IProgressMonitor monitor = getMonitor();
		IWorkspaceConnection sourceWsConnection = workspaceManager.getWorkspaceConnection(sourceWs, monitor);
		@SuppressWarnings("unchecked")
		List<IComponentHandle> componentHandles = sourceWsConnection.getComponents();
		@SuppressWarnings("unchecked")
		List<IComponent> components = itemManager.fetchCompleteItems(componentHandles, componentHandles.size(),
				monitor);
		for (IComponent component : components) {
			// recent() lists a page oldest to newest; previousHistory() steps to the next older page
			List<List<String>> pages = new ArrayList<List<String>>();
			for (IChangeHistory page = sourceWsConnection.changeHistory(component); page != null; page = page
					.previousHistory(monitor)) {
				List<String> uuids = new ArrayList<String>();
				for (Object entry : page.recent(monitor)) {
					IChangeSetHandle changeSet = ((IChangeHistoryEntryChange) entry).changeSet();
					uuids.add(changeSet.getItemId().getUuidValue());
				}
				pages.add(0, uuids);
			}
			List<String> ordered = new ArrayList<String>();
			for (List<String> page : pages) {
				ordered.addAll(page);
			}
			history.put(component.getItemId().getUuidValue(), ordered);
			output.writeLine("History of " + label + " component [" + component.getName() + "]: " + ordered.size()
					+ " change sets" + (ordered.isEmpty() ? ""
							: ", oldest [" + ordered.get(0) + "], newest [" + ordered.get(ordered.size() - 1) + "]"));
		}
		return history;
	}

	private RtcTagList createTagListFromBaselines(IFilesystemRestClient client, ITeamRepository repo,
			IWorkspace sourceWs) {
		RtcTagList tagList = new RtcTagList(output);
		try {
			IWorkspaceConnection sourceWsConnection = SCMPlatform.getWorkspaceManager(repo).getWorkspaceConnection(
					sourceWs, getMonitor());

			IWorkspaceHandle sourceStreamHandle = (IWorkspaceHandle) (sourceWsConnection.getFlowTable()
					.getCurrentAcceptFlow().getFlowNode());

			@SuppressWarnings("unchecked")
			List<IComponentHandle> componentHandles = sourceWsConnection.getComponents();

			ParmsGetBaselines parms = new ParmsGetBaselines();
			parms.workspaceItemId = sourceStreamHandle.getItemId().getUuidValue();
			parms.repositoryUrl = repo.getRepositoryURI();
			parms.max = 1000000;

			GetBaselinesDTO result = null;
			for (IComponentHandle component : componentHandles) {
				parms.componentItemId = component.getItemId().getUuidValue();
				result = client.getBaselines(parms, getMonitor());
				for (Object obj : result.getBaselineHistoryEntriesInWorkspace()) {
					BaselineHistoryEntryDTO baselineEntry = (BaselineHistoryEntryDTO) obj;
					BaselineDTO baseline = baselineEntry.getBaseline();
					long creationDate = baseline.getCreationDate();
					RtcTag tag = new RtcTag(baseline.getItemId()).setCreationDate(creationDate).setOriginalName(
							baseline.getName());
					tag = tagList.add(tag);
				}
			}
			// add default tag
			tagList.getHeadTag();
		} catch (TeamRepositoryException e) {
			// an incomplete history must not be migrated
			throw new RuntimeException("Unable to read the history from the repository", e);
		}
		return tagList;
	}

	private void addChangeSetInfo(RtcTagList tagList, ITeamRepository repo, IWorkspace sourceWs,
			IWorkspace destinationWs) {

		SnapshotSyncReport syncReport;
		try {
			IWorkspaceConnection sourceWsConnection = SCMPlatform.getWorkspaceManager(repo).getWorkspaceConnection(
					sourceWs, getMonitor());

			IWorkspaceHandle sourceStreamHandle = (IWorkspaceHandle) (sourceWsConnection.getFlowTable()
					.getCurrentAcceptFlow().getFlowNode());
			SnapshotId sourceSnapshotId = SnapshotId.getSnapshotId(sourceStreamHandle);
			SnapshotId destinationSnapshotId = SnapshotId.getSnapshotId(destinationWs.getItemHandle());

			@SuppressWarnings("unchecked")
			List<IComponentHandle> componentHandles = sourceWsConnection.getComponents();
			syncReport = SnapshotSyncReport.compare(destinationSnapshotId.getSnapshot(null),
					sourceSnapshotId.getSnapshot(null), componentHandles, getMonitor());
			GenerateChangeLogOperation clOp = new GenerateChangeLogOperation();
			ChangeLogCustomizer customizer = new ChangeLogCustomizer();

			customizer.setFlowsToInclude(FlowType.Incoming);
			customizer.setIncludeComponents(true);
			customizer.setIncludeBaselines(true);
			customizer.setIncludeChangeSets(true);
			customizer.setIncludeWorkItems(true);
			customizer.setPruneEmptyDirections(false);
			customizer.setPruneUnchangedComponents(false);

			List<IPathResolver> pathResolvers = new ArrayList<IPathResolver>();
			pathResolvers.add(CopyFileAreaPathResolver.create());
			pathResolvers.add(SnapshotPathResolver.create(destinationSnapshotId));
			pathResolvers.add(SnapshotPathResolver.create(sourceSnapshotId));
			IPathResolver pathResolver = new FallbackPathResolver(pathResolvers, true);
			clOp.setChangeLogRequest(repo, syncReport, pathResolver, customizer);
			output.writeLine("Get list of baselines and changesets form RTC.");
			long startTime = System.currentTimeMillis();
			ChangeLogEntryDTO changelog = clOp.run(getMonitor());
			output.writeLine("Get list of baselines and changesets form RTC took ["
					+ (System.currentTimeMillis() - startTime) / 1000 + "]s.");
			output.writeLine("Parse the list of baselines and changesets.");
			HistoryEntryVisitor visitor = new HistoryEntryVisitor(tagList,
					getChangeSetHistory(repo, sourceWs, "source"),
					new ChangeLogStreamOutput(config.getContext().stdout()));

			startTime = System.currentTimeMillis();
			visitor.acceptInto(changelog);
			output.writeLine("Parse the list of baselines and changesets took ["
					+ (System.currentTimeMillis() - startTime) / 1000 + "]s.");

		} catch (TeamRepositoryException e) {
			// an incomplete history must not be migrated
			throw new RuntimeException("Unable to read the history from the repository", e);
		}
	}

	static class LogTaskMonitor extends NullProgressMonitor {
		private String taskName;
		private int total = -1;
		private int done = 0;
		private final IChangeLogOutput output;

		LogTaskMonitor(IChangeLogOutput output) {
			this.output = output;
		}

		@Override
		public void beginTask(String task, int totalWork) {
			if (task != null && !task.isEmpty()) {
				taskName = task;
				output.writeLine(taskName + " start");
			}
			total = totalWork;
		}

		@Override
		public void subTask(String subTask) {
			output.setIndent(2);
			output.writeLine(subTask + " [" + getPercent() + "%]");
		}

		private int getPercent() {
			if (total <= 0) {
				return -1;
			}
			return done * 100 / total;
		}

		@Override
		public void worked(int workDone) {
			done += workDone;
		}

		@Override
		public void done() {
			taskName = null;
		}
	}
}
