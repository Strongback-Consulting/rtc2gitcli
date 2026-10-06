/**
 *
 */
package to.rtc.cli.migrate.git;

import to.rtc.cli.migrate.MigrateToOptions;

import com.ibm.team.rtc.cli.infrastructure.internal.parser.IOptionKey;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.NamedOptionDefinition;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.OptionKey;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.Options;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.exceptions.ConflictingOptionException;

/**
 * @author florian.buehlmann
 *
 */
public class MigrateToGitOptions extends MigrateToOptions {

	public static final IOptionKey OPT_MIGRATION_PROPERTIES = new OptionKey("migrationProperties"); //$NON-NLS-1$
	public static final IOptionKey OPT_BRANCH = new OptionKey("branch"); //$NON-NLS-1$
	public static final IOptionKey OPT_GIT_REPOSITORY = new OptionKey("gitRepository"); //$NON-NLS-1$

	@Override
	public Options getOptions() throws ConflictingOptionException {
		Options options = super.getOptions();

		// rtc2git migration properties
		options.addOption(new NamedOptionDefinition(OPT_MIGRATION_PROPERTIES, "m", "migrationProperties", 1),
				"File with migration properties.");
		options.addOption(new NamedOptionDefinition(OPT_BRANCH, "b", "branch", 1),
				"Git branch of this stream: the initial branch of a new repository, or the new branch with"
						+ " --git-repository. A sandbox with a repository must be on it.");
		options.addOption(new NamedOptionDefinition(OPT_GIT_REPOSITORY, "g", "git-repository", 1),
				"Sandbox (or git directory) of an earlier migration. A new sandbox becomes a linked worktree of its"
						+ " repository, with the --branch starting at the newest commit whose configuration the stream"
						+ " contains; the target workspace is set to that configuration. Needs --stream.");
		return options;
	}

}
