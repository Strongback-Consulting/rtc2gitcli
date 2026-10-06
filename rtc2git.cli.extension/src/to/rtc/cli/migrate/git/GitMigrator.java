package to.rtc.cli.migrate.git;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Formatter;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.HashMap;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.EmptyCommitException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.WindowCacheConfig;

import to.rtc.cli.migrate.ChangeSet;
import to.rtc.cli.migrate.ChangeSet.WorkItem;
import to.rtc.cli.migrate.FileProperties;
import to.rtc.cli.migrate.Migrator;
import to.rtc.cli.migrate.ResumeState;
import to.rtc.cli.migrate.Tag;
import to.rtc.cli.migrate.util.CommitCommentTranslator;
import to.rtc.cli.migrate.util.Files;
import to.rtc.cli.migrate.util.JazzignoreTranslator;
import to.rtc.cli.migrate.util.JsonWriter;
import to.rtc.cli.migrate.zos.SystemDefinitions;
import to.rtc.cli.migrate.zos.ZosMetadata;

/**
 * Git implementation of a {@link Migrator}.
 *
 * @author otmar.humbel
 * @author patrick.reinhart
 */
public final class GitMigrator implements Migrator {
	private static final String GIT_CONFIG_PREFIX = "git.config.";
	static final List<String> ROOT_IGNORED_ENTRIES = Arrays.asList("/.jazz5", "/.jazzShed", "/.metadata");
	static final Pattern GITIGNORE_PATTERN = Pattern.compile("(^.*(/|))\\.gitignore$");
	static final Pattern JAZZIGNORE_PATTERN = Pattern.compile("(^.*(/|))\\.jazzignore$");
	static final String CHANGE_SET_TRAILER = "EWM-ChangeSet";
	static final String BASELINE_TRAILER = "EWM-Baseline";
	static final String BASE_TRAILER = "EWM-Base";
	static final String SNAPSHOT_TRAILER = "EWM-Snapshot";
	static final Pattern VALUE_PATTERN = Pattern.compile("^([0-9]+) *(m|mb|k|kb|)$", Pattern.CASE_INSENSITIVE);

	private Charset defaultCharset;
	private final Set<String> ignoredFileExtensions;
	private final WindowCacheConfig WindowCacheConfig;

	private int commitsAfterClean;
	private Git git;
	private Properties properties;
	private PersonIdent defaultIdent;
	private File rootDir;
	private CommitCommentTranslator commentTranslator;
	private IdentityResolver identities;
	private boolean commitEmptyChangeSets;
	private boolean changeSetTrailer;
	private boolean forceAddIgnored;
	private List<FastIgnoreRule> intentionalIgnores;
	private final Set<String> migratedChangeSets;
	// files rewritten while handling removals that must be added, not removed (the root .gitignore)
	private final Set<String> pendingAdds = new HashSet<String>();
	private boolean attributesFromEwm;
	private GitattributesGenerator attributesGenerator;
	private Collection<FileProperties> propertiesBeforeInit;
	private final Set<String> executablePaths = new TreeSet<String>();
	private final Map<String, String> initialState = new TreeMap<String, String>();
	private String lastCommitId;
	static final String KEEP_FILE = ".gitkeep";
	private boolean keepEmptyFolders;
	private String zosCodePage;
	private boolean zosMetadata;
	private SystemDefinitions systemDefinitions = SystemDefinitions.unavailable("not set by the migration");
	// index-only placeholders for folders that are empty in EWM (never written to the sandbox)
	private final Set<String> keepFiles = new TreeSet<String>();
	// UUIDs of the EWM baselines that already have a tag (read from the tag messages)
	private Set<String> taggedBaselines;
	// UUIDs of the EWM snapshots that already have a tag
	private Set<String> taggedSnapshots;

	public GitMigrator(Properties properties) {
		ignoredFileExtensions = new HashSet<String>();
		WindowCacheConfig = new WindowCacheConfig();
		commitsAfterClean = 0;
		migratedChangeSets = new HashSet<String>();
		initialize(properties);
	}

	private Charset getCharset() {
		return defaultCharset;
	}

	private void initRootGitignore(File sandboxRootDirectory) throws IOException {
		Set<String> ignoreEntries = new LinkedHashSet<String>(ROOT_IGNORED_ENTRIES);
		parseElements(properties.getProperty("global.gitignore.entries", ""), ignoreEntries);
		initRootFile(new File(sandboxRootDirectory, ".gitignore"), ignoreEntries);
	}

	private void initRootGitattributes(File sandboxRootDirectory) throws IOException {
		initRootFile(new File(sandboxRootDirectory, ".gitattributes"), getGitattributeLines());
	}

	private void initRootFile(File rootFile, Collection<String> linesToAdd) throws IOException {
		Charset charset = getCharset();
		List<String> existingLines = Files.readLines(rootFile, charset);
		addMissing(existingLines, linesToAdd);
		if (!existingLines.isEmpty()) {
			Files.writeLines(rootFile, existingLines, charset, false);
		}
	}

