package to.rtc.cli.migrate.command;

import com.ibm.team.rtc.cli.infrastructure.internal.core.CLIClientException;

/**
 * The <code>scm</code> operations the migration performs on the target workspace. Each returns the <code>scm</code>
 * status code (see <code>com.ibm.team.filesystem.cli.core.Constants</code>).
 */
@SuppressWarnings("restriction")
public interface RtcCommands {

	/**
	 * @return the name of the target workspace the commands work on
	 */
	String getWorkspace();

	int accept(String changeSetUuid, boolean acceptMissingChangeSets) throws CLIClientException;

	/**
	 * @param component
	 *            component UUID or name, or <code>null</code> for the whole workspace
	 */
	int load(String component, boolean force) throws CLIClientException;
}
