package to.rtc.cli.migrate.command;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IStatus;

import com.ibm.team.filesystem.cli.client.internal.subcommands.AcceptCmd;
import com.ibm.team.filesystem.cli.client.internal.subcommands.AcceptCmdOptions;
import com.ibm.team.filesystem.cli.core.AbstractSubcommand;
import com.ibm.team.filesystem.cli.core.subcommands.IScmClientConfiguration;
import com.ibm.team.filesystem.rcp.core.internal.changelog.IChangeLogOutput;
import com.ibm.team.rtc.cli.infrastructure.internal.core.CLIClientException;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.Options;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.exceptions.ConflictingOptionException;

/**
 * <code>scm accept</code> of a single change set into the target workspace. Failures reported through a
 * {@link CLIClientException} with a status are returned as that status code, so the caller decides how to react.
 */
@SuppressWarnings("restriction")
public class AcceptCommandDelegate extends RtcCommandDelegate {

	public AcceptCommandDelegate(IScmClientConfiguration config, IChangeLogOutput output, RtcConnection connection,
			String targetWorkspace, String changeSetUuid, boolean acceptMissingChangesets) {
		super(config, output, connection, "accept " + targetWorkspace + " " + changeSetUuid
				+ (acceptMissingChangesets ? " --accept-missing-changesets" : ""),
				arguments(targetWorkspace, changeSetUuid, acceptMissingChangesets));
	}

	@Override
	public int run() throws CLIClientException {
		try {
			return super.run();
		} catch (CLIClientException e) {
			IStatus status = e.getStatus();
			if (status == null) {
				throw e;
			}
			output.writeLine("Accept ended with status [" + status.getCode() + "] (" + status.getMessage() + ")");
			return status.getCode();
		}
	}

	@Override
	AbstractSubcommand getCommand() {
		return new AcceptCmd();
	}

	@Override
	Options getOptions() throws ConflictingOptionException {
		return new AcceptCmdOptions().getOptions();
	}

	private static List<String> arguments(String targetWorkspace, String changeSetUuid,
			boolean acceptMissingChangesets) {
		List<String> args = new ArrayList<String>();
		args.add("-o");
		args.add("--no-merge");
		if (acceptMissingChangesets) {
			args.add("--accept-missing-changesets");
		}
		args.add("--no-local-refresh");
		args.add("-t");
		args.add(targetWorkspace);
		args.add("--changes");
		args.add(changeSetUuid);
		return args;
	}
}