	private void parseElements(String elementString, Collection<String> consumer) {
		if (elementString == null || elementString.isEmpty()) {
			return;
		}
		String[] splitted = elementString.split(";");
		int splittedLength = splitted.length;
		for (int i = 0; i < splittedLength; i++) {
			consumer.add(splitted[i].trim());
		}
	}

	void addMissing(Collection<String> existing, Collection<String> adding) {
		for (String entry : adding) {
			if (!existing.contains(entry)) {
				existing.add(entry);
			}
		}
	}

	String getCommitMessage(String workItemNumbers, String comment, String workItemTexts) {
		return String.format(properties.getProperty("commit.message.format", "%1s %2s"), workItemNumbers,
				commentTranslator.translate(comment), workItemTexts).trim();
	}

	List<String> getGitattributeLines() {
		List<String> lines = new ArrayList<String>();
		parseElements(properties.getProperty("gitattributes", ""), lines);
		return lines;
	}

	Set<String> getIgnoredFileExtensions() {
		return ignoredFileExtensions;
	}

	WindowCacheConfig getWindowCacheConfig() {
		return WindowCacheConfig;
	}

	String getCommentText(ChangeSet changeSet) {
		String comment = changeSet.getComment();
		String workItemText = changeSet.getWorkItems().isEmpty() ? "" : changeSet.getWorkItems().get(0).getText();
		final boolean substituteWorkItemText = Boolean
				.valueOf(properties.getProperty("rtc.changeset.comment.substitute", Boolean.FALSE.toString()));
		if (comment.isEmpty() && substituteWorkItemText)
			return workItemText;
		final String format = properties.getProperty("rtc.changeset.comment.format", "%1s");
		return String.format(format, comment, workItemText);
	}

	String getWorkItemNumbers(List<WorkItem> workItems) {
		if (workItems.isEmpty()) {
			return "";
		}
		final String format = properties.getProperty("rtc.workitem.number.format", "%1s");
		final String delimiter = properties.getProperty("rtc.workitem.number.delimiter", " ");
		final StringBuilder sb = new StringBuilder();
		boolean isFirst = true;
		Formatter formatter = new Formatter(sb);
		try {
			for (WorkItem workItem : workItems) {
				if (isFirst) {
					isFirst = false;
				} else {
					sb.append(delimiter);
				}
				formatter.format(format, String.valueOf(workItem.getNumber()));
			}

		} finally {
			formatter.close();
		}
		return sb.toString();
	}

	String getWorkItemTexts(List<WorkItem> workItems) {
		if (workItems.isEmpty()) {
			return "";
		}
		final String format = properties.getProperty("rtc.workitem.text.format", "%1s %2s");
		final String delimiter = properties.getProperty("rtc.workitem.text.delimiter",
				System.getProperty("line.separator"));
		final StringBuilder sb = new StringBuilder();
		boolean isFirst = true;
		Formatter formatter = new Formatter(sb);
		try {
			for (WorkItem workItem : workItems) {
				if (isFirst) {
					isFirst = false;
				} else {
					sb.append(delimiter);
				}
				formatter.format(format, String.valueOf(workItem.getNumber()), workItem.getText());
			}

		} finally {
			formatter.close();
		}
		return sb.toString();
	}

	SortedSet<String> getExistingIgnoredFiles() {
		try {
			TreeSet<String> exstingIgnoredFiles = new TreeSet<String>();
			for (String ignoredFile : Files.readLines(new File(rootDir, ".gitignore"), getCharset())) {
				if (ignoredFile.startsWith("/")) {
					File ignored = new File(rootDir, ignoredFile.substring(1));
					if (ignored.exists() && ignored.isFile()) {
						exstingIgnoredFiles.add(ignoredFile);
					}
				}
			}
			return exstingIgnoredFiles;
		} catch (IOException e) {
			throw new RuntimeException("To process .gitignore", e);
		}
	}

