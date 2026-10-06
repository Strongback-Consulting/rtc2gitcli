package to.rtc.cli.migrate.git;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.eclipse.jgit.attributes.FilterCommand;
import org.eclipse.jgit.attributes.FilterCommandRegistry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;

/**
 * The Git LFS clean filter, built into JGit's filter registry: stores the content in the repository's LFS object
 * store and passes the pointer on to git. JGit uses it for paths with <code>filter=lfs</code> when the repository has
 * <code>filter.lfs.useJGitBuiltin=true</code>. The store is the one git-lfs uses: <code>lfs/objects</code> of the
 * common git directory, shared by all worktrees.
 */
final class LfsCleanFilter extends FilterCommand {
	static final String COMMAND = Constants.BUILTIN_FILTER_PREFIX + "lfs/clean";
	static final String POINTER_VERSION = "version https://git-lfs.github.com/spec/v1";

	private final Repository repository;

	private LfsCleanFilter(Repository repository, InputStream in, OutputStream out) {
		super(in, out);
		this.repository = repository;
	}

	/**
	 * Registers the filter (once per class loader; the registry is global).
	 */
	static synchronized void register() {
		if (!FilterCommandRegistry.isRegistered(COMMAND)) {
			FilterCommandRegistry.register(COMMAND, LfsCleanFilter::new);
		}
	}

	@Override
	public int run() throws IOException {
		File objects = objectStore(repository);
		File tmp = new File(objects.getParentFile(), "tmp");
		tmp.mkdirs();
		File content = File.createTempFile("clean", null, tmp);
		try {
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			long size;
			try (OutputStream file = new DigestOutputStream(Files.newOutputStream(content.toPath()), sha256)) {
				size = in.transferTo(file);
			}
			String oid = hex(sha256.digest());
			File object = objectFile(objects, oid);
			if (!object.isFile()) {
				object.getParentFile().mkdirs();
				Files.move(content.toPath(), object.toPath(), StandardCopyOption.ATOMIC_MOVE);
			}
			out.write(pointer(oid, size).getBytes(StandardCharsets.US_ASCII));
		} catch (NoSuchAlgorithmException e) {
			throw new IOException(e);
		} finally {
			Files.deleteIfExists(content.toPath());
			in.close();
			out.close();
		}
		return -1;
	}

	static String pointer(String oid, long size) {
		return POINTER_VERSION + "\noid sha256:" + oid + "\nsize " + size + "\n";
	}

	/**
	 * @return <code>lfs/objects</code> of the common git directory
	 */
	static File objectStore(Repository repository) {
		File common = repository.getCommonDirectory();
		return new File(common != null ? common : repository.getDirectory(), "lfs/objects");
	}

	static File objectFile(File objects, String oid) {
		return new File(objects, oid.substring(0, 2) + "/" + oid.substring(2, 4) + "/" + oid);
	}

	private static String hex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
		}
		return sb.toString();
	}
}
