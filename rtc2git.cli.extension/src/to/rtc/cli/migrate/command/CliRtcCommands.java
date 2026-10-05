package to.rtc.cli.migrate.command;

import com.ibm.team.filesystem.cli.core.subcommands.IScmClientConfiguration;
import com.ibm.team.filesystem.rcp.core.internal.changelog.IChangeLogOutput;
import com.ibm.team.rtc.cli.infrastructure.internal.core.CLIClientException;

/**
 * {@link RtcCommands} backed by in-process <code>scm accept</code> and <code>scm load</code>.
 */
@SuppressWarnings("restriction")
public class CliRtcCommands implements RtcCommands {
	private final IScmClientConfiguration config;
	private final IChangeLogOutput output;
	private final RtcConnection connection;
	private final String workspace;

	public CliRtcCommands(IScmClientConfiguration config, IChangeLogOutput output, RtcConnection connection,
			String workspace) {
		this.config = config;
		this.output = output;
		this.connection = connection;
		this.workspace = workspace;
	}

	@Override
	public String getWorkspace() {
		return workspace;
	}

	@Override
	public int accept(String changeSetUuid, boolean acceptMissingChangeSets) throws CLIClientException {
		return new AcceptCommandDelegate(config, output, connection, workspace, changeSetUuid,
				acceptMissingChangeSets).run();
	}

	@Override
	public int discard(String changeSetUuid) throws CLIClientException {
		return new DiscardCommandDelegate(config, output, connection, workspace, changeSetUuid).run();
	}

	@Override
	public int load(String component, boolean force) throws CLIClientException {
		return new LoadCommandDelegate(config, output, connection, workspace, component, force).run();
	}
}
