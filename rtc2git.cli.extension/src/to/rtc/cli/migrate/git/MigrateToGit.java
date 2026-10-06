package to.rtc.cli.migrate.git;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import to.rtc.cli.migrate.MigrateTo;
import to.rtc.cli.migrate.Migrator;

import com.ibm.team.filesystem.client.FileSystemException;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.ICommandLine;

public class MigrateToGit extends MigrateTo {
	private Migrator migratorImplementation;
	private Pattern baselineIncludeRegexPattern;
	private Properties migrationProperties;

	@Override
	public void run() throws FileSystemException {
		migrationProperties = readProperties(config.getSubcommandCommandLine());
		baselineIncludeRegexPattern = Pattern.compile(migrationProperties.getProperty("rtc.baseline.include", ""));
		GitMigrator gitMigrator = new GitMigrator(migrationProperties);
		ICommandLine subargs = config.getSubcommandCommandLine();
		if (subargs.hasOption(MigrateToGitOptions.OPT_BRANCH)) {
			gitMigrator.setBranch(subargs.getOption(MigrateToGitOptions.OPT_BRANCH));
		}
		if (subargs.hasOption(MigrateToGitOptions.OPT_GIT_REPOSITORY)) {
			if (!subargs.hasOption(MigrateToGitOptions.OPT_BRANCH)) {
				throw new IllegalArgumentException("--git-repository needs the name of the new --branch");
			}
			gitMigrator.setBranchRepository(new File(subargs.getOption(MigrateToGitOptions.OPT_GIT_REPOSITORY)));
		}
		migratorImplementation = gitMigrator;
		try {
			super.run();
		} finally {
			migratorImplementation.close();
		}
	}

	@Override
	public Migrator getMigrator() {
		return migratorImplementation;
	}

	@Override
	public Pattern getBaselineIncludePattern() {
		return baselineIncludeRegexPattern;
	}

	@Override
	protected Pattern getSnapshotIncludePattern() {
		return Pattern.compile(migrationProperties.getProperty("rtc.snapshot.include", ""));
	}

	@Override
	protected String getSnapshotTagPrefix() {
		return migrationProperties.getProperty("rtc.snapshot.tag.prefix", "snapshot/");
	}

	@Override
	protected boolean isDiscardPendingChangeSets() {
		return Boolean.parseBoolean(migrationProperties.getProperty("resume.discard.pending", "true"));
	}

	@Override
	protected boolean isAcceptMissingChangeSets() {
		return Boolean.parseBoolean(migrationProperties.getProperty("rtc.accept.missing.changesets", "false"));
	}

	private Properties readProperties(ICommandLine subargs) {
		final Properties props = new Properties();
		if (subargs.hasOption(MigrateToGitOptions.OPT_MIGRATION_PROPERTIES)) {
			try {
				FileInputStream in = new FileInputStream(
						subargs.getOption(MigrateToGitOptions.OPT_MIGRATION_PROPERTIES));
				try {
					props.load(in);
				} finally {
					in.close();
				}
			} catch (IOException e) {
				throw new RuntimeException("Unable to read migration properties", e);
			}
		}
		return trimProperties(props);
	}

	protected static Properties trimProperties(Properties props) {
		Set<Object> keyset = props.keySet();
		for (Object keyObject : keyset) {
			String key = (String) keyObject;
			props.setProperty(key, props.getProperty(key).trim());
		}
		return props;
	}
}
