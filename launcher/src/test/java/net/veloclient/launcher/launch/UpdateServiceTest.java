package net.veloclient.launcher.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateServiceTest {

	// ---- repository ----

	@Test
	void updatesComeFromTheFork() {
		assertEquals("Shaan4854/velo-client", UpdateService.REPO);
		assertEquals("https://api.github.com/repos/Shaan4854/velo-client/releases?per_page=15", UpdateService.RELEASES);
		assertEquals("https://api.github.com/repos/Shaan4854/velo-client/releases/tags/", UpdateService.RELEASE_BY_TAG);
		assertEquals("https://github.com/Shaan4854/velo-client/releases/download/", UpdateService.GITHUB_DOWNLOADS);
		for (String url : new String[] {UpdateService.RELEASES, UpdateService.RELEASE_BY_TAG, UpdateService.GITHUB_DOWNLOADS}) {
			assertFalse(url.contains("velo345"), url);
		}
	}

	@Test
	void upstreamMirrorIsNeverUsed() {
		assertFalse(UpdateService.USE_VELO_MIRROR);
		assertNull(UpdateService.mirrorUrl(UpdateService.GITHUB_DOWNLOADS + "v1.4.4/velo-client-launcher_1.4.4_amd64.deb"));
		assertNull(UpdateService.mirrorUrl("https://github.com/velo345/velo-client/releases/download/v1.4.4/x.deb"));
	}

	// ---- version comparison ----

	@Test
	void compareIsNumeric() {
		assertTrue(UpdateService.compare("1.4.4", "1.4.3") > 0);
		assertTrue(UpdateService.compare("1.4.10", "1.4.9") > 0);
		assertTrue(UpdateService.compare("1.5", "1.4.9") > 0);
		assertTrue(UpdateService.compare("1.4.2", "1.4.3") < 0);
		assertEquals(0, UpdateService.compare("1.4.3", "1.4.3"));
		assertEquals(0, UpdateService.compare("1.4", "1.4.0"));
	}

	@Test
	void noSelfOfferForEqualOrOlder() {
		assertFalse(UpdateService.offersLauncher("1.4.3", "1.4.3"));
		assertFalse(UpdateService.offersLauncher("1.4.2", "1.4.3"));
		assertFalse(UpdateService.offersLauncher("1.4.3", "1.4.4"));
		assertTrue(UpdateService.offersLauncher("1.4.4", "1.4.3"));
	}

	@Test
	void tagDropsVPrefixAndTestPrefix() {
		assertEquals("1.4.3", UpdateService.tag(release("v1.4.3", false, false)));
		assertEquals("1.4.3", UpdateService.tag(release("1.4.3", false, false)));
		assertEquals("1.2.4", UpdateService.tag(release("updatetest-1.2.4", true, false)));
	}

	// ---- release selection ----

	private static JsonObject release(String tag, boolean prerelease, boolean draft) {
		JsonObject o = new JsonObject();
		o.addProperty("tag_name", tag);
		o.addProperty("prerelease", prerelease);
		o.addProperty("draft", draft);
		o.addProperty("html_url", "https://github.com/" + UpdateService.REPO + "/releases/tag/" + tag);
		o.add("assets", new JsonArray());
		return o;
	}

	private static JsonArray list(JsonObject... releases) {
		JsonArray a = new JsonArray();
		for (JsonObject r : releases) {
			a.add(r);
		}
		return a;
	}

	@Test
	void normalReleaseIsPicked() {
		JsonObject picked = UpdateService.pickRelease(list(release("v1.4.3", false, false)), false);
		assertNotNull(picked);
		assertEquals("v1.4.3", picked.get("tag_name").getAsString());
	}

	@Test
	void preAndNormalReleasesCompeteByVersion() {
		JsonArray releases = list(release("v1.4.3", false, false), release("v1.4.4", true, false), release("v1.4.2", true, false));
		assertEquals("v1.4.4", UpdateService.pickRelease(releases, false).get("tag_name").getAsString());
		JsonArray releases2 = list(release("v1.4.3", true, false), release("v1.4.4", false, false));
		assertEquals("v1.4.4", UpdateService.pickRelease(releases2, false).get("tag_name").getAsString());
	}

	@Test
	void draftsAndTestReleasesAreSkipped() {
		JsonArray releases = list(release("v1.4.3", false, false), release("v1.9.0", false, true), release("updatetest-2.0.0", true, false));
		assertEquals("v1.4.3", UpdateService.pickRelease(releases, false).get("tag_name").getAsString());
		assertEquals("updatetest-2.0.0", UpdateService.pickRelease(releases, true).get("tag_name").getAsString());
		assertNull(UpdateService.pickRelease(list(release("v1.9.0", false, true)), false));
	}

	// ---- the fork's real release JSON (gh api repos/Shaan4854/velo-client/releases, trimmed) ----

	private static JsonArray forkReleases() throws Exception {
		try (InputStream in = UpdateServiceTest.class.getResourceAsStream("fork-releases-v1.4.3.json")) {
			assertNotNull(in, "fixture missing");
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
		}
	}

	private static List<UpdateService.Asset> assets(JsonObject release) {
		List<UpdateService.Asset> out = new ArrayList<>();
		for (JsonElement e : release.getAsJsonArray("assets")) {
			JsonObject a = e.getAsJsonObject();
			out.add(new UpdateService.Asset(a.get("name").getAsString(), a.get("browser_download_url").getAsString(), a.get("size").getAsLong(),
					null, 0, a.get("id").getAsLong()));
		}
		return out;
	}

	@Test
	void forkV143IsPickedButNotOfferedToItself() throws Exception {
		JsonObject picked = UpdateService.pickRelease(forkReleases(), false);
		assertNotNull(picked);
		assertFalse(picked.get("prerelease").getAsBoolean()); // a normal release on the fork
		String version = UpdateService.tag(picked);
		assertEquals("1.4.3", version);
		assertFalse(UpdateService.offersLauncher(version, "1.4.3"));
		assertFalse(UpdateService.offersLauncher(version, "1.4.4"));
		assertTrue(UpdateService.offersLauncher(version, "1.4.2"));
		for (UpdateService.Asset a : assets(picked)) {
			assertTrue(a.url().startsWith(UpdateService.GITHUB_DOWNLOADS + "v1.4.3/"), a.url());
		}
	}

	@Test
	void forkAssetNamesMatchWhatTheUpdaterLooksFor() throws Exception {
		List<UpdateService.Asset> assets = assets(UpdateService.pickRelease(forkReleases(), false));
		List<String> installers = new ArrayList<>();
		for (UpdateService.Asset a : assets) {
			if (UpdateService.isInstaller(a.name(), "linux", true, null)) {
				installers.add(a.name());
			}
		}
		assertEquals(List.of("velo-client-launcher_1.4.3_amd64.deb"), installers);
		long dmgs = assets.stream().filter(a -> UpdateService.isInstaller(a.name(), "mac os x", false, "-macos-arm64.dmg")).count();
		assertEquals(1, dmgs);

		String os = System.getProperty("os.name");
		String arch = System.getProperty("os.arch");
		try {
			System.setProperty("os.name", "Linux");
			assertEquals("velo-client-launcher-1.4.3-app-linux-x64.zip", AppUpdate.pick(assets, "1.4.3").name());
			System.setProperty("os.name", "Mac OS X");
			System.setProperty("os.arch", "aarch64");
			assertEquals("velo-client-launcher-1.4.3-app-macos-arm64.zip", AppUpdate.pick(assets, "1.4.3").name());
		} finally {
			System.setProperty("os.name", os);
			System.setProperty("os.arch", arch);
		}
	}

	// ---- mod jars: the fork's releases carry none ----

	@Test
	void forkReleaseWithoutJarsOffersNoModUpdate() throws Exception {
		JsonObject picked = UpdateService.pickRelease(forkReleases(), false);
		List<UpdateService.Asset> assets = assets(picked);
		assertTrue(assets.stream().noneMatch(a -> a.name().endsWith(".jar")), "fixture should have no jars");
		// AppVersion in unit tests is the fallback 0.5.3, so 1.4.3 counts as newer - the strictest case
		assertTrue(UpdateService.modJars(assets, "1.4.3").isEmpty());
		UpdateService.Release release = new UpdateService.Release("1.4.3", "", "", false, null, UpdateService.modJars(assets, "1.4.3"));
		assertFalse(release.anything()); // "You're up to date", no popup, no download
	}

	@Test
	void upstreamStyleJarNamesWouldBeMatched() {
		// Control: the names the updater looks for are velo-client-<version>+<mc>.jar (e.g. upstream's v1.4.2 assets)
		List<UpdateService.Asset> assets = List.of(
				new UpdateService.Asset("velo-client-1.4.3+26.2.jar", UpdateService.GITHUB_DOWNLOADS + "v1.4.3/velo-client-1.4.3+26.2.jar", 10, null, 0, 1),
				new UpdateService.Asset("velo-client-1.2.0+mc1.8.9.jar", "x", 10, null, 0, 2),
				new UpdateService.Asset("velo-server.jar", "x", 10, null, 0, 3));
		var mods = UpdateService.modJars(assets, "1.4.3");
		assertEquals(List.of("26.2"), List.copyOf(mods.keySet()));
	}
}
