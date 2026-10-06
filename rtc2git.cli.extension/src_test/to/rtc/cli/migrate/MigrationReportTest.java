package to.rtc.cli.migrate;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MigrationReportTest {
	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();

	@Test
	public void testWritesJsonIntoGitDirectory() throws Exception {
		File sandbox = tempFolder.getRoot();
		MigrationReport report = new MigrationReport("source", "target");
		report.changeSetMigrated(changeSet("cs-1", "line one\nline \"two\"\\"), "Database", "R1", "abc123");
		report.changeSetSkipped(changeSet("cs-0", "old"), "Database", "R1");
		report.changeSetDiscarded("cs-9");
		report.tagged(new SnapshotTag("snap", "R1", "snapshot/", 1L, Arrays.asList("bl-1")));
		report.warning("tab\there");
		report.finished(null);

		File file = report.write(sandbox);

		assertTrue(file.getPath(), file.getParentFile().equals(new File(sandbox, ".git/rtc2git")));
		String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
		assertTrue(json, json.contains("\"comment\": \"line one\\nline \\\"two\\\"\\\\\""));
		assertTrue(json, json.contains("\"commit\": \"abc123\""));
		assertTrue(json, json.contains("\"status\": \"alreadyMigrated\""));
		assertTrue(json, json.contains("\"kind\": \"snapshot\""));
		assertTrue(json, json.contains("\"tab\\there\""));
		assertTrue(json, json.contains("\"migrated\": 1"));
		assertTrue(json, json.contains("\"status\": \"succeeded\""));
	}

	@Test
	public void testFailureIsRecorded() throws Exception {
		MigrationReport report = new MigrationReport("source", "target");
		report.finished(new IllegalStateException("boom"));

		String json = new String(Files.readAllBytes(report.write(tempFolder.getRoot()).toPath()),
				StandardCharsets.UTF_8);

		assertTrue(json, json.contains("\"status\": \"failed\""));
		assertTrue(json, json.contains("\"error\": \"boom\""));
	}

	private static ChangeSet changeSet(final String uuid, final String comment) {
		return new ChangeSet() {
			@Override
			public String getUuid() {
				return uuid;
			}

			@Override
			public String getComment() {
				return comment;
			}

			@Override
			public String getCreatorName() {
				return "Deb";
			}

			@Override
			public String getEmailAddress() {
				return null;
			}

			@Override
			public long getCreationDate() {
				return 0;
			}

			@Override
			public List<WorkItem> getWorkItems() {
				return Collections.emptyList();
			}
		};
	}
}
