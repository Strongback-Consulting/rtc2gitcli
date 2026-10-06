package to.rtc.cli.migrate.zos;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import to.rtc.cli.migrate.FileProperties;

/**
 * The z/OS metadata of one state of the sandbox, as written to <code>.ewm/zos-metadata.json</code> in every commit
 * where it changes: the data set definition of each zFolder and, for each member, its language definition, per-file
 * build variables, code page and build flags. This file is the hand-off to <code>ewm2zbuilder --app-yaml</code>.
 */
public final class ZosMetadata {
	public static final String PATH = ".ewm/zos-metadata.json";
	/** Code page of z/OS members without an <code>mvsCodePage</code> property, unless configured otherwise. */
	public static final String DEFAULT_CODE_PAGE = "IBM-1047";
	static final int FORMAT = 1;

	private ZosMetadata() {
	}

	/**
	 * @return the zFolder paths (folders with a data set definition)
	 */
	public static Set<String> zFolders(Collection<FileProperties> items) {
		Set<String> folders = new HashSet<String>();
		for (FileProperties item : items) {
			if (item.isFolder() && item.getUserProperties().containsKey(ZosProperties.RESOURCE_DEFINITION)) {
				folders.add(item.getPath());
			}
		}
		return folders;
	}

	/**
	 * @return whether the file is a z/OS member: it has a language definition or lies directly in a zFolder
	 */
	public static boolean isMember(FileProperties file, Set<String> zFolders) {
		if (file.isFolder()) {
			return false;
		}
		if (file.getUserProperties().containsKey(ZosProperties.LANGUAGE_DEFINITION)) {
			return true;
		}
		int slash = file.getPath().lastIndexOf('/');
		return slash > 0 && zFolders.contains(file.getPath().substring(0, slash));
	}

	/**
	 * @return the JSON-ready document, or <code>null</code> if no item carries z/OS metadata
	 */
	public static Map<String, Object> build(Collection<FileProperties> items, SystemDefinitions definitions,
			String defaultCodePage) {
		Set<String> zFolders = zFolders(items);
		Set<String> languageUuids = new TreeSet<String>();
		Set<String> dataSetUuids = new TreeSet<String>();
		for (FileProperties item : items) {
			addIfPresent(languageUuids, item.getUserProperties().get(ZosProperties.LANGUAGE_DEFINITION));
			addIfPresent(dataSetUuids, item.getUserProperties().get(ZosProperties.RESOURCE_DEFINITION));
		}
		if (languageUuids.isEmpty() && dataSetUuids.isEmpty()) {
			return null;
		}
		Map<String, ZosDefinition> resolved = definitions.resolve(languageUuids, dataSetUuids);

		Map<String, Object> folders = new TreeMap<String, Object>();
		Map<String, Object> members = new TreeMap<String, Object>();
		for (FileProperties item : items) {
			Map<String, String> properties = item.getUserProperties();
			if (item.isFolder()) {
				String dataSet = properties.get(ZosProperties.RESOURCE_DEFINITION);
				if (dataSet != null) {
					folders.put(item.getPath(), Collections.singletonMap("dataSetDefinition",
							reference(dataSet, resolved)));
				}
				continue;
			}
			if (!isMember(item, zFolders)) {
				continue;
			}
			Map<String, Object> member = new LinkedHashMap<String, Object>();
			String language = properties.get(ZosProperties.LANGUAGE_DEFINITION);
			member.put("languageDefinition", language == null ? null : reference(language, resolved));
			Map<String, String> variables = ZosProperties.buildVariables(properties);
			if (!variables.isEmpty()) {
				member.put("buildVariables", variables);
			}
			String codePage = properties.get(ZosProperties.MVS_CODE_PAGE);
			if (codePage != null) {
				member.put("mvsCodePage", codePage);
			}
			flag(member, "alwaysLoad", properties.get(ZosProperties.ALWAYS_LOAD));
			flag(member, "ignoreForDependencyBuild", properties.get(ZosProperties.IGNORE_FOR_DEPENDENCY_BUILD));
			if (isBinary(item)) {
				member.put("binary", Boolean.TRUE);
			}
			members.put(item.getPath(), member);
		}

		Map<String, Object> definitionMap = new TreeMap<String, Object>();
		for (ZosDefinition definition : resolved.values()) {
			definitionMap.put(definition.getUuid(), definition.toMap());
		}
		Map<String, Object> document = new LinkedHashMap<String, Object>();
		document.put("format", Integer.valueOf(FORMAT));
		document.put("defaultCodePage", defaultCodePage);
		document.put("zFolders", folders);
		document.put("members", members);
		document.put("definitions", definitionMap);
		return document;
	}

	/**
	 * @return the code page of a member: its own <code>mvsCodePage</code> or the default
	 */
	public static String codePage(FileProperties member, String defaultCodePage) {
		String own = member.getUserProperties().get(ZosProperties.MVS_CODE_PAGE);
		return own == null || own.trim().isEmpty() ? defaultCodePage : own.trim();
	}

	public static boolean isBinary(FileProperties file) {
		String contentType = file.getContentType();
		return contentType != null && !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/");
	}

	private static Map<String, Object> reference(String uuid, Map<String, ZosDefinition> resolved) {
		Map<String, Object> reference = new LinkedHashMap<String, Object>();
		ZosDefinition definition = resolved.get(uuid);
		reference.put("name", definition == null ? null : definition.getName());
		reference.put("uuid", uuid);
		return reference;
	}

	private static void flag(Map<String, Object> member, String name, String value) {
		if (value != null && Boolean.parseBoolean(value.trim())) {
			member.put(name, Boolean.TRUE);
		}
	}

	private static void addIfPresent(Set<String> set, String value) {
		if (value != null && !value.isEmpty()) {
			set.add(value);
		}
	}
}