	private void gitCommit(PersonIdent author, PersonIdent committer, String comment, boolean allowEmpty) {
		lastCommitId = null;
		try {
			// add all untracked files
			Status status = git.status().call();

			Set<String> toAdd = handleAdded(status);
			Set<String> toForce = handleIgnoredButVersioned(status);
			Set<String> toRestore = new HashSet<String>();
			Set<String> toRemove = handleRemoved(status, toRestore);
			toAdd.addAll(pendingAdds);
			toRemove.removeAll(pendingAdds);
			pendingAdds.clear();

			// execute the git index commands if needed
			if (!toAdd.isEmpty()) {
				AddCommand add = git.add();
				for (String filepattern : toAdd) {
					add.addFilepattern(filepattern);
				}
				add.call();
			}
			if (!toForce.isEmpty()) {
				AddCommand add = git.add()
						.setWorkingTreeIterator(new ForceAddTreeIterator(git.getRepository(), toForce));
				for (String filepattern : toForce) {
					add.addFilepattern(filepattern);
				}
				add.call();
			}
			if (!toRemove.isEmpty()) {
				RmCommand rm = git.rm();
				for (String filepattern : toRemove) {
					rm.addFilepattern(filepattern);
				}
				rm.call();
			}
			markExecutables();
			syncKeepFiles();
			if (!toRestore.isEmpty()) {
				CheckoutCommand checkout = git.checkout();
				for (String filepattern : toRestore) {
					checkout.addPath(filepattern);
				}
				checkout.call();
			}

			// commit what is staged; JGit compares the index with HEAD, so staging that changed nothing (e.g. an
			// ignored empty folder) does not produce a commit unless one commit per change set is wanted
			if (!toAdd.isEmpty() || !toForce.isEmpty() || !toRemove.isEmpty() || allowEmpty || keepEmptyFolders) {
				try {
					lastCommitId = git.commit().setMessage(comment).setAuthor(author).setCommitter(committer)
							.setAllowEmpty(allowEmpty).call().getId().name();
				} catch (EmptyCommitException e) {
					// nothing changed
				}
			}

			++commitsAfterClean;
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException("Unable to commit changes", e);
		}
	}

	@Override
	public boolean needsIntermediateCleanup() {
		return commitsAfterClean >= 1000;
	}

	@Override
	public void intermediateCleanup() {
		runGitGc();
		commitsAfterClean = 0;
	}

	private Set<String> handleRemoved(Status status, Set<String> toRestore) {
		Set<String> toRemove = new HashSet<String>();
		// go over all deleted files
		for (String removed : status.getMissing()) {
			if (isKeepFile(removed)) {
				continue; // index-only placeholder, see syncKeepFiles
			}
			Matcher matcher = GITIGNORE_PATTERN.matcher(removed);
			if (matcher.matches()) {
				File jazzignore = new File(rootDir, matcher.group(1).concat(".jazzignore"));
				if (jazzignore.exists()) {
					// restore .gitignore files that where deleted if corresponding .jazzignore exists
					toRestore.add(removed);
					continue;
				}
			}
			// adds removed entry to the index
			toRemove.add(removed);
		}
		handleJazzignores(toRemove);
		return toRemove;
	}

	/**
	 * Only scm writes to the sandbox during a migration, so an ignored file that is present but not tracked came from
	 * EWM, where it is versioned; it is added anyway. Exceptions are the scm metadata and what the migration
	 * properties exclude on purpose (<code>global.gitignore.entries</code>, <code>ignore.file.extensions</code>).
	 */
	Set<String> handleIgnoredButVersioned(Status status) {
		Set<String> toForce = new TreeSet<String>();
		if (!forceAddIgnored) {
			return toForce;
		}
		for (String ignored : status.getIgnoredNotInIndex()) {
			if (!isIntentionallyIgnored(ignored)) {
				toForce.add(ignored);
			}
		}
		return toForce;
	}

	boolean isIntentionallyIgnored(String path) {
		if (intentionalIgnores == null) {
			Set<String> patterns = new LinkedHashSet<String>(ROOT_IGNORED_ENTRIES);
			parseElements(properties.getProperty("global.gitignore.entries", ""), patterns);
			for (String extension : getIgnoredFileExtensions()) {
				patterns.add("*" + extension);
			}
			intentionalIgnores = new ArrayList<FastIgnoreRule>();
			for (String pattern : patterns) {
				intentionalIgnores.add(new FastIgnoreRule(pattern));
			}
		}
		// a path is excluded when it or one of its parent folders matches
		for (String candidate = path; candidate != null; candidate = parent(candidate)) {
			boolean directory = !candidate.equals(path) || new File(rootDir, candidate).isDirectory();
			for (FastIgnoreRule rule : intentionalIgnores) {
				if (rule.isMatch(candidate, directory) && rule.getResult()) {
					return true;
				}
			}
		}
		return false;
	}

	private static String parent(String path) {
		int slash = path.lastIndexOf('/');
		return slash < 0 ? null : path.substring(0, slash);
	}

	private Set<String> handleAdded(Status status) {
		Set<String> toAdd = new HashSet<String>();
		// go over untracked files
		for (String untracked : status.getUntracked()) {
			// add it to the index
			toAdd.add(untracked);
		}
		// go over modified files
		for (String modified : status.getModified()) {
			// adds a modified entry to the index
			toAdd.add(modified);
		}
		handleGlobalFileExtensions(toAdd);
		handleJazzignores(toAdd);
		return toAdd;
	}

