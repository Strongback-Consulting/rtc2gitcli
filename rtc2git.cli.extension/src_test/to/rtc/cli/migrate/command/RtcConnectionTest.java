package to.rtc.cli.migrate.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.Arrays;

import org.junit.Test;

import com.ibm.team.filesystem.cli.core.subcommands.CommonOptions;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.CLIParser;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.ICommandLine;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.NamedOptionDefinition;
import com.ibm.team.rtc.cli.infrastructure.internal.parser.Options;

@SuppressWarnings("restriction")
public class RtcConnectionTest {

	private static final RtcConnection.PasswordLookup NO_PASSWORD = new RtcConnection.PasswordLookup() {
		@Override
		public String getPassword() {
			throw new AssertionError("password must not be looked up");
		}
	};

	@Test
	public void testUriUserAndPassword() throws Exception {
		RtcConnection connection = RtcConnection.from(parse("-r", "https://ewm/ccm", "-u", "bob", "-P", "secret"),
				NO_PASSWORD);

		assertEquals(Arrays.asList("--repository-uri", "https://ewm/ccm", "--username", "bob", "--password",
				"secret"), connection.getArguments());
		assertFalse(connection.toString().contains("secret"));
	}

	@Test
	public void testNicknameOnlyUsesCachedLogin() throws Exception {
		RtcConnection connection = RtcConnection.from(parse("-r", "myrepo"), NO_PASSWORD);

		assertEquals(Arrays.asList("--repository-uri", "myrepo"), connection.getArguments());
	}

	@Test
	public void testPasswordFileAndCertificateArePassedThrough() throws Exception {
		assertEquals(Arrays.asList("--repository-uri", "u", "--username", "bob", "--password-file", "/pw"),
				RtcConnection.from(parse("-r", "u", "-u", "bob", "--password-file", "/pw"), NO_PASSWORD)
						.getArguments());
		assertEquals(Arrays.asList("--repository-uri", "u", "--password", "pin", "--certificate", "/cert.p12"),
				RtcConnection.from(parse("-r", "u", "--certificate", "/cert.p12", "-P", "pin"), NO_PASSWORD)
						.getArguments());
	}

	@Test
	public void testFlagOptionsHaveNoValue() throws Exception {
		assertEquals(Arrays.asList("--repository-uri", "u", "--kerberos"),
				RtcConnection.from(parse("-r", "u", "--kerberos"), NO_PASSWORD).getArguments());
	}

	@Test
	public void testInteractivePasswordIsLookedUp() throws Exception {
		RtcConnection connection = RtcConnection.from(parse("-r", "u", "-u", "bob"),
				new RtcConnection.PasswordLookup() {
					@Override
					public String getPassword() {
						return "typed";
					}
				});

		assertEquals(Arrays.asList("--repository-uri", "u", "--username", "bob", "--password", "typed"),
				connection.getArguments());
	}

	// the repository options migrate-to-git gets from SubcommandUtil.addRepoLocationToOptions, which needs OSGi
	private static ICommandLine parse(String... options) throws Exception {
		Options defs = new Options(false);
		for (NamedOptionDefinition option : Arrays.asList(CommonOptions.OPT_URI, CommonOptions.OPT_USERNAME,
				CommonOptions.OPT_PASSWORD, CommonOptions.OPT_PASSWORD_FILE, CommonOptions.OPT_CERTIFICATE_FILE,
				CommonOptions.OPT_SMART_CARD, CommonOptions.OPT_KERBEROS, CommonOptions.OPT_INTEGRATED_WINDOWS)) {
			defs.addOption(option, "");
		}
		return new CLIParser(defs, new ArrayList<String>(Arrays.asList(options))).parse();
	}
}
