package to.rtc.cli.migrate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class LoggingPrintStreamTest {

	private final LoggingPrintStream stream = new LoggingPrintStream(new ByteArrayOutputStream());

	@Test
	public void testEndsWithNewLineLooksAtLastWrittenByte() {
		byte[] bytes = "ab\ncd".getBytes(StandardCharsets.US_ASCII);

		assertTrue(stream.endsWithNewLine(bytes, 0, 3));
		assertFalse(stream.endsWithNewLine(bytes, 0, 2));
		assertFalse(stream.endsWithNewLine(bytes, 3, 2));
		assertFalse(stream.endsWithNewLine(bytes, 0, 0));
	}

	@Test
	public void testTimestampOnlyAtLineStart() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LoggingPrintStream logging = new LoggingPrintStream(out);
		byte[] first = "one\n".getBytes(StandardCharsets.US_ASCII);
		byte[] second = "two".getBytes(StandardCharsets.US_ASCII);

		logging.write(first, 0, first.length);
		logging.write(second, 0, second.length);
		logging.write(second, 0, second.length);
		logging.flush();

		String[] lines = out.toString().split("\n");
		assertEquals(2, lines.length);
		assertTrue(lines[0].matches("^\\[[0-9-]+ [0-9:]+\\] one$"));
		assertTrue(lines[1].matches("^\\[[0-9-]+ [0-9:]+\\] twotwo$"));
	}
}
