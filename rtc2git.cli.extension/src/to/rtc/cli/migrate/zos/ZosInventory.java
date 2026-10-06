package to.rtc.cli.migrate.zos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import to.rtc.cli.migrate.FileProperties;

/**
 * The z/OS view of a stream or workspace: every file and folder with its EWM properties, the language and data set
 * definitions assigned to them, and a summary. Built from {@link FileProperties} so that it needs no server.
 */
public final class ZosInventory {
	private final Map<String, List<FileProperties>> components;
	private final Map<String, ZosDefinition> definitions;
	private final String unavailableReason;
	private final Set<String> unresolved = new TreeSet<String>();

	/**
	 * @param components
	 *            component name → its files and folders (paths relative to the component root)
	 */
	public ZosInventory(Map<String, List<FileProperties>> components, SystemDefinitions resolver) {
		this.components = new TreeMap<String, List<FileProperties>>(components);
		Set<String> languages = new TreeSet<String>();
		Set<String> dataSets = new TreeSet<String>();
		for (List<FileProperties> items : components.values()) {
			for (FileProperties item : items) {
				addIfPresent(languages, item.getUserProperties().get(ZosProperties.LANGUAGE_DEFINITION));
				addIfPresent(dataSets, item.getUserProperties().get(ZosProperties.RESOURCE_DEFINITION));
			}
		}
		this.definitions = resolver.resolve(languages, dataSets);
		this.unavailableReason = resolver.getUnavailableReason();
		for (String uuid : languages) {
			if (!definitions.containsKey(uuid)) {
				unresolved.add(uuid);
			}
		}
		for (String uuid : dataSets) {
			if (!definitions.containsKey(uuid)) {
				unresolved.add(uuid);
			}
		}
	}

	private static void addIfPresent(Set<String> set, String value) {
		if (value != null && !value.isEmpty()) {
			set.add(value);
		}
	}

	/**
	 * @return the definition's name, or the UUID when it cannot be resolved
	 */
	public String nameOf(String uuid) {
		ZosDefinition definition = definitions.get(uuid);
		return definition == null ? uuid : definition.getName();
	}

	public Set<String> getUnresolved() {
		return unresolved;
	}

	/**
	 * @return the inventory as JSON-ready map
	 */
	public Map<String, Object> toMap() {
		Map<String, Object> document = new LinkedHashMap<String, Object>();
		document.put("systemDefinitions", unavailableReason == null ? "resolved" : "unavailable: " + unavailableReason);
		document.put("summary", summary());
		List<Object> componentList = new ArrayList<Object>();
		for (Map.Entry<String, List<FileProperties>> component : components.entrySet()) {
			Map<String, Object> entry = new LinkedHashMap<String, Object>();
			entry.put("name", component.getKey());
			List<Object> items = new ArrayList<Object>();
			for (FileProperties item : sorted(component.getValue())) {
				items.add(itemMap(item));
			}
			entry.put("items", items);
			componentList.add(entry);
		}
		document.put("components", componentList);
		Map<String, Object> definitionMap = new TreeMap<String, Object>();
		for (ZosDefinition definition : definitions.values()) {
			definitionMap.put(definition.getUuid(), definition.toMap());
		}
		document.put("definitions", definitionMap);
		document.put("unresolvedDefinitions", new ArrayList<String>(unresolved));
		return document;
	}

	private Map<String, Object> itemMap(FileProperties item) {
		Map<String, Object> map = new LinkedHashMap<String, Object>();
		map.put("path", item.getPath());
		map.put("type", item.isFolder() ? "folder" : "file");
		map.put("itemId", item.getItemId());
		if (!item.isFolder()) {
			map.put("encoding", item.getEncoding());
			map.put("lineDelimiter", String.valueOf(item.getLineDelimiter()));
			map.put("contentType", item.getContentType());
			map.put("executable", Boolean.valueOf(item.isExecutable()));
		}
		String language = item.getUserProperties().get(ZosProperties.LANGUAGE_DEFINITION);
		if (language != null) {
			map.put("languageDefinition", nameOf(language));
		}
		String dataSet = item.getUserProperties().get(ZosProperties.RESOURCE_DEFINITION);
		if (dataSet != null) {
			map.put("dataSetDefinition", nameOf(dataSet));
		}
		Map<String, String> variables = ZosProperties.buildVariables(item.getUserProperties());
		if (!variables.isEmpty()) {
			map.put("buildVariables", variables);
		}
		if (item.getUserProperties().containsKey(ZosProperties.MVS_CODE_PAGE)) {
			map.put("mvsCodePage", item.getUserProperties().get(ZosProperties.MVS_CODE_PAGE));
		}
		if (!item.getUserProperties().isEmpty()) {
			map.put("userProperties", item.getUserProperties());
		}
		return map;
	}

