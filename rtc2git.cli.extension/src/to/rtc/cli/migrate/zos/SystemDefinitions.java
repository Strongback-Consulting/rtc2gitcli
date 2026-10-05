package to.rtc.cli.migrate.zos;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import com.ibm.team.repository.client.ITeamRepository;

/**
 * Resolves the definition UUIDs stored in {@link ZosProperties} to {@link ZosDefinition}s.
 */
public abstract class SystemDefinitions {

	/**
	 * @return a resolver backed by the EE system definition client, or one that resolves nothing when the EE bundles
	 *         are not installed in this SCM Tools
	 */
	public static SystemDefinitions create(ITeamRepository repository) {
		try {
			return new EeSystemDefinitions(repository);
		} catch (LinkageError e) {
			// the optional EE packages are not wired: plain SCM Tools
			return unavailable("the EWM Enterprise Extensions bundles are not installed in this SCM Tools ("
					+ e.getMessage() + ")");
		}
	}

	public static SystemDefinitions unavailable(final String reason) {
		return new SystemDefinitions() {
			@Override
			public String getUnavailableReason() {
				return reason;
			}

			@Override
			public Map<String, ZosDefinition> resolve(Collection<String> languageDefinitions,
					Collection<String> dataSetDefinitions) {
				return Collections.emptyMap();
			}
		};
	}

	/**
	 * @return <code>null</code> if definitions can be resolved, otherwise why not
	 */
	public abstract String getUnavailableReason();

	/**
	 * @return UUID → definition; UUIDs of definitions that no longer exist are missing from the result
	 */
	public abstract Map<String, ZosDefinition> resolve(Collection<String> languageDefinitions,
			Collection<String> dataSetDefinitions);
}
