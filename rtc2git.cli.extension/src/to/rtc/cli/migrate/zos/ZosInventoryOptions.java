package to.rtc.cli.migrate.zos;

import com.ibm.team.filesystem.cli.core.util.SubcommandUtil;
import com.ibm.team.rtc.cli.infrastructure.internal.core.IOptionSource;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.IOptionKey;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.NamedOptionDefinition;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.OptionKey;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.Options;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.PositionalOptionDefinition;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.exceptions.ConflictingOptionException;

@SuppressWarnings("restriction")
public class ZosInventoryOptions implements IOptionSource {
	public static final IOptionKey OPT_SELECTOR = new OptionKey("workspace"); //$NON-NLS-1$
	public static final IOptionKey OPT_OUTPUT = new OptionKey("output"); //$NON-NLS-1$
	public static final IOptionKey OPT_COMPONENTS = new OptionKey("components"); //$NON-NLS-1$

	@Override
	public Options getOptions() throws ConflictingOptionException {
		Options options = new Options(false);
		SubcommandUtil.addRepoLocationToOptions(options);
		options.addOption(new PositionalOptionDefinition(OPT_SELECTOR, "workspace", 1, -1), //$NON-NLS-1$
				"Streams or repository workspaces to inventory (name, alias or UUID); several are reported"
						+ " together, with components named <stream>/<component>.");
		options.addOption(new NamedOptionDefinition(OPT_OUTPUT, "o", "output", 1),
				"Write the full inventory (every file and folder) as JSON into this file.");
		options.addOption(new NamedOptionDefinition(OPT_COMPONENTS, "C", "components", -1),
				"Only these components (names); default all.");
		return options;
	}
}
