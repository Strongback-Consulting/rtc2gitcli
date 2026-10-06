package to.rtc.cli.migrate.git;

import java.io.File;
import java.io.IOException;
import java.util.Set;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;

/**
 * Working tree iterator that treats the given paths (and everything below them) as not ignored, so that
 * {@link org.eclipse.jgit.api.AddCommand} adds them like <code>git add --force</code>.
 */
final class ForceAddTreeIterator extends FileTreeIterator {
	private final Set<String> forcedPaths;

	ForceAddTreeIterator(Repository repository, Set<String> forcedPaths) {
		super(repository);
		this.forcedPaths = forcedPaths;
	}

	private ForceAddTreeIterator(ForceAddTreeIterator parent, File root) {
		super(parent, root, parent.fs, parent.fileModeStrategy);
		this.forcedPaths = parent.forcedPaths;
	}

	@Override
	public boolean isEntryIgnored() throws IOException {
		return !isForced(getEntryPathString()) && super.isEntryIgnored();
	}

	@Override
	protected AbstractTreeIterator enterSubtree() {
		return new ForceAddTreeIterator(this, ((FileEntry) current()).getFile());
	}

	private boolean isForced(String path) {
		for (String forced : forcedPaths) {
			if (path.equals(forced) || path.startsWith(forced + "/") || forced.startsWith(path + "/")) {
				return true;
			}
		}
		return false;
	}
}