	private void handleJazzignores(Set<String> relativeFileNames) {
		try {
			Set<String> additionalNames = new HashSet<String>();
			for (String relativeFileName : relativeFileNames) {
				Matcher matcher = JAZZIGNORE_PATTERN.matcher(relativeFileName);
				if (matcher.matches()) {
					File jazzIgnore = new File(rootDir, relativeFileName);
					String gitignoreFile = matcher.group(1).concat(".gitignore");
					if (matcher.group(1).isEmpty()) {
						// the root .gitignore also holds the migration's own entries: only replace the block that
						// comes from the root .jazzignore
						updateRootJazzignoreBlock(jazzIgnore.exists() ? JazzignoreTranslator.toGitignore(jazzIgnore)
								: Collections.<String> emptyList());
						pendingAdds.add(gitignoreFile);
						continue;
					} else if (jazzIgnore.exists()) {
						// change/add case
						List<String> ignoreContent = JazzignoreTranslator.toGitignore(jazzIgnore);
						Files.writeLines(new File(rootDir, gitignoreFile), ignoreContent, getCharset(), false);
					} else {
						// delete case
						new File(rootDir, gitignoreFile).delete();
					}
					additionalNames.add(gitignoreFile);
				}
			}
			// add additional modified name
			relativeFileNames.addAll(additionalNames);
		} catch (IOException e) {
			throw new RuntimeException("Unable to handle .jazzignore", e);
		}
	}

	static final String ROOT_JAZZIGNORE_BEGIN = "# >>> translated from /.jazzignore";
	static final String ROOT_JAZZIGNORE_END = "# <<< translated from /.jazzignore";

	private void updateRootJazzignoreBlock(List<String> translated) throws IOException {
		File rootGitignore = new File(rootDir, ".gitignore");
		List<String> lines = new ArrayList<String>();
		boolean inBlock = false;
		for (String line : Files.readLines(rootGitignore, getCharset())) {
			if (line.equals(ROOT_JAZZIGNORE_BEGIN)) {
				inBlock = true;
			} else if (line.equals(ROOT_JAZZIGNORE_END)) {
				inBlock = false;
			} else if (!inBlock) {
				lines.add(line);
			}
		}
		if (!translated.isEmpty()) {
			lines.add(ROOT_JAZZIGNORE_BEGIN);
			lines.addAll(translated);
			lines.add(ROOT_JAZZIGNORE_END);
		}
		Files.writeLines(rootGitignore, lines, getCharset(), false);
	}

	private void handleGlobalFileExtensions(Set<String> addToGitIndex) {
		Set<String> gitignoreEntries = new LinkedHashSet<String>();
		for (String extension : getIgnoredFileExtensions()) {
			for (Iterator<String> candidateIt = addToGitIndex.iterator(); candidateIt.hasNext();) {
				String addCandidate = candidateIt.next();
				if (addCandidate.endsWith(extension)) {
					gitignoreEntries.add("/".concat(addCandidate));
					candidateIt.remove();
				}
			}
		}
		if (!gitignoreEntries.isEmpty()) {
			try {
				Files.writeLines(new File(rootDir, ".gitignore"), gitignoreEntries, getCharset(), true);
				addToGitIndex.add(".gitignore");
			} catch (IOException e) {
				throw new RuntimeException("Unable to handle .gitignore", e);
			}
		}
	}

	private void initConfig() throws IOException {
		StoredConfig config = git.getRepository().getConfig();
		config.setBoolean("core", null, "ignoreCase", false);
		config.setString("core", null, "autocrlf", File.separatorChar == '/' ? "input" : "true");
		config.setString("push", null, "default", "simple");
		fillConfigFromProperties(config);
		config.save();
	}

	private int getFactor(String sign) {
		if (!sign.isEmpty()) {
			switch (sign.charAt(0)) {
			case 'k':
			case 'K':
				return org.eclipse.jgit.storage.file.WindowCacheConfig.KB;
			case 'm':
			case 'M':
				return org.eclipse.jgit.storage.file.WindowCacheConfig.MB;
			}
		}
		return 1;
	}

	long parseConfigValue(String value, long defaultValue) {
		if (value != null) {
			Matcher matcher = VALUE_PATTERN.matcher(value.trim());
			if (matcher.matches()) {
				return getFactor(matcher.group(2)) * Long.parseLong(matcher.group(1));
			}
		}
		return defaultValue;
	}

