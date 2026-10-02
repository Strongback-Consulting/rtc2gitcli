package to.rtc.cli.migrate;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Date;

public class LoggingPrintStream extends PrintStream {
	private static final byte NEWLINE = (byte) '\n';

	private boolean afterNewLine;

	LoggingPrintStream(OutputStream stream) {
		super(stream);
		afterNewLine = true;
	}

	@Override
	public void write(byte[] bytes, int i, int j) {
		if (afterNewLine) {
			byte[] dateBytes = getDateTime();
			super.write(dateBytes, 0, dateBytes.length);
			afterNewLine = false;
		}
		super.write(bytes, i, j);
		if (endsWithNewLine(bytes, i, j)) {
			afterNewLine = true;
		}
	}

	boolean endsWithNewLine(byte[] bytes, int offset, int length) {
		return length > 0 && NEWLINE == bytes[offset + length - 1];
	}

	@Override
	public void println(String s) {
		super.println(s);
		afterNewLine = true;
	}

	@Override
	public void println() {
		super.println();
		afterNewLine = true;
	}

	@Override
	public void println(char[] ac) {
		super.println(ac);
		afterNewLine = true;
	}

	private byte[] getDateTime() {
		return String.format("[%1$tY-%1$tm-%1$td %1$tT] ", new Date()).getBytes();
	}
}
