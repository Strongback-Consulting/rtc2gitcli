package to.rtc.cli.migrate.git;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.eclipse.jgit.lib.PersonIdent;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import to.rtc.cli.migrate.ChangeSet;
import to.rtc.cli.migrate.util.Files;

public class IdentityResolverTest {
	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();

	private final PersonIdent defaultIdent = new PersonIdent("RTC 2 git", "rtc2git@rtc.to");
	private final Properties props = new Properties();

	@Test
	public void testUsesEwmNameAndEmail() {
		PersonIdent ident = resolver().resolve(changeSet("jdoe", "Jane Doe", "jane@example.com"));

		assertEquals("Jane Doe", ident.getName());
		assertEquals("jane@example.com", ident.getEmailAddress());
		assertEquals(1500000000000L, ident.getWhenAsInstant().toEpochMilli());
	}

	@Test
	public void testMissingEmailFallsBackToDomain() {
		props.setProperty("user.email.domain", "example.com");

		PersonIdent ident = resolver().resolve(changeSet("jdoe", "Jane Doe", null));

		assertEquals("jdoe@example.com", ident.getEmailAddress());
	}

	@Test
	public void testMissingEmailWithoutDomainUsesDefault() {
		PersonIdent ident = resolver().resolve(changeSet("jdoe", "Jane Doe", ""));

		assertEquals("rtc2git@rtc.to", ident.getEmailAddress());
	}

	@Test
	public void testMissingNameUsesUserId() {
		PersonIdent ident = resolver().resolve(changeSet("jdoe", null, "jane@example.com"));

		assertEquals("jdoe", ident.getName());
	}

	@Test
	public void testMappingByUserIdOrName() throws Exception {
		File mapping = tempFolder.newFile("users.txt");
		Files.writeLines(mapping, Arrays.asList("# comment", "JDOE = Jane Q. Doe <jane.doe@corp.example>",
				"Old Account = Someone Else <someone@corp.example>"), StandardCharsets.UTF_8, false);
		props.setProperty("user.mapping.file", mapping.getPath());

		assertEquals("jane.doe@corp.example",
				resolver().resolve(changeSet("jdoe", "Jane Doe", "x@y")).getEmailAddress());
		assertEquals("Someone Else", resolver().resolve(changeSet(null, "old account", null)).getName());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testMissingMappingFileFails() {
		props.setProperty("user.mapping.file", new File(tempFolder.getRoot(), "missing.txt").getPath());
		resolver();
	}

	@Test
	public void testTimeZone() {
		props.setProperty("commit.timezone", "Europe/Zurich");

		PersonIdent ident = resolver().resolve(changeSet("jdoe", "Jane Doe", "jane@example.com"));

		assertEquals(ZoneId.of("Europe/Zurich"), ident.getZoneId());
	}

	private IdentityResolver resolver() {
		return new IdentityResolver(props, defaultIdent, StandardCharsets.UTF_8);
	}

	private static ChangeSet changeSet(final String userId, final String name, final String email) {
		return new ChangeSet() {
			@Override
			public String getCreatorUserId() {
				return userId;
			}

			@Override
			public String getComment() {
				return "";
			}

			@Override
			public String getCreatorName() {
				return name;
			}

			@Override
			public String getEmailAddress() {
				return email;
			}

			@Override
			public long getCreationDate() {
				return 1500000000000L;
			}

			@Override
			public List<WorkItem> getWorkItems() {
				return Collections.emptyList();
			}
		};
	}
}