	void initialize(Properties props) {
		properties = props;
		defaultCharset = Charset.forName(props.getProperty("file.encoding", "UTF-8").trim());
		commentTranslator = new CommitCommentTranslator(props);
		defaultIdent = new PersonIdent(props.getProperty("user.name", "RTC 2 git"),
				props.getProperty("user.email", "rtc2git@rtc.to"));
		identities = new IdentityResolver(props, defaultIdent, defaultCharset);
		commitEmptyChangeSets = Boolean.parseBoolean(props.getProperty("commit.empty.changesets", "true"));
		changeSetTrailer = Boolean.parseBoolean(props.getProperty("commit.changeset.trailer", "true"));
		forceAddIgnored = Boolean.parseBoolean(props.getProperty("commit.force.add.ignored", "true"));
		attributesFromEwm = Boolean.parseBoolean(props.getProperty("gitattributes.from.ewm", "true"));
		keepEmptyFolders = Boolean.parseBoolean(props.getProperty("keep.empty.folders", "false"));
		String codePage = props.getProperty("zos.codepage", ZosMetadata.DEFAULT_CODE_PAGE).trim();
		zosCodePage = codePage.isEmpty() || codePage.equalsIgnoreCase("none") ? null : codePage;
		zosMetadata = Boolean.parseBoolean(props.getProperty("zos.metadata", "true"));
		attributesGenerator = new GitattributesGenerator(
				Boolean.parseBoolean(props.getProperty("gitattributes.working-tree-encoding", "false")), zosCodePage);
		intentionalIgnores = null;
		parseElements(props.getProperty("ignore.file.extensions", ""), ignoredFileExtensions);
		// update window cache config
		WindowCacheConfig cfg = getWindowCacheConfig();
		cfg.setPackedGitOpenFiles(
				(int) parseConfigValue(props.getProperty("packedgitopenfiles"), cfg.getPackedGitOpenFiles()));
		cfg.setPackedGitLimit(parseConfigValue(props.getProperty("packedgitlimit"), cfg.getPackedGitLimit()));
		cfg.setPackedGitWindowSize(
				(int) parseConfigValue(props.getProperty("packedgitwindowsize"), cfg.getPackedGitWindowSize()));
		cfg.setPackedGitMMAP(Boolean.parseBoolean(props.getProperty("packedgitmmap")));
		cfg.setDeltaBaseCacheLimit(
				(int) parseConfigValue(props.getProperty("deltabasecachelimit"), cfg.getDeltaBaseCacheLimit()));
		long sft = parseConfigValue(props.getProperty("streamfilethreshold"), cfg.getStreamFileThreshold());
		cfg.setStreamFileThreshold(getMaxFileThresholdValue(sft, Runtime.getRuntime().maxMemory()));
	}

	int getMaxFileThresholdValue(long configThreshold, final long maxMem) {
		// don't use more than 1/4 of the heap
		configThreshold = Math.min(configThreshold, maxMem / 4);
		// cannot exceed array length
		configThreshold = Math.min(configThreshold, Integer.MAX_VALUE);
		return (int) configThreshold;
	}

	/**
	 * Turns an EWM baseline name into a valid git tag name.
	 */
	static String createTagName(String tagName) {
		StringBuilder sb = new StringBuilder();
		for (char c : tagName.trim().toCharArray()) {
			sb.append(c <= ' ' || c == 0x7f || "~^:?*[]\\".indexOf(c) >= 0 ? '_' : c);
		}
		String name = sb.toString().replace("@{", "_{");
		while (name.contains("..")) {
			name = name.replace("..", "_.");
		}
		while (name.startsWith(".") || name.startsWith("-") || name.startsWith("/")) {
			name = name.substring(1);
		}
		while (name.endsWith(".") || name.endsWith("/")) {
			name = name.substring(0, name.length() - 1);
		}
		if (name.endsWith(".lock")) {
			name = name + "_";
		}
		if (!Repository.isValidRefName(Constants.R_TAGS + name)) {
			name = name.replace('/', '_');
		}
		if (name.isEmpty() || !Repository.isValidRefName(Constants.R_TAGS + name)) {
			name = "tag_" + Integer.toHexString(tagName.hashCode());
		}
		return name;
	}

	@Override
	public void init(File sandboxRootDirectory) {
		rootDir = sandboxRootDirectory;
		try {
			File bareGitDirectory = new File(sandboxRootDirectory, ".git");
			if (bareGitDirectory.exists()) {
				git = Git.open(sandboxRootDirectory);
				checkResumable();
			} else if (sandboxRootDirectory.exists()) {
				git = Git.init().setDirectory(sandboxRootDirectory).call();
			} else {
				throw new RuntimeException(bareGitDirectory + " does not exist");
			}
			getWindowCacheConfig().install();
			initRootGitignore(sandboxRootDirectory);
			initRootGitattributes(sandboxRootDirectory);
			if (propertiesBeforeInit != null) {
				writeGeneratedFiles(propertiesBeforeInit);
				propertiesBeforeInit = null;
			}
			initConfig();
			boolean resumed = git.getRepository().resolve(Constants.HEAD) != null;
			PersonIdent now = new PersonIdent(defaultIdent, Instant.now(), identities.getZoneId());
			if (resumed) {
				gitCommit(now, now, "Update generated files before resuming the migration", false);
			} else {
				StringBuilder message = new StringBuilder("Initial commit");
				if (!initialState.isEmpty()) {
					// start state of the target workspace, read when resuming an interrupted run
					message.append('\n');
					for (Map.Entry<String, String> component : initialState.entrySet()) {
						message.append('\n').append(BASE_TRAILER).append(": ").append(component.getKey()).append('=')
								.append(component.getValue());
					}
				}
				gitCommit(now, now, message.toString(), !initialState.isEmpty());
			}
		} catch (IOException e) {
			throw new RuntimeException("Unable to initialize GIT repository", e);
		} catch (GitAPIException e) {
			throw new RuntimeException("Unable to initialize GIT repository", e);
		}
	}

