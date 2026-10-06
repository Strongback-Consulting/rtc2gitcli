package to.rtc.cli.migrate.util;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/**
 * Minimal pretty-printing JSON writer for maps, collections, strings, numbers and booleans (keeps the bundle free of a
 * JSON library).
 */
public final class JsonWriter {

	private JsonWriter() {
	}

	public static void write(File file, Object value) throws IOException {
		try (Writer writer = new OutputStreamWriter(Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8)) {
			writer.write(toString(value));
		}
	}

	public static String toString(Object value) {
		java.io.StringWriter writer = new java.io.StringWriter();
		try {
			writeValue(writer, value, "");
		} catch (IOException e) {
			throw new IllegalStateException(e); // cannot happen with a StringWriter
		}
		return writer.append('\n').toString();
	}

	public static void writeValue(Writer writer, Object value, String indent) throws IOException {
		if (value == null) {
			writer.write("null");
		} else if (value instanceof Number || value instanceof Boolean) {
			writer.write(value.toString());
		} else if (value instanceof Map) {
			Iterator<? extends Map.Entry<?, ?>> it = ((Map<?, ?>) value).entrySet().iterator();
			if (!it.hasNext()) {
				writer.write("{}");
				return;
			}
			writer.write("{\n");
			while (it.hasNext()) {
				Map.Entry<?, ?> entry = it.next();
				writer.write(indent + "  ");
				writeString(writer, String.valueOf(entry.getKey()));
				writer.write(": ");
				writeValue(writer, entry.getValue(), indent + "  ");
				writer.write(it.hasNext() ? ",\n" : "\n");
			}
			writer.write(indent + "}");
		} else if (value instanceof Collection) {
			Iterator<?> it = ((Collection<?>) value).iterator();
			if (!it.hasNext()) {
				writer.write("[]");
				return;
			}
			writer.write("[\n");
			while (it.hasNext()) {
				writer.write(indent + "  ");
				writeValue(writer, it.next(), indent + "  ");
				writer.write(it.hasNext() ? ",\n" : "\n");
			}
			writer.write(indent + "]");
		} else {
			writeString(writer, value.toString());
		}
	}

	private static void writeString(Writer writer, String text) throws IOException {
		StringBuilder sb = new StringBuilder("\"");
		for (char c : text.toCharArray()) {
			switch (c) {
			case '"':
				sb.append("\\\"");
				break;
			case '\\':
				sb.append("\\\\");
				break;
			case '\n':
				sb.append("\\n");
				break;
			case '\r':
				sb.append("\\r");
				break;
			case '\t':
				sb.append("\\t");
				break;
			default:
				if (c < 0x20) {
					sb.append(String.format("\\u%04x", Integer.valueOf(c)));
				} else {
					sb.append(c);
				}
			}
		}
		writer.write(sb.append('"').toString());
	}
}
