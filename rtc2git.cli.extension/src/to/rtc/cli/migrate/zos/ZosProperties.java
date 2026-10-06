package to.rtc.cli.migrate.zos;

import java.util.Map;
import java.util.TreeMap;

/**
 * User property keys that EWM Enterprise Extensions stores on versioned files and folders (see
 * <code>docs/ewm-zos-model.md</code>). Kept as strings so that plain SCM Tools without the EE bundles can read them.
 */
public final class ZosProperties {
	/** On a file (member): item UUID of its language definition. */
	public static final String LANGUAGE_DEFINITION = "team.enterprise.language.definition";
	/** On a folder (zFolder): item UUID of its data set definition. */
	public static final String RESOURCE_DEFINITION = "team.enterprise.resource.definition";
	/**
	 * Prefix of per-file build variables; the rest of the key is the name of the translator variable the file
	 * overrides (for example <code>CBLCMPOPTS</code>).
	 */
	public static final String BUILD_VARIABLE_PREFIX = "team.enterprise.build.var.";
	/** On a file: MVS code page of the member (when it differs from the default). */
	public static final String MVS_CODE_PAGE = "mvsCodePage";
	/** On a file: <code>true</code> if the build always loads the member. */
	public static final String ALWAYS_LOAD = "team.enterprise.build.alwaysload";
	/** On a file: <code>true</code> if changes to the member do not trigger dependent builds. */
	public static final String IGNORE_FOR_DEPENDENCY_BUILD = "team.enterprise.build.changes.ignoreForDependencyBuild";

	private ZosProperties() {
	}

	/**
	 * @return the build variables (name without prefix → value) among the user properties
	 */
	public static Map<String, String> buildVariables(Map<String, String> userProperties) {
		Map<String, String> variables = new TreeMap<String, String>();
		for (Map.Entry<String, String> property : userProperties.entrySet()) {
			if (property.getKey().startsWith(BUILD_VARIABLE_PREFIX)) {
				variables.put(property.getKey().substring(BUILD_VARIABLE_PREFIX.length()), property.getValue());
			}
		}
		return variables;
	}
}