	@Override
	public void close() {
		if (git != null) {
			try {
				runGitGc();
			} finally {
				git.close();
			}
		}
		SortedSet<String> existingIgnoredFiles = getExistingIgnoredFiles();
		if (!existingIgnoredFiles.isEmpty()) {
			System.err.println("Some ignored files still exist in the sandbox:");
			for (String existingIgnoredEntry : existingIgnoredFiles) {
				System.err.println(existingIgnoredEntry);
			}
		}
	}

	private void runGitGc() {
		try {
			git.gc().call();
		} catch (GitAPIException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void commitChanges(ChangeSet changeset) {
		String message = getCommitMessage(getWorkItemNumbers(changeset.getWorkItems()), getCommentText(changeset),
				getWorkItemTexts(changeset.getWorkItems()));
		String uuid = changeset.getUuid();
		if (changeSetTrailer && uuid != null) {
			message = message + "\n\n" + CHANGE_SET_TRAILER + ": " + uuid;
		}
		gitCommit(identities.resolve(changeset), identities.resolveCommitter(changeset), message,
				commitEmptyChangeSets && uuid != null);
		if (uuid != null) {
			migratedChangeSets.add(uuid);
		}
	}

	@Override
	public void updateFileProperties(Collection<FileProperties> files) {
		if (keepEmptyFolders) {
			updateKeepFiles(files);
		}
		if (!attributesFromEwm && !zosMetadata) {
			return;
		}
		if (rootDir == null) {
			propertiesBeforeInit = new ArrayList<FileProperties>(files);
			return;
		}
		try {
			writeGeneratedFiles(files);
		} catch (IOException e) {
			throw new RuntimeException("Unable to write the files generated from EWM properties", e);
		}
	}

	@Override
	public void setSystemDefinitions(SystemDefinitions definitions) {
		systemDefinitions = definitions;
	}

	private void writeGeneratedFiles(Collection<FileProperties> files) throws IOException {
		if (attributesFromEwm) {
			writeGeneratedAttributes(files);
		}
		if (zosMetadata) {
			writeZosMetadata(files);
		}
	}

	/**
	 * Writes <code>.ewm/zos-metadata.json</code> when it changed; removes it when no file has z/OS metadata (any
	 * more).
	 */
	private void writeZosMetadata(Collection<FileProperties> files) throws IOException {
		File file = new File(rootDir, ZosMetadata.PATH);
		Map<String, Object> document = ZosMetadata.build(files, systemDefinitions,
				zosCodePage == null ? ZosMetadata.DEFAULT_CODE_PAGE : zosCodePage);
		if (document == null) {
			if (file.exists()) {
				java.nio.file.Files.delete(file.toPath());
			}
			return;
		}
		byte[] content = JsonWriter.toString(document).getBytes(StandardCharsets.UTF_8);
		if (file.exists() && Arrays.equals(content, java.nio.file.Files.readAllBytes(file.toPath()))) {
			return;
		}
		file.getParentFile().mkdirs();
		java.nio.file.Files.write(file.toPath(), content);
	}

	/**
	 * Replaces the generated block of the root <code>.gitattributes</code>; lines from the <code>gitattributes</code>
	 * property stay in front of it.
	 */
	private void writeGeneratedAttributes(Collection<FileProperties> files) throws IOException {
		executablePaths.clear();
		for (FileProperties file : files) {
			if (!file.isFolder() && file.isExecutable()) {
				executablePaths.add(file.getPath());
			}
		}
		List<String> generated = attributesGenerator.generate(files);
		File gitattributes = new File(rootDir, ".gitattributes");
		List<String> existing = Files.readLines(gitattributes, getCharset());
		List<String> lines = new ArrayList<String>();
		boolean inBlock = false;
		for (String line : existing) {
			if (line.equals(GitattributesGenerator.BEGIN)) {
				inBlock = true;
			} else if (line.equals(GitattributesGenerator.END)) {
				inBlock = false;
			} else if (!inBlock) {
				lines.add(line);
			}
		}
		if (!generated.isEmpty()) {
			lines.add(GitattributesGenerator.BEGIN);
			lines.addAll(generated);
			lines.add(GitattributesGenerator.END);
		}
		if (!lines.equals(existing)) {
			Files.writeLines(gitattributes, lines, getCharset(), false);
		}
	}

	private void updateKeepFiles(Collection<FileProperties> files) {
		Set<String> emptyFolders = new TreeSet<String>();
		Set<String> parents = new HashSet<String>();
		for (FileProperties item : files) {
			if (item.isFolder()) {
				emptyFolders.add(item.getPath());
			}
			for (String parent = parent(item.getPath()); parent != null; parent = parent(parent)) {
				parents.add(parent);
			}
		}
		emptyFolders.removeAll(parents);
		keepFiles.clear();
		for (String folder : emptyFolders) {
			keepFiles.add(folder + "/" + KEEP_FILE);
		}
	}

	private static boolean isKeepFile(String path) {
		return path.equals(KEEP_FILE) || path.endsWith("/" + KEEP_FILE);
	}

	/**
	 * Adds the placeholders of empty EWM folders to the index and removes those of folders that are no longer empty.
	 */
	private void syncKeepFiles() throws IOException {
		if (!keepEmptyFolders) {
			return;
		}
		Repository repository = git.getRepository();
		DirCache dirCache = repository.lockDirCache();
		try (ObjectInserter inserter = repository.newObjectInserter()) {
			final ObjectId empty = inserter.insert(Constants.OBJ_BLOB, new byte[0]);
			inserter.flush();
			DirCacheEditor editor = dirCache.editor();
			boolean changed = false;
			for (int i = 0; i < dirCache.getEntryCount(); i++) {
				String path = dirCache.getEntry(i).getPathString();
				if (isKeepFile(path) && !keepFiles.contains(path) && !new File(rootDir, path).exists()) {
					editor.add(new DirCacheEditor.DeletePath(path));
					changed = true;
				}
			}
			for (String path : keepFiles) {
				if (dirCache.getEntry(path) == null) {
					editor.add(new DirCacheEditor.PathEdit(path) {
						@Override
						public void apply(DirCacheEntry entry) {
							entry.setFileMode(FileMode.REGULAR_FILE);
							entry.setObjectId(empty);
							entry.setLength(0);
							// not in the sandbox on purpose: keep git status clean
							entry.setAssumeValid(true);
						}
					});
					changed = true;
				}
			}
			if (changed) {
				editor.finish();
				dirCache.write();
				dirCache.commit();
			}
		} finally {
			dirCache.unlock();
		}
	}

	/**
	 * EWM's executable flag becomes the git file mode, also where the file system has no executable bit.
	 */
	private void markExecutables() throws IOException {
		if (executablePaths.isEmpty()) {
			return;
		}
		DirCache dirCache = git.getRepository().lockDirCache();
		try {
			boolean changed = false;
			for (String path : executablePaths) {
				new File(rootDir, path).setExecutable(true);
				DirCacheEntry entry = dirCache.getEntry(path);
				if (entry != null && !FileMode.EXECUTABLE_FILE.equals(entry.getFileMode())) {
					entry.setFileMode(FileMode.EXECUTABLE_FILE);
					changed = true;
				}
			}
			if (changed) {
				dirCache.write();
				dirCache.commit();
			}
		} finally {
			dirCache.unlock();
		}
	}

	@Override
	public String getLastCommitId() {
		return lastCommitId;
	}

	@Override
	public void setInitialState(Map<String, String> newestChangeSets) {
		initialState.clear();
		initialState.putAll(newestChangeSets);
	}

	@Override
	public ResumeState inspectResume(File sandboxRootDirectory) {
		if (!new File(sandboxRootDirectory, ".git").exists()) {
			return ResumeState.NEW;
		}
		try (Git existing = Git.open(sandboxRootDirectory)) {
			if (existing.getRepository().resolve(Constants.HEAD) == null) {
				return ResumeState.NEW;
			}
			Set<String> migrated = new HashSet<String>();
			Map<String, String> base = new HashMap<String, String>();
			for (RevCommit commit : existing.log().call()) {
				migrated.addAll(commit.getFooterLines(CHANGE_SET_TRAILER));
				for (String line : commit.getFooterLines(BASE_TRAILER)) {
					int equals = line.indexOf('=');
					if (equals > 0) {
						base.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
					}
				}
			}
			return new ResumeState(true, migrated, base);
		} catch (IOException | GitAPIException e) {
			throw new RuntimeException("Unable to read the git history of " + sandboxRootDirectory, e);
		}
	}

	@Override
	public boolean isMigrated(String changeSetUuid) {
		return migratedChangeSets.contains(changeSetUuid);
	}

	/**
	 * An existing repository is resumed: its working tree must be clean (otherwise an earlier run stopped between
	 * accepting a change set and committing it), and the change sets it already contains are remembered.
	 */
	private void checkResumable() throws GitAPIException, IOException {
		if (git.getRepository().resolve(Constants.HEAD) == null) {
			return;
		}
		Status status = git.status().call();
		Set<String> pending = new TreeSet<String>(status.getUncommittedChanges());
		pending.addAll(status.getUntracked());
		for (String missing : status.getMissing()) {
			if (isKeepFile(missing)) {
				pending.remove(missing);
			}
		}
		if (!pending.isEmpty()) {
			throw new IllegalStateException("The sandbox " + rootDir + " has uncommitted changes " + pending
					+ ". A previous migration probably stopped after accepting a change set but before committing it."
					+ " Commit or discard these changes, then run the migration again.");
		}
		for (RevCommit commit : git.log().call()) {
			migratedChangeSets.addAll(commit.getFooterLines(CHANGE_SET_TRAILER));
		}
	}

	@Override
	public void createTag(Tag tag) {
		String tagName = tag.getName();
		if (tagName == null || tagName.isEmpty()) {
			return;
		}
		try {
			Repository repository = git.getRepository();
			String snapshot = tag.getSnapshotUuid();
			if (snapshot != null) {
				if (getTaggedSnapshots().contains(snapshot)) {
					return; // tagged by an earlier run
				}
			} else {
				for (String baseline : tag.getBaselineUuids()) {
					if (getTaggedBaselines().contains(baseline)) {
						return; // tagged by an earlier run
					}
				}
			}
			ObjectId head = repository.resolve(Constants.HEAD);
			String baseName = createTagName(tagName);
			String name = baseName;
			Ref existing;
			for (int i = 2; (existing = repository.exactRef(Constants.R_TAGS + name)) != null; i++) {
				if (head != null && head.equals(peeled(repository, existing))) {
					// re-run of an already tagged baseline
					return;
				}
				name = baseName + "_" + i;
			}
			long created = tag.getCreationDate();
			Instant when = (created <= 0 || created == Long.MAX_VALUE) ? Instant.now() : Instant.ofEpochMilli(created);
			StringBuilder message = new StringBuilder(snapshot != null ? "EWM snapshot: " : "EWM baseline: ")
					.append(tag.getOriginalName());
			if (snapshot != null || !tag.getBaselineUuids().isEmpty()) {
				message.append('\n');
				if (snapshot != null) {
					message.append('\n').append(SNAPSHOT_TRAILER).append(": ").append(snapshot);
				}
				for (String uuid : tag.getBaselineUuids()) {
					message.append('\n').append(BASELINE_TRAILER).append(": ").append(uuid);
				}
			}
			git.tag().setName(name).setAnnotated(true).setMessage(message.toString())
					.setTagger(new PersonIdent(defaultIdent, when, identities.getZoneId())).call();
			if (snapshot != null) {
				getTaggedSnapshots().add(snapshot);
			} else {
				getTaggedBaselines().addAll(tag.getBaselineUuids());
			}
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException("Unable to tag", e);
		}
	}

	private Set<String> getTaggedBaselines() throws IOException {
		readTagTrailers();
		return taggedBaselines;
	}

	private Set<String> getTaggedSnapshots() throws IOException {
		readTagTrailers();
		return taggedSnapshots;
	}

	/**
	 * Reads which baselines and snapshots the existing tags represent (the baselines listed in a snapshot tag do not
	 * count as tagged baselines).
	 */
	private void readTagTrailers() throws IOException {
		if (taggedBaselines != null) {
			return;
		}
		taggedBaselines = new HashSet<String>();
		taggedSnapshots = new HashSet<String>();
		Repository repository = git.getRepository();
		try (RevWalk walk = new RevWalk(repository)) {
			for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_TAGS)) {
				RevObject object = walk.parseAny(ref.getObjectId());
				if (!(object instanceof RevTag)) {
					continue;
				}
				Set<String> baselines = new HashSet<String>();
				String snapshot = null;
				for (String line : ((RevTag) object).getFullMessage().split("\n")) {
					if (line.startsWith(BASELINE_TRAILER + ": ")) {
						baselines.add(line.substring(BASELINE_TRAILER.length() + 2).trim());
					} else if (line.startsWith(SNAPSHOT_TRAILER + ": ")) {
						snapshot = line.substring(SNAPSHOT_TRAILER.length() + 2).trim();
					}
				}
				if (snapshot != null) {
					taggedSnapshots.add(snapshot);
				} else {
					taggedBaselines.addAll(baselines);
				}
			}
		}
	}

	private static ObjectId peeled(Repository repository, Ref ref) throws IOException {
		Ref peeled = repository.getRefDatabase().peel(ref);
		return peeled.getPeeledObjectId() != null ? peeled.getPeeledObjectId() : peeled.getObjectId();
	}

	private void fillConfigFromProperties(Config config) {
		for (Entry<Object, Object> entry : properties.entrySet()) {
			if (entry.getKey() instanceof String && (((String) entry.getKey()).startsWith(GIT_CONFIG_PREFIX))) {

				String key = ((String) entry.getKey()).substring(GIT_CONFIG_PREFIX.length());
				int dot = key.indexOf('.');
				int dot1 = key.lastIndexOf('.');
				// so far supporting section/key entries, no subsections
				if (dot < 1 || dot == key.length() - 1 || dot1 != dot) {
					// invalid config key entry
					continue;
				}
				String section = key.substring(0, dot);
				String name = key.substring(dot + 1);
				config.setString(section, null, name, entry.getValue().toString());
			}

		}
	}
}
