package to.rtc.cli.migrate;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * The EWM properties of one versioned file or folder, at its current path in the sandbox.
 */
public final class FileProperties {

	/** EWM line delimiter of a file; <code>NONE</code> means the content is stored and loaded unchanged. */
	public enum LineDelimiter {
		NONE, LF, CR, CRLF, PLATFORM
	}

	private final String itemId;
	private final String path;
	private final boolean folder;
	private final LineDelimiter lineDelimiter;
	private final String contentType;
	private final String encoding;
	private final boolean executable;
	private final Map<String, String> userProperties;

	private FileProperties(String itemId, String path, boolean folder, LineDelimiter lineDelimiter,
			String contentType, String encoding, boolean executable, Map<String, String> userProperties) {
		this.itemId = itemId;
		this.path = path;
		this.folder = folder;
		this.lineDelimiter = lineDelimiter;
		this.contentType = contentType;
		this.encoding = encoding;
		this.executable = executable;
		this.userProperties = Collections.unmodifiableMap(new TreeMap<String, String>(userProperties));
	}

	public static FileProperties folder(String itemId, String path, Map<String, String> userProperties) {
		return new FileProperties(itemId, path, true, null, null, null, false, userProperties);
	}

	public static FileProperties file(String itemId, String path, LineDelimiter lineDelimiter, String contentType,
			String encoding, boolean executable, Map<String, String> userProperties) {
		return new FileProperties(itemId, path, false, lineDelimiter, contentType, encoding, executable,
				userProperties);
	}

	/**
	 * @return a copy at another path (after the item or one of its parent folders was moved or renamed)
	 */
	public FileProperties withPath(String newPath) {
		return new FileProperties(itemId, newPath, folder, lineDelimiter, contentType, encoding, executable,
				userProperties);
	}

	public String getItemId() {
		return itemId;
	}

	/**
	 * @return the path relative to the sandbox root, separated by '/'
	 */
	public String getPath() {
		return path;
	}

	public boolean isFolder() {
		return folder;
	}

	public LineDelimiter getLineDelimiter() {
		return lineDelimiter;
	}

	public String getContentType() {
		return contentType;
	}

	public String getEncoding() {
		return encoding;
	}

	public boolean isExecutable() {
		return executable;
	}

	public Map<String, String> getUserProperties() {
		return userProperties;
	}

	@Override
	public String toString() {
		return path + (folder ? "/" : " [" + lineDelimiter + ", " + contentType + ", " + encoding
				+ (executable ? ", executable" : "") + "]");
	}
}
