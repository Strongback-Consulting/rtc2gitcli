package to.rtc.cli.migrate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import org.junit.Test;

import to.rtc.cli.migrate.FileProperties.LineDelimiter;

public class FilePropertiesModelTest {
	private static final Map<String, String> NONE = Collections.emptyMap();

	private final FilePropertiesModel model = new FilePropertiesModel();

	@Test
	public void testAddModifyRemove() {
		model.reset(Arrays.asList(file("1", "a.txt", LineDelimiter.LF)));

		assertTrue(model.apply(new ChangeSetDetails.Update(5).changed(file("2", "b.txt", LineDelimiter.NONE))
				.changed(file("1", "a.txt", LineDelimiter.CRLF))));
		assertEquals("{a.txt=CRLF, b.txt=NONE}", paths());

		model.apply(new ChangeSetDetails.Update(6).removed("2"));
		assertEquals("{a.txt=CRLF}", paths());
	}

	@Test
	public void testFolderRenameMovesContent() {
		model.reset(Arrays.asList(FileProperties.folder("d", "old", NONE), file("1", "old/a.txt", LineDelimiter.LF),
				file("2", "old/sub/b.txt", LineDelimiter.LF), file("3", "other/c.txt", LineDelimiter.LF)));

		model.apply(new ChangeSetDetails.Update(5).changed(FileProperties.folder("d", "new", NONE)));

		assertEquals("{new/a.txt=LF, new/sub/b.txt=LF, other/c.txt=LF}", paths());
	}

	@Test
	public void testFolderDeleteRemovesContent() {
		model.reset(Arrays.asList(FileProperties.folder("d", "gone", NONE), file("1", "gone/a.txt", LineDelimiter.LF),
				file("3", "kept.txt", LineDelimiter.LF)));

		model.apply(new ChangeSetDetails.Update(5).removed("d"));

		assertEquals("{kept.txt=LF}", paths());
	}

	@Test
	public void testUnknownRemovalChangesNothing() {
		assertFalse(model.apply(new ChangeSetDetails.Update(5).removed("unknown")));
	}

	private String paths() {
		Map<String, LineDelimiter> files = new TreeMap<String, LineDelimiter>();
		for (FileProperties properties : model.getAll()) {
			if (!properties.isFolder()) {
				files.put(properties.getPath(), properties.getLineDelimiter());
			}
		}
		return files.toString();
	}

	private static FileProperties file(String id, String path, LineDelimiter delimiter) {
		return FileProperties.file(id, path, delimiter, "text/plain", "UTF-8", false, NONE);
	}
}
