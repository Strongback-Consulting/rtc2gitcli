package to.rtc.cli.migrate.zos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.ibm.team.enterprise.systemdefinition.client.ClientFactory;
import com.ibm.team.enterprise.systemdefinition.client.ISystemDefinitionModelClient;
import com.ibm.team.enterprise.systemdefinition.common.model.IDataSetDefinition;
import com.ibm.team.enterprise.systemdefinition.common.model.ILanguageDefinition;
import com.ibm.team.enterprise.systemdefinition.common.model.IStringHelper;
import com.ibm.team.enterprise.systemdefinition.common.model.ISystemDefinition;
import com.ibm.team.enterprise.systemdefinition.common.model.ISystemDefinitionHandle;
import com.ibm.team.enterprise.systemdefinition.common.model.IZosLanguageDefinition;
import com.ibm.team.process.common.IProjectArea;
import com.ibm.team.process.common.IProjectAreaHandle;
import com.ibm.team.repository.client.IItemManager;
import com.ibm.team.repository.client.ITeamRepository;
import com.ibm.team.repository.common.IItemType;
import com.ibm.team.repository.common.TeamRepositoryException;
import com.ibm.team.repository.common.UUID;

/**
 * {@link SystemDefinitions} backed by the EE system definition client. Only this class refers to EE types; it fails
 * to load (with a {@link LinkageError}) on SCM Tools without the EE bundles.
 */
final class EeSystemDefinitions extends SystemDefinitions {
	private final ISystemDefinitionModelClient client;
	private final IProgressMonitor monitor = new NullProgressMonitor();
	private final Map<String, ZosDefinition> cache = new HashMap<String, ZosDefinition>();

	private final ITeamRepository repository;
	private final Map<String, String> projectAreaNames = new HashMap<String, String>();

	EeSystemDefinitions(ITeamRepository repository) {
		this.repository = repository;
		this.client = ClientFactory.getSystemDefinitionModelClient(repository);
	}

	@Override
	public String getUnavailableReason() {
		return null;
	}

	@Override
	public synchronized Map<String, ZosDefinition> resolve(Collection<String> languageDefinitions,
			Collection<String> dataSetDefinitions) {
		fetch(IZosLanguageDefinition.ITEM_TYPE, languageDefinitions);
		fetch(IDataSetDefinition.ITEM_TYPE, dataSetDefinitions);
		Map<String, ZosDefinition> resolved = new HashMap<String, ZosDefinition>();
		for (Collection<String> uuids : List.of(languageDefinitions, dataSetDefinitions)) {
			for (String uuid : uuids) {
				ZosDefinition definition = cache.get(uuid);
				if (definition != null) {
					resolved.put(uuid, definition);
				}
			}
		}
		return resolved;
	}

	private void fetch(IItemType type, Collection<String> uuids) {
		List<ISystemDefinitionHandle> handles = new ArrayList<ISystemDefinitionHandle>();
		for (String uuid : uuids) {
			if (cache.containsKey(uuid)) {
				continue;
			}
			try {
				handles.add((ISystemDefinitionHandle) type.createItemHandle(UUID.valueOf(uuid), null));
			} catch (IllegalArgumentException e) {
				cache.put(uuid, null); // not a UUID
			}
		}
		if (handles.isEmpty()) {
			return;
		}
		try {
			store(client.fetchSystemDefinitionsComplete(handles, false, monitor));
		} catch (TeamRepositoryException batchFailure) {
			// one of them no longer exists (or is not readable): fetch the others one by one
			for (ISystemDefinitionHandle handle : handles) {
				try {
					store(client.fetchSystemDefinitionsComplete(Collections.singletonList(handle), false, monitor));
				} catch (TeamRepositoryException e) {
					cache.put(handle.getItemId().getUuidValue(), null);
				}
			}
		}
	}

	private void store(List<ISystemDefinition> definitions) {
		for (ISystemDefinition definition : definitions) {
			if (definition != null) {
				cache.put(definition.getItemId().getUuidValue(),
						toDefinition(definition, projectAreaName(definition.getProjectArea())));
			}
		}
	}

	private String projectAreaName(IProjectAreaHandle handle) {
		if (handle == null) {
			return null;
		}
		String uuid = handle.getItemId().getUuidValue();
		if (!projectAreaNames.containsKey(uuid)) {
			String name = null;
			try {
				name = ((IProjectArea) repository.itemManager().fetchCompleteItem(handle, IItemManager.DEFAULT,
						monitor)).getName();
			} catch (TeamRepositoryException e) {
				// not readable for this user: leave the name out
			}
			projectAreaNames.put(uuid, name);
		}
		return projectAreaNames.get(uuid);
	}

	@SuppressWarnings("unchecked")
	static ZosDefinition toDefinition(ISystemDefinition definition, String projectArea) {
		String uuid = definition.getItemId().getUuidValue();
		ZosDefinition.Builder builder;
		if (definition instanceof ILanguageDefinition) {
			ILanguageDefinition language = (ILanguageDefinition) definition;
			List<String> patterns = new ArrayList<String>();
			if (language.getDefaultPatterns() != null) {
				for (IStringHelper pattern : (List<IStringHelper>) language.getDefaultPatterns()) {
					patterns.add(pattern.getValue());
				}
			}
			builder = ZosDefinition.language(uuid, definition.getName()).languageCode(language.getLanguageCode())
					.defaultPatterns(patterns);
		} else {
			builder = ZosDefinition.dataSet(uuid, definition.getName());
			if (definition instanceof IDataSetDefinition) {
				IDataSetDefinition dataSet = (IDataSetDefinition) definition;
				builder.dataSet(dataSet.getDsName(), dataSet.getDsMember(), dataSet.isPrefixDSN(),
						dataSet.getUsageType(), dataSet.getDsType())
						.record(dataSet.getRecordFormat(), dataSet.getRecordLength());
			}
		}
		return builder.description(definition.getDescription()).projectArea(projectArea)
				.archived(definition.isArchived())
				.properties(definition.getProperties()).build();
	}
}
