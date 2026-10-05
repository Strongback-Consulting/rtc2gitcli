package to.rtc.cli.migrate.git;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.lib.PersonIdent;

import to.rtc.cli.migrate.ChangeSet;
import to.rtc.cli.migrate.util.Files;

/**
 * Turns the creator of an EWM change set into a git identity.
 * <p>
 * Lookup order: the <code>user.mapping.file</code> (keyed by EWM user ID or full name), then the EWM name and email
 * address. A missing email address becomes <code>&lt;userId&gt;@&lt;user.email.domain&gt;</code> when that property is
 * set, otherwise the default <code>user.email</code>. Commit times use the <code>commit.timezone</code> zone (default:
 * the system zone).
 */
final class IdentityResolver {
	// key = Name <email>
	private static final Pattern MAPPING_LINE = Pattern.compile("^(.+?)\\s*=\\s*(.*?)\\s*<([^>]*)>\\s*$");

	private final PersonIdent defaultIdent;
	private final String emailDomain;
	private final ZoneId zoneId;
	private final Map<String, PersonIdent> mapping;

	IdentityResolver(Properties props, PersonIdent defaultIdent, Charset charset) {
		this.defaultIdent = defaultIdent;
		this.emailDomain = trimToNull(props.getProperty("user.email.domain"));
		String zone = trimToNull(props.getProperty("commit.timezone"));
		this.zoneId = zone == null ? ZoneId.systemDefault() : ZoneId.of(zone);
		this.mapping = new HashMap<String, PersonIdent>();
		String mappingFile = trimToNull(props.getProperty("user.mapping.file"));
		if (mappingFile != null) {
			loadMapping(new File(mappingFile), charset);
		}
	}

	ZoneId getZoneId() {
		return zoneId;
	}

	/**
	 * @return the author, dated with the creation of the change set
	 */
	PersonIdent resolve(ChangeSet changeSet) {
		return resolve(changeSet, changeSet.getCreationDate());
	}

	/**
	 * @return the same person, dated with the completion of the change set
	 */
	PersonIdent resolveCommitter(ChangeSet changeSet) {
		return resolve(changeSet, Math.max(changeSet.getCreationDate(), changeSet.getLastChangeDate()));
	}

	private PersonIdent resolve(ChangeSet changeSet, long time) {
		Instant when = Instant.ofEpochMilli(time);
		PersonIdent mapped = lookup(changeSet.getCreatorUserId());
		if (mapped == null) {
			mapped = lookup(changeSet.getCreatorName());
		}
		if (mapped != null) {
			return new PersonIdent(mapped, when, zoneId);
		}
		String name = firstNonEmpty(changeSet.getCreatorName(), changeSet.getCreatorUserId(), defaultIdent.getName());
		String email = trimToNull(changeSet.getEmailAddress());
		if (email == null) {
			String userId = trimToNull(changeSet.getCreatorUserId());
			email = (userId != null && emailDomain != null) ? userId + "@" + emailDomain
					: defaultIdent.getEmailAddress();
		}
		return new PersonIdent(name, email, when, zoneId);
	}

	private PersonIdent lookup(String key) {
		key = trimToNull(key);
		return key == null ? null : mapping.get(key.toLowerCase(Locale.ROOT));
	}

	private void loadMapping(File file, Charset charset) {
		if (!file.isFile()) {
			throw new IllegalArgumentException("User mapping file not found: " + file);
		}
		try {
			for (String line : Files.readLines(file, charset)) {
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				Matcher matcher = MAPPING_LINE.matcher(line);
				if (!matcher.matches()) {
					throw new IllegalArgumentException("Invalid line in " + file + ": " + line);
				}
				mapping.put(matcher.group(1).toLowerCase(Locale.ROOT),
						new PersonIdent(matcher.group(2), matcher.group(3)));
			}
		} catch (IOException e) {
			throw new RuntimeException("Unable to read user mapping file " + file, e);
		}
	}

	private static String firstNonEmpty(String... values) {
		for (String value : values) {
			if (trimToNull(value) != null) {
				return value.trim();
			}
		}
		return null;
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		value = value.trim();
		return value.isEmpty() ? null : value;
	}
}
