package to.rtc.cli.migrate.zos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import to.rtc.cli.migrate.FileProperties;
import to.rtc.cli.migrate.FileProperties.LineDelimiter;

public class ZosInventoryTest {
	private static final String COBOL = "_cobol";
	private static final String COPYBOOK = "_copy";
	private static final String DELETED = "_deleted";
	private static final String COBOL_DSD = "_dsCobol";
	private static final String COPY_DSD = "_dsCopy";

	private static final SystemDefinitions RESOLVER = new SystemDefinitions() {
		@Override
		public String getUnavailableReason() {
			return null;
		}

		@Override
		public Map<String, ZosDefinition> resolve(Collection<String> languages, Collection<String> dataSets) {
			Map<String, ZosDefinition> known = new HashMap<String, ZosDefinition>();
			known.put(COBOL, ZosDefinition.language(COBOL, "COBOL compile").languageCode("COB").build());
			known.put(COPYBOOK, ZosDefinition.language(COPYBOOK, "Copybook").languageCode("COB").build());
			known.put(COBOL_DSD,
					ZosDefinition.dataSet(COBOL_DSD, "COBOL sources").dataSet("COBOL", "", true, 0, 0).build());
			known.put(COPY_DSD, ZosDefinition.dataSet(COPY_DSD, "Copybooks").dataSet("COPY", "", true, 0, 0).build());
			Map<String, ZosDefinition> result = new HashMap<String, ZosDefinition>();
			for (String uuid : languages) {
				if (known.containsKey(uuid)) {
					result.put(uuid, known.get(uuid));
				}
			}
			for (String uuid : dataSets) {
				if (known.containsKey(uuid)) {
					result.put(uuid, known.get(uuid));
				}
			}
			return result;
		}
	};

	private static FileProperties folder(String path, String dataSet) {
		return FileProperties.folder("f:" + path, path, dataSet == null ? Collections.<String, String> emptyMap()
				: Collections.singletonMap(ZosProperties.RESOURCE_DEFINITION, dataSet));
	}

	private static FileProperties member(String path, String language) {
		return FileProperties.file("i:" + path, path, LineDelimiter.LF, "text/plain", "UTF-8", false,
				language == null ? Collections.<String, String> emptyMap()
						: Collections.singletonMap(ZosProperties.LANGUAGE_DEFINITION, language));
	}

