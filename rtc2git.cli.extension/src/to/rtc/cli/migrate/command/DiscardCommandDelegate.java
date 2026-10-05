package to.rtc.cli.migrate.command;

import java.util.Arrays;

import com.ibm.team.filesystem.cli.client.internal.changeset.ChangesetDiscardCmd;
import com.ibm.team.filesystem.cli.client.internal.changeset.ChangesetDiscardCmdOpts;
import com.ibm.team.filesystem.cli.core.AbstractSubcommand;
import com.ibm.team.filesystem.cli.core.subcommands.IScmClientConfiguration;
import com.ibm.team.filesystem.rcp.core.internal.changelog.IChangeLogOutput;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.Options;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.exceptions.ConflictingOptionException;

/**
 * <code>scm discard</code> of a change set from the target workspace; local files are restored as well.
 */
@SuppressWarnings("restriction")
public class DiscardCommandDelegate extends RtcCommandDelegate {

	public DiscardCommandDelegate(IScmClientConfiguration config, IChangeLogOutput output, RtcConnection connection,
			String workspace, String changeSetUuid) {
		super(config, output, connection, "discard " + workspace + " " + changeSetUuid,
				Arrays.asList("-o", "-w", workspace, changeSetUuid));
	}

	@Override
	AbstractSubcommand getCommand() {
		return new ChangesetDiscardCmd();
	}

	@Override
	Options getOptions() throws ConflictingOptionException {
		return new ChangesetDiscardCmdOpts().getOptions();
	}
}
