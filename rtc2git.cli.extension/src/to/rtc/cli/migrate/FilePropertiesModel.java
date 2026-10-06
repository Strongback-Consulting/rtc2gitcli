package to.rtc.cli.migrate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The properties of all items in the sandbox, kept up to date change set by change set.
 */
public final class FilePropertiesModel {
	private final Map<String, FileProperties> byItemId = new HashMap<String, FileProperties>();

	public void reset(Collection<FileProperties> all) {
		byItemId.clear();
		for (FileProperties properties : all) {
			byItemId.put(properties.getItemId(), properties);
		}
	}

	/**
	 * @return whether anything changed
	 */
	public boolean apply(ChangeSetDetails.Update update) {
		boolean changed = false;
		for (String removed : update.getRemovedItemIds()) {
			FileProperties old = byItemId.remove(removed);
			if (old != null) {
				changed = true;
				if (old.isFolder()) {
					removeBelow(old.getPath());
				}
			}
		}
		for (FileProperties properties : update.getChanged()) {
			FileProperties old = byItemId.put(properties.getItemId(), properties);
			changed = true;
			if (old != null && old.isFolder() && !old.getPath().equals(properties.getPath())) {
				// a moved or renamed folder moves everything below it
				moveBelow(old.getPath(), properties.getPath());
			}
		}
		return changed;
	}

	public Collection<FileProperties> getAll() {
		return Collections.unmodifiableCollection(byItemId.values());
	}

	private void moveBelow(String oldPath, String newPath) {
		String prefix = oldPath + "/";
		List<FileProperties> moved = new ArrayList<FileProperties>();
		for (FileProperties properties : byItemId.values()) {
			if (properties.getPath().startsWith(prefix)) {
				moved.add(properties.withPath(newPath + "/" + properties.getPath().substring(prefix.length())));
			}
		}
		for (FileProperties properties : moved) {
			byItemId.put(properties.getItemId(), properties);
		}
	}

	private void removeBelow(String path) {
		String prefix = path + "/";
		for (Iterator<FileProperties> it = byItemId.values().iterator(); it.hasNext();) {
			if (it.next().getPath().startsWith(prefix)) {
				it.remove();
			}
		}
	}
}
