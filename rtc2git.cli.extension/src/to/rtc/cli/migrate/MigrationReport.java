package to.rtc.cli.migrate;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import to.rtc.cli.migrate.util.JsonWriter;

/**
 * Machine readable record of one migration run (written as JSON).
 */
public final class MigrationReport {
	private final Map<String, Object> root = new LinkedHashMap<String, Object>();
	private final List<Object> changeSets = new ArrayList<Object>();
	private final List<Object> tags = new ArrayList<Object>();
	private final List<Object> discarded = new ArrayList<Object>();
	private final List<Object> warnings = new ArrayList<Object>();
	private int migrated;
	private int skipped;

	public MigrationReport(String sourceWorkspace, String targetWorkspace) {
		root.put("tool", "rtc2gitcli migrate-to-git");
		root.put("started", timestamp(System.currentTimeMillis()));
		root.put("sourceWorkspace", sourceWorkspace);
		root.put("targetWorkspace", targetWorkspace);
	}

	public synchronized void changeSetMigrated(ChangeSet changeSet, String component, String tag, String commitId) {
		migrated++;
		changeSets.add(changeSetEntry("migrated", changeSet, component, tag, commitId));
	}

	public synchronized void changeSetSkipped(ChangeSet changeSet, String component, String tag) {
		skipped++;
		changeSets.add(changeSetEntry("alreadyMigrated", changeSet, component, tag, null));
	}

	public synchronized void changeSetDiscarded(String uuid) {
		discarded.add(uuid);
	}

	public synchronized void tagged(Tag tag) {
		Map<String, Object> entry = new LinkedHashMap<String, Object>();
		entry.put("name", tag.getName());
		entry.put("originalName", tag.getOriginalName());
		entry.put("kind", tag.getSnapshotUuid() != null ? "snapshot" : "baseline");
		if (tag.getSnapshotUuid() != null) {
			entry.put("snapshot", tag.getSnapshotUuid());
		}
		entry.put("baselines", new ArrayList<Object>(tag.getBaselineUuids()));
		tags.add(entry);
	}

	public synchronized void warning(String message) {
		warnings.add(message);
	}

	public synchronized void finished(Throwable failure) {
		root.put("finished", timestamp(System.currentTimeMillis()));
		root.put("status", failure == null ? "succeeded" : "failed");
		if (failure != null) {
			root.put("error", String.valueOf(failure.getMessage() != null ? failure.getMessage() : failure));
		}
	}

	private static Map<String, Object> changeSetEntry(String status, ChangeSet changeSet, String component,
			String tag, String commitId) {
		Map<String, Object> entry = new LinkedHashMap<String, Object>();
		entry.put("uuid", changeSet.getUuid());
		entry.put("status", status);
		entry.put("component", component);
		entry.put("tag", tag);
		entry.put("author", changeSet.getCreatorName());
		entry.put("created", timestamp(changeSet.getCreationDate()));
		entry.put("comment", changeSet.getComment());
		if (commitId != null) {
			entry.put("commit", commitId);
		}
		return entry;
	}

	/**
	 * Writes the report into <code>&lt;sandbox&gt;/.git/rtc2git/report-&lt;time&gt;.json</code> (not part of the
	 * migrated history).
	 *
	 * @return the written file
	 */
	public synchronized File write(File sandboxDirectory) throws IOException {
		File directory = new File(sandboxDirectory, ".git/rtc2git");
		directory.mkdirs();
		SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss");
		File file = new File(directory, "report-" + format.format(new Date()) + ".json");
		Map<String, Object> summary = new LinkedHashMap<String, Object>();
		summary.put("migrated", Integer.valueOf(migrated));
		summary.put("alreadyMigrated", Integer.valueOf(skipped));
		summary.put("discardedOnResume", Integer.valueOf(discarded.size()));
		summary.put("tags", Integer.valueOf(tags.size()));
		summary.put("warnings", Integer.valueOf(warnings.size()));
		Map<String, Object> document = new LinkedHashMap<String, Object>(root);
		document.put("summary", summary);
		document.put("changeSets", changeSets);
		document.put("tags", tags);
		document.put("discardedOnResume", discarded);
		document.put("warnings", warnings);
		JsonWriter.write(file, document);
		return file;
	}

	static String timestamp(long millis) {
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		format.setTimeZone(TimeZone.getTimeZone("UTC"));
		return format.format(new Date(millis));
	}
}
