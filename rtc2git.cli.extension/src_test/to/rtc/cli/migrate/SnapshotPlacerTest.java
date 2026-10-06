package to.rtc.cli.migrate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class SnapshotPlacerTest {

	@Test
	public void testSnapshotWaitsForItsLastBaseline() {
		SnapshotTag snapshot = snapshot("s1", "bl-a", "bl-b");
		SnapshotPlacer placer = new SnapshotPlacer(Arrays.asList(snapshot), Arrays.asList("bl-a", "bl-b", "bl-c"));

		assertTrue(placer.reached(Collections.<String> emptySet()).isEmpty());
		assertTrue(placer.reached(Arrays.asList("bl-a")).isEmpty());
		assertEquals(Arrays.asList(snapshot), placer.reached(Arrays.asList("bl-b", "bl-c")));
		assertTrue(placer.getWaiting().isEmpty());
	}

	@Test
	public void testBaselinesOutsideTheMigrationDoNotBlock() {
		SnapshotTag snapshot = snapshot("s1", "initial-baseline", "bl-a");
		SnapshotPlacer placer = new SnapshotPlacer(Arrays.asList(snapshot), Arrays.asList("bl-a"));

		assertEquals(Arrays.asList(snapshot), placer.reached(Arrays.asList("bl-a")));
	}

	@Test
	public void testSnapshotOfTheStartStateIsTaggedFirst() {
		SnapshotTag snapshot = snapshot("s0", "old-1", "old-2");
		SnapshotPlacer placer = new SnapshotPlacer(Arrays.asList(snapshot), Arrays.asList("bl-a"));

		assertEquals(Arrays.asList(snapshot), placer.reached(Collections.<String> emptySet()));
	}

	@Test
	public void testUnreachedSnapshotsAreReported() {
		SnapshotTag snapshot = snapshot("s1", "bl-a", "bl-z");
		SnapshotPlacer placer = new SnapshotPlacer(Arrays.asList(snapshot), Arrays.asList("bl-a", "bl-z"));

		List<SnapshotTag> tagged = placer.reached(Arrays.asList("bl-a"));

		assertTrue(tagged.isEmpty());
		assertEquals(Arrays.asList(snapshot), placer.getWaiting());
	}

	private static SnapshotTag snapshot(String uuid, String... baselines) {
		return new SnapshotTag(uuid, "Release " + uuid, "snapshot/", 1000L, Arrays.asList(baselines));
	}
}
