package to.rtc.cli.migrate.command;

import java.util.ArrayList;
import java.util.List;

import com.ibm.team.filesystem.cli.client.internal.subcommands.LoadCmdLauncher;
import com.ibm.team.filesystem.cli.client.internal.subcommands.LoadCmdOptions;
import com.ibm.team.filesystem.cli.core.AbstractSubcommand;
import com.ibm.team.filesystem.cli.core.subcommands.IScmClientConfiguration;
import com.ibm.team.filesystem.rcp.core.internal.changelog.IChangeLogOutput;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.Options;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.exceptions.ConflictingOptionException;

/**
 * <code>scm load</code> of the target workspace, or of one of its components (selected by UUID or name).
 */
@SuppressWarnings("restriction")
public class LoadCommandDelegate extends RtcCommandDelegate {

	public LoadCommandDelegate(IScmClientConfiguration config, IChangeLogOutput output, RtcConnection connection,
			String workspace, String component, boolean force) {
		super(config, output, connection,
				"load " + workspace + (component != null ? " " + component : "") + (force ? " --force" : ""),
				arguments(workspace, component, force));
	}

	@Override
	AbstractSubcommand getCommand() {
		return new LoadCmdLauncher();
	}

	@Override
	Options getOptions() throws ConflictingOptionException {
		return new LoadCmdOptions().getOptions();
	}

	private static List<String> arguments(String workspace, String component, boolean force) {
		List<String> args = new ArrayList<String>();
		if (force) {
			args.add("--force");
		}
		args.add(workspace);
		if (component != null) {
			args.add(component);
		}
		return args;
	}
}
