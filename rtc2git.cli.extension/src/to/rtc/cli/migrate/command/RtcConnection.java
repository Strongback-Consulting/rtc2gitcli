package to.rtc.cli.migrate.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.ibm.team.filesystem.cli.core.subcommands.CommonOptions;
import com.ibm.team.filesystem.cli.core.subcommands.IScmClientConfiguration;
import com.ibm.team.filesystem.client.FileSystemException;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.ICommandLine;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.NamedOptionDefinition;

/**
 * Repository and authentication options of the <code>migrate-to-git</code> command line, captured once so the
 * <code>accept</code> and <code>load</code> sub-commands connect the same way the user did (URI or nickname, password,
 * password file, certificate, smart card, Kerberos or integrated Windows authentication).
 */
@SuppressWarnings("restriction")
public final class RtcConnection {

	/** Callback for the password of an interactive login (user name given, no other credentials). */
	public interface PasswordLookup {
		String getPassword() throws FileSystemException;
	}

	private static final List<NamedOptionDefinition> AUTH_OPTIONS = Arrays.asList(CommonOptions.OPT_USERNAME,
			CommonOptions.OPT_PASSWORD, CommonOptions.OPT_PASSWORD_FILE, CommonOptions.OPT_CERTIFICATE_FILE,
			CommonOptions.OPT_SMART_CARD, CommonOptions.OPT_KERBEROS, CommonOptions.OPT_INTEGRATED_WINDOWS);

	private final List<String> args;

	private RtcConnection(List<String> args) {
		this.args = Collections.unmodifiableList(args);
	}

	public static RtcConnection from(final IScmClientConfiguration config) {
		return from(config.getSubcommandCommandLine(), new PasswordLookup() {
			@Override
			public String getPassword() throws FileSystemException {
				return config.getConnectionInfo().getPassword();
			}
		});
	}

	static RtcConnection from(ICommandLine commandLine, PasswordLookup passwordLookup) {
		List<String> args = new ArrayList<String>();
		add(args, commandLine, CommonOptions.OPT_URI);
		boolean hasCredentials = false;
		for (NamedOptionDefinition option : AUTH_OPTIONS) {
			if (add(args, commandLine, option) && option != CommonOptions.OPT_USERNAME) {
				hasCredentials = true;
			}
		}
		if (commandLine.hasOption(CommonOptions.OPT_USERNAME) && !hasCredentials) {
			// password was entered interactively
			try {
				String password = passwordLookup.getPassword();
				if (password != null) {
					args.add("--" + CommonOptions.OPT_PASSWORD.getLongOpt());
					args.add(password);
				}
			} catch (FileSystemException e) {
				throw new RuntimeException("Unable to get password", e);
			}
		}
		return new RtcConnection(args);
	}

	private static boolean add(List<String> args, ICommandLine commandLine, NamedOptionDefinition option) {
		if (!commandLine.hasOption(option)) {
			return false;
		}
		args.add("--" + option.getLongOpt());
		if (option.getArgCount() != 0) {
			args.add(commandLine.getOption(option));
		}
		return true;
	}

	/**
	 * @return the options to put in front of a sub-command's own arguments
	 */
	public List<String> getArguments() {
		return args;
	}

	@Override
	public String toString() {
		// never print credentials
		return "RtcConnection" + args.subList(0, Math.min(2, args.size()));
	}
}
