package to.rtc.cli.migrate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class BranchPointTest {
	private final Map<String, List<BranchPoint.Commit>> branches = new LinkedHashMap<String, List<BranchPoint.Commit>>();
	private final Map<String, List<String>> stream = new HashMap<String, List<String>>();

	private static List<BranchPoint.Commit> branch(Map<String, String> base, String... changeSets) {
		List<BranchPoint.Commit> commits = new ArrayList<BranchPoint.Commit>();
		commits.add(new BranchPoint.Commit("root", null, base));
		for (String changeSet : changeSets) {
			commits.add(new BranchPoint.Commit(changeSet == null ? "generated" : "c-" + changeSet, changeSet, null));
		}
		return commits;
	}

	private static Map<String, String> base(String... componentAndChangeSet) {
		Map<String, String> base = new HashMap<String, String>();
		for (int i = 0; i < componentAndChangeSet.length; i += 2) {
			base.put(componentAndChangeSet[i], componentAndChangeSet[i + 1]);
		}
		return base;
	}

	@Test
	public void testStreamCopiedFromTheMiddleOfABranch() {
		branches.put("main", branch(base("db", "i"), "a", "b", "c", "d"));
		stream.put("db", Arrays.asList("i", "a", "b", "x", "y"));

		BranchPoint point = BranchPoint.find(branches, stream);

		assertEquals("main", point.getBranch());
		assertEquals("c-b", point.getCommitId());
		assertEquals(2, point.getChangeSets());
		assertEquals(Arrays.asList("i", "a", "b"), point.getConfiguration().get("db"));
		assertTrue(point.getStop().contains("c is not in the stream"));
	}

	@Test
	public void testWholeBranchAndCommitsWithoutChangeSet() {
		branches.put("main", branch(base("db", "i"), "a", null, "b", null));
		stream.put("db", Arrays.asList("i", "a", "b", "x"));

		BranchPoint point = BranchPoint.find(branches, stream);

		assertEquals("generated", point.getCommitId());
		assertEquals(2, point.getChangeSets());
		assertNull(point.getStop());
	}

	@Test
	public void testComponentsAdvanceIndependently() {
		branches.put("main", branch(base("db", "i", "ui", "j"), "a", "u1", "b", "u2"));
		stream.put("db", Arrays.asList("i", "a", "b"));
		stream.put("ui", Arrays.asList("j", "u1", "u9"));

		BranchPoint point = BranchPoint.find(branches, stream);

		assertEquals("c-b", point.getCommitId());
		assertEquals(Arrays.asList("i", "a", "b"), point.getConfiguration().get("db"));
		assertEquals(Arrays.asList("j", "u1"), point.getConfiguration().get("ui"));
	}

	@Test
	public void testOtherDeliveryOrderStops() {
		branches.put("main", branch(base("db", "i"), "a", "b", "c"));
		stream.put("db", Arrays.asList("i", "b", "a", "c"));

		BranchPoint point = BranchPoint.find(branches, stream);

		assertEquals("root", point.getCommitId());
		assertEquals(0, point.getChangeSets());
		assertEquals(Arrays.asList("i"), point.getConfiguration().get("db"));
		assertTrue(point.getStop().contains("another order"));
	}

	@Test
	public void testLongestBranchWins() {
		branches.put("main", branch(base("db", "i"), "a", "m1"));
		branches.put("release", branch(base("db", "i"), "a", "r1", "r2"));
		stream.put("db", Arrays.asList("i", "a", "r1", "r2", "h1"));

		BranchPoint point = BranchPoint.find(branches, stream);

		assertEquals("release", point.getBranch());
		assertEquals("c-r2", point.getCommitId());
		assertEquals(3, point.getChangeSets());
	}

	@Test
	public void testUnknownStartState() {
		branches.put("main", branch(base("db", "other"), "a"));
		stream.put("db", Arrays.asList("i", "a"));

		assertNull(BranchPoint.find(branches, stream));
	}

	@Test
	public void testComponentThatStartedEmpty() {
		branches.put("main", branch(base("db", ""), "a"));
		stream.put("db", Arrays.asList("a", "b"));

		BranchPoint point = BranchPoint.find(branches, stream);

		assertEquals("c-a", point.getCommitId());
		assertEquals(Arrays.asList("a"), point.getConfiguration().get("db"));
	}

	@Test
	public void testNoBranches() {
		stream.put("db", Collections.singletonList("i"));

		assertNull(BranchPoint.find(branches, stream));
	}
}
