package to.rtc.cli.migrate.zos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A language or data set definition of EWM Enterprise Extensions, copied out of the EE model so that the rest of the
 * migration does not depend on the EE bundles.
 */
public final class ZosDefinition {

	public enum Kind {
		LANGUAGE, DATA_SET
	}

	private final Kind kind;
	private final String uuid;
	private final String name;
	private final String description;
	private final String projectArea;
	private final boolean archived;
	private final String languageCode;
	private final List<String> defaultPatterns;
	private final String dsName;
	private final String dsMember;
	private final boolean prefixDsn;
	private final int usageType;
	private final int dsType;
	private final String recordFormat;
	private final String recordLength;
	private final Map<String, String> properties;

	private ZosDefinition(Builder builder) {
		this.kind = builder.kind;
		this.uuid = builder.uuid;
		this.name = builder.name;
		this.description = builder.description;
		this.projectArea = builder.projectArea;
		this.archived = builder.archived;
		this.languageCode = builder.languageCode;
		this.defaultPatterns = Collections.unmodifiableList(new ArrayList<String>(builder.defaultPatterns));
		this.dsName = builder.dsName;
		this.dsMember = builder.dsMember;
		this.prefixDsn = builder.prefixDsn;
		this.usageType = builder.usageType;
		this.dsType = builder.dsType;
		this.recordFormat = builder.recordFormat;
		this.recordLength = builder.recordLength;
		this.properties = Collections.unmodifiableMap(new TreeMap<String, String>(builder.properties));
	}

	public static Builder language(String uuid, String name) {
		return new Builder(Kind.LANGUAGE, uuid, name);
	}

	public static Builder dataSet(String uuid, String name) {
		return new Builder(Kind.DATA_SET, uuid, name);
	}

	public Kind getKind() {
		return kind;
	}

	public String getUuid() {
		return uuid;
	}

	/**
	 * @return the name, which is the key of the definition in a system definition export
	 */
	public String getName() {
		return name;
	}

	public boolean isArchived() {
		return archived;
	}

	public String getLanguageCode() {
		return languageCode;
	}

	public List<String> getDefaultPatterns() {
		return defaultPatterns;
	}

	public String getDsName() {
		return dsName;
	}

	public boolean isPrefixDsn() {
		return prefixDsn;
	}

	public int getUsageType() {
		return usageType;
	}

	public Map<String, String> getProperties() {
		return properties;
	}

	/**
	 * @return the definition as JSON-ready map
	 */
	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<String, Object>();
		map.put("kind", kind == Kind.LANGUAGE ? "languageDefinition" : "dataSetDefinition");
		map.put("uuid", uuid);
		map.put("name", name);
		map.put("description", description);
		map.put("projectArea", projectArea);
		map.put("archived", Boolean.valueOf(archived));
		if (kind == Kind.LANGUAGE) {
			map.put("languageCode", languageCode);
			map.put("defaultPatterns", defaultPatterns);
		} else {
			map.put("dsName", dsName);
			map.put("dsMember", dsMember);
			map.put("prefixDsn", Boolean.valueOf(prefixDsn));
			map.put("usageType", Integer.valueOf(usageType));
			map.put("dsType", Integer.valueOf(dsType));
			map.put("recordFormat", recordFormat);
			map.put("recordLength", recordLength);
		}
		map.put("properties", properties);
		return map;
	}

	public static final class Builder {
		private final Kind kind;
		private final String uuid;
		private final String name;
		private String description;
		private String projectArea;
		private boolean archived;
		private String languageCode;
		private List<String> defaultPatterns = Collections.emptyList();
		private String dsName;
		private String dsMember;
		private boolean prefixDsn;
		private int usageType = -1;
		private int dsType = -1;
		private String recordFormat;
		private String recordLength;
		private Map<String, String> properties = Collections.emptyMap();

		private Builder(Kind kind, String uuid, String name) {
			this.kind = kind;
			this.uuid = uuid;
			this.name = name;
		}

		public Builder description(String value) {
			description = value;
			return this;
		}

		/**
		 * @param value
		 *            name of the project area that owns the definition (where to export it from)
		 */
		public Builder projectArea(String value) {
			projectArea = value;
			return this;
		}

		public Builder archived(boolean value) {
			archived = value;
			return this;
		}

		public Builder languageCode(String value) {
			languageCode = value;
			return this;
		}

		public Builder defaultPatterns(List<String> value) {
			defaultPatterns = value;
			return this;
		}

		public Builder dataSet(String name, String member, boolean prefix, int usage, int type) {
			dsName = name;
			dsMember = member;
			prefixDsn = prefix;
			usageType = usage;
			dsType = type;
			return this;
		}

		public Builder record(String format, String length) {
			recordFormat = format;
			recordLength = length;
			return this;
		}

		public Builder properties(Map<String, String> value) {
			properties = value == null ? Collections.<String, String> emptyMap() : value;
			return this;
		}

		public ZosDefinition build() {
			return new ZosDefinition(this);
		}
	}
}
