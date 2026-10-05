package to.rtc.cli.migrate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class ResumeAnalysisTest {
	private final Map<String, List<String>> history = new HashMap<String, List<String>>();
	private final Set<String> migrated = new HashSet<String>();
	private final Map<String, String> base = new HashMap<String, String>();

	@Test
	public void testNothingPendingWhenNewestIsCommitted() {
		history.put("db", Arrays.asList("initial", "cs1", "cs2"));
		migrated.addAll(Arrays.asList("cs1", "cs2"));

		assertTrue(analyse().getPending().isEmpty());
	}

	@Test
	public void testAcceptedButNotCommittedIsPending() {
		history.put("db", Arrays.asList("initial", "cs1", "cs2"));
		history.put("ui", Arrays.asList("initial-ui", "u1"));
		migrated.addAll(Arrays.asList("cs1", "u1"));

		assertEquals(Arrays.asList("cs2"), analyse().getPending());
	}

	@Test
	public void testComponentWithoutCommitUsesRecordedStart() {
		history.put("mobile", Arrays.asList("initial-m", "m1"));
		base.put("mobile", "initial-m");

		assertEquals(Arrays.asList("m1"), analyse().getPending());
	}

	@Test
	public void testComponentThatStartedEmpty() {
		history.put("new", Arrays.asList("n1"));
		base.put("new", "");

		assertEquals(Arrays.asList("n1"), analyse().getPending());
	}

	@Test
	public void testComponentWithoutReferencePointIsReported() {
		history.put("unknown", Arrays.asList("x1"));
		history.put("empty", Collections.<String> emptyList());

		ResumeAnalysis analysis = analyse();

		assertTrue(analysis.getPending().isEmpty());
		assertEquals(Collections.singleton("unknown"), analysis.getUndeterminedComponents());
	}

	private ResumeAnalysis analyse() {
		return ResumeAnalysis.analyse(history, migrated, base);
	}
}