	private static Map<String, List<FileProperties>> mortgage() {
		Map<String, List<FileProperties>> components = new LinkedHashMap<String, List<FileProperties>>();
		components.put("Mortgage", Arrays.asList(folder("App", null), member("App/.project", null),
				folder("App/zOSsrc", null), folder("App/zOSsrc/COBOL", COBOL_DSD),
				member("App/zOSsrc/COBOL/A.cbl", COBOL), member("App/zOSsrc/COBOL/B.cbl", COBOL),
				member("App/zOSsrc/COBOL/OLD.cbl", DELETED), member("App/zOSsrc/COBOL/NOLANG.cbl", null),
				folder("App/zOSsrc/COPYBOOK", COPY_DSD), member("App/zOSsrc/COPYBOOK/C.cpy", COPYBOOK),
				member("App/stray.cpy", COPYBOOK)));
		return components;
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testSummaryCountsAssignments() {
		ZosInventory inventory = new ZosInventory(mortgage(), RESOLVER);
		Map<String, Object> summary = inventory.summary();

		assertEquals(Integer.valueOf(7), summary.get("files"));
		assertEquals(Integer.valueOf(4), summary.get("folders"));
		assertEquals(Integer.valueOf(2), summary.get("zFolders"));
		Map<String, Integer> byLanguage = (Map<String, Integer>) summary.get("filesByLanguageDefinition");
		assertEquals(Integer.valueOf(2), byLanguage.get("COBOL compile"));
		assertEquals(Integer.valueOf(2), byLanguage.get("Copybook"));
		assertEquals(Integer.valueOf(1), byLanguage.get(DELETED)); // unresolved: shown by UUID
		Map<String, Integer> byDataSet = (Map<String, Integer>) summary.get("zFoldersByDataSetDefinition");
		assertEquals(Integer.valueOf(1), byDataSet.get("COBOL sources"));
		assertEquals(Integer.valueOf(1), summary.get("unresolvedDefinitions"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testReportsInconsistentAssignments() {
		Map<String, Object> summary = new ZosInventory(mortgage(), RESOLVER).summary();

		assertEquals(Arrays.asList("Mortgage:App/zOSsrc/COBOL/NOLANG.cbl"),
				summary.get("membersWithoutLanguageDefinition"));
		assertEquals(Arrays.asList("Mortgage:App/stray.cpy"), summary.get("languageDefinitionOutsideZFolder"));
		Map<String, Object> zFolder = (Map<String, Object>) ((Map<String, Object>) summary.get("zFolderLanguages"))
				.get("Mortgage:App/zOSsrc/COBOL");
		assertEquals("COBOL sources", zFolder.get("dataSetDefinition"));
		Map<String, Integer> languages = (Map<String, Integer>) zFolder.get("languageDefinitions");
		assertEquals(Integer.valueOf(2), languages.get("COBOL compile"));
		assertEquals(Integer.valueOf(1), languages.get("(none)"));
		assertEquals(Integer.valueOf(1), languages.get(DELETED));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testItemsCarryResolvedNames() {
		Map<String, Object> document = new ZosInventory(mortgage(), RESOLVER).toMap();

		assertEquals("resolved", document.get("systemDefinitions"));
		List<Object> components = (List<Object>) document.get("components");
		List<Object> items = (List<Object>) ((Map<String, Object>) components.get(0)).get("items");
		Map<String, Object> member = null;
		for (Object item : items) {
			if ("App/zOSsrc/COBOL/A.cbl".equals(((Map<String, Object>) item).get("path"))) {
				member = (Map<String, Object>) item;
			}
		}
		assertEquals("COBOL compile", member.get("languageDefinition"));
		assertEquals("UTF-8", member.get("encoding"));
		assertEquals(Arrays.asList(DELETED), document.get("unresolvedDefinitions"));
		assertTrue(((Map<String, Object>) document.get("definitions")).containsKey(COBOL_DSD));
	}

	@Test
	public void testWithoutEnterpriseExtensionsKeepsUuids() {
		ZosInventory inventory = new ZosInventory(mortgage(), SystemDefinitions.unavailable("no EE"));

		assertEquals("unavailable: no EE", inventory.toMap().get("systemDefinitions"));
		assertEquals(COBOL, inventory.nameOf(COBOL));
		assertEquals(5, inventory.getUnresolved().size());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testReportsFileLevelVariableOverridesAndCodePages() {
		Map<String, String> special = new HashMap<String, String>();
		special.put(ZosProperties.LANGUAGE_DEFINITION, COBOL);
		special.put(ZosProperties.BUILD_VARIABLE_PREFIX + "CBLCMPOPTS", "LIB,RENT,NODYNAM");
		special.put(ZosProperties.MVS_CODE_PAGE, "IBM-037");
		Map<String, String> other = new HashMap<String, String>();
		other.put(ZosProperties.LANGUAGE_DEFINITION, COBOL);
		other.put(ZosProperties.BUILD_VARIABLE_PREFIX + "CBLCMPOPTS", "LIB,RENT");
		Map<String, List<FileProperties>> components = mortgage();
		List<FileProperties> items = new java.util.ArrayList<FileProperties>(components.get("Mortgage"));
		items.add(FileProperties.file("i:S", "App/zOSsrc/COBOL/SPECIAL.cbl", LineDelimiter.LF, "text/plain", "UTF-8",
				false, special));
		items.add(FileProperties.file("i:O", "App/zOSsrc/COBOL/OTHER.cbl", LineDelimiter.LF, "text/plain", "UTF-8",
				false, other));
		components.put("Mortgage", items);

		ZosInventory inventory = new ZosInventory(components, RESOLVER);
		Map<String, Object> summary = inventory.summary();

		Map<String, Object> override = ((Map<String, Map<String, Object>>) summary.get("buildVariableOverrides"))
				.get("CBLCMPOPTS");
		assertEquals(Integer.valueOf(2), override.get("files"));
		assertEquals(2, ((Map<String, Integer>) override.get("values")).size());
		assertEquals("COBOL compile",
				((Map<String, String>) override.get("overriddenIn")).get("Mortgage:App/zOSsrc/COBOL/SPECIAL.cbl"));
		assertEquals(Collections.singletonMap("IBM-037", Integer.valueOf(1)), summary.get("filesByMvsCodePage"));
	}

	@Test
	public void testBuildVariables() {
		Map<String, String> properties = new HashMap<String, String>();
		properties.put(ZosProperties.BUILD_VARIABLE_PREFIX + "PARM", "LIST,MAP");
		properties.put(ZosProperties.LANGUAGE_DEFINITION, COBOL);

		Map<String, String> variables = ZosProperties.buildVariables(properties);

		assertEquals(Collections.singletonMap("PARM", "LIST,MAP"), variables);
		assertFalse(variables.containsKey(ZosProperties.LANGUAGE_DEFINITION));
	}
}