	/**
	 * @return counts per definition, zFolder, encoding and user property key, plus inconsistencies
	 */
	public Map<String, Object> summary() {
		int files = 0;
		int folders = 0;
		Map<String, Integer> byLanguage = new TreeMap<String, Integer>();
		Map<String, Integer> byDataSet = new TreeMap<String, Integer>();
		Map<String, Integer> encodings = new TreeMap<String, Integer>();
		Map<String, Integer> contentTypes = new TreeMap<String, Integer>();
		Map<String, Integer> propertyKeys = new TreeMap<String, Integer>();
		Map<String, Map<String, Object>> overrides = new TreeMap<String, Map<String, Object>>();
		Map<String, Integer> codePages = new TreeMap<String, Integer>();
		Map<String, Object> zFolders = new TreeMap<String, Object>();
		List<String> membersWithoutLanguage = new ArrayList<String>();
		List<String> languageOutsideZFolder = new ArrayList<String>();
		for (Map.Entry<String, List<FileProperties>> component : components.entrySet()) {
			Map<String, String> folderDataSets = new TreeMap<String, String>();
			for (FileProperties item : component.getValue()) {
				if (item.isFolder()) {
					String dataSet = item.getUserProperties().get(ZosProperties.RESOURCE_DEFINITION);
					if (dataSet != null) {
						folderDataSets.put(item.getPath(), nameOf(dataSet));
					}
				}
			}
			for (FileProperties item : sorted(component.getValue())) {
				for (String key : item.getUserProperties().keySet()) {
					increment(propertyKeys, key);
				}
				String qualifiedPath = component.getKey() + ":" + item.getPath();
				if (item.isFolder()) {
					folders++;
					String dataSet = folderDataSets.get(item.getPath());
					if (dataSet != null) {
						increment(byDataSet, dataSet);
						Map<String, Object> zFolder = new LinkedHashMap<String, Object>();
						zFolder.put("dataSetDefinition", dataSet);
						zFolder.put("languageDefinitions", new TreeMap<String, Integer>());
						zFolders.put(qualifiedPath, zFolder);
					}
					continue;
				}
				files++;
				for (Map.Entry<String, String> variable : ZosProperties.buildVariables(item.getUserProperties())
						.entrySet()) {
					addOverride(overrides, variable.getKey(), variable.getValue(), qualifiedPath,
							nameOf(item.getUserProperties().get(ZosProperties.LANGUAGE_DEFINITION)));
				}
				String codePage = item.getUserProperties().get(ZosProperties.MVS_CODE_PAGE);
				if (codePage != null) {
					increment(codePages, codePage);
				}
				increment(encodings, String.valueOf(item.getEncoding()));
				increment(contentTypes, String.valueOf(item.getContentType()));
				String parent = parent(item.getPath());
				String language = item.getUserProperties().get(ZosProperties.LANGUAGE_DEFINITION);
				boolean inZFolder = folderDataSets.containsKey(parent);
				if (language != null) {
					increment(byLanguage, nameOf(language));
					if (!inZFolder) {
						languageOutsideZFolder.add(qualifiedPath);
					}
				} else if (inZFolder) {
					membersWithoutLanguage.add(qualifiedPath);
				}
				if (inZFolder) {
					@SuppressWarnings("unchecked")
					Map<String, Integer> languages = (Map<String, Integer>) ((Map<String, Object>) zFolders
							.get(component.getKey() + ":" + parent)).get("languageDefinitions");
					increment(languages, language == null ? "(none)" : nameOf(language));
				}
			}
		}
		Map<String, Object> summary = new LinkedHashMap<String, Object>();
		summary.put("files", Integer.valueOf(files));
		summary.put("folders", Integer.valueOf(folders));
		summary.put("zFolders", Integer.valueOf(zFolders.size()));
		summary.put("filesByLanguageDefinition", byLanguage);
		summary.put("zFoldersByDataSetDefinition", byDataSet);
		summary.put("filesByEncoding", encodings);
		summary.put("filesByContentType", contentTypes);
		summary.put("userPropertyKeys", propertyKeys);
		summary.put("buildVariableOverrides", overrides);
		summary.put("filesByMvsCodePage", codePages);
		summary.put("zFolderLanguages", zFolders);
		summary.put("membersWithoutLanguageDefinition", membersWithoutLanguage);
		summary.put("languageDefinitionOutsideZFolder", languageOutsideZFolder);
		summary.put("unresolvedDefinitions", Integer.valueOf(unresolved.size()));
		return summary;
	}

	/**
	 * Per variable: how many files override it, with which values, and in which files (with their language
	 * definition, as the variable belongs to that definition's translators).
	 */
	@SuppressWarnings("unchecked")
	private static void addOverride(Map<String, Map<String, Object>> overrides, String variable, String value,
			String file, String language) {
		Map<String, Object> entry = overrides.get(variable);
		if (entry == null) {
			entry = new LinkedHashMap<String, Object>();
			entry.put("files", Integer.valueOf(0));
			entry.put("values", new TreeMap<String, Integer>());
			entry.put("overriddenIn", new TreeMap<String, String>());
			overrides.put(variable, entry);
		}
		entry.put("files", Integer.valueOf(((Integer) entry.get("files")).intValue() + 1));
		increment((Map<String, Integer>) entry.get("values"), value);
		((Map<String, String>) entry.get("overriddenIn")).put(file, language == null ? "(none)" : language);
	}

	private static String parent(String path) {
		int slash = path.lastIndexOf('/');
		return slash < 0 ? "" : path.substring(0, slash);
	}

	private static void increment(Map<String, Integer> counts, String key) {
		Integer count = counts.get(key);
		counts.put(key, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
	}

	private static List<FileProperties> sorted(Collection<FileProperties> items) {
		List<FileProperties> list = new ArrayList<FileProperties>(items);
		list.sort((a, b) -> a.getPath().compareTo(b.getPath()));
		return list;
	}
}
