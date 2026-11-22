package net.veloclient.launcher.launch;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameJarsCacheTest {

	private static Properties cache(String version, String repo) {
		Properties p = new Properties();
		p.setProperty("version", version);
		if (repo != null) {
			p.setProperty("repo", repo);
		}
		return p;
	}

	@Test
	void forkCacheOfThisOrNewerVersionIsUsed() {
		assertTrue(GameJars.cacheIsCurrent(cache("1.4.4", UpdateService.REPO), "1.4.4"));
		assertTrue(GameJars.cacheIsCurrent(cache("1.4.5", UpdateService.REPO), "1.4.4"));
	}

	@Test
	void olderCacheFallsBackToBundled() {
		assertFalse(GameJars.cacheIsCurrent(cache("1.4.3", UpdateService.REPO), "1.4.4"));
	}

	@Test
	void upstreamOrUnknownCacheFallsBackToBundled() {
		// jars an older launcher fetched from velo345 (no "repo" key before this fork) never override the bundled mod
		assertFalse(GameJars.cacheIsCurrent(cache("1.9.0", null), "1.4.4"));
		assertFalse(GameJars.cacheIsCurrent(cache("1.9.0", "velo345/velo-client"), "1.4.4"));
	}
}
