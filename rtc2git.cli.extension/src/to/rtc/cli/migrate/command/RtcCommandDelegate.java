package to.rtc.cli.migrate.command;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import com.ibm.team.filesystem.cli.core.AbstractSubcommand;
import com.ibm.team.filesystem.cli.core.subcommands.IScmClientConfiguration;
import com.ibm.team.filesystem.rcp.core.internal.changelog.IChangeLogOutput;
import com.ibm.team.rtc.cli.infrastructure.internal.core.CLIClientException;
import com.ibm.team.rtc.cli.infrastructure.internal.core.ClientConfiguration;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.CLIParser;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.ICommandLine;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.Options;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.exceptions.ConflictingOptionException;

/**
 * Runs an <code>scm</code> sub-command in-process. The sub-command reads its arguments from the shared client
 * configuration, so they are swapped in for the duration of the call and the original command line is restored
 * afterwards.
 */
@SuppressWarnings("restriction")
public abstract class RtcCommandDelegate {
	protected final IScmClientConfiguration config;
	protected final IChangeLogOutput output;
	private final String description;
	private final List<String> args;

	protected RtcCommandDelegate(IScmClientConfiguration config, IChangeLogOutput output, RtcConnection connection,
			String description, List<String> commandArgs) {
		this.config = config;
		this.output = output;
		this.description = description;
		this.args = new ArrayList<String>(connection.getArguments());
		this.args.addAll(commandArgs);
	}

	public int run() throws CLIClientException {
		long start = System.currentTimeMillis();
		ICommandLine original = config.getSubcommandCommandLine();
		setSubCommandLine(parse(args));
		try {
			return getCommand().run(config);
		} finally {
			setSubCommandLine(original);
			output.writeLine(
					"DelegateCommand [" + this + "] finished in [" + (System.currentTimeMillis() - start) + "]ms");
		}
	}

	abstract AbstractSubcommand getCommand();

	abstract Options getOptions() throws ConflictingOptionException;

	private ICommandLine parse(List<String> commandArgs) {
		try {
			return new CLIParser(getOptions(), commandArgs).parse();
		} catch (Exception e) {
			throw new RuntimeException("Unable to build the command line for " + this, e);
		}
	}

	private void setSubCommandLine(ICommandLine commandLine) {
		try {
			Field subargs = ClientConfiguration.class.getDeclaredField("subargs");
			subargs.setAccessible(true);
			subargs.set(config, commandLine);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public String toString() {
		// the arguments may contain credentials
		return description;
	}
}
