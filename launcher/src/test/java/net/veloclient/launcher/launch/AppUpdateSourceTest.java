package net.veloclient.launcher.launch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppUpdateSourceTest {

	@TempDir
	Path root;

	/** An unpacked version folder like install() leaves it, plus current.txt pointing at it. */
	private Path stage(String version, String repo) throws Exception {
		Path dir = Files.createDirectories(root.resolve(version));
		Files.writeString(dir.resolve("launcher.jar"), "jar");
		if (repo != null) {
			Files.writeString(dir.resolve(AppUpdate.SOURCE), "repo=" + repo + "\nversion=" + version + "\n", StandardCharsets.UTF_8);
		}
		Files.writeString(root.resolve("current.txt"), version);
		return dir;
	}

	@Test
	void forkSourceIsTrusted() throws Exception {
		Path dir = Files.createDirectories(root.resolve("1.4.5"));
		AppUpdate.writeSource(dir, "1.4.5"); // what install() writes
		Files.writeString(root.resolve("current.txt"), "1.4.5");
		assertTrue(AppUpdate.trusted(dir));
		assertEquals(new AppUpdate.StagedVersion(AppUpdate.Staged.READY, "1.4.5"), AppUpdate.staged(root, "1.4.4"));
	}

	@Test
	void upstreamSourceIsUntrusted() throws Exception {
		stage("1.4.5", "velo345/velo-client");
		assertEquals(AppUpdate.Staged.UNTRUSTED, AppUpdate.staged(root, "1.4.4").state());
	}

	@Test
	void unmarkedSourceIsUntrusted() throws Exception {
		stage("1.4.5", null); // unpacked by a launcher from before the marker
		assertEquals(AppUpdate.Staged.UNTRUSTED, AppUpdate.staged(root, "1.4.4").state());
	}

	@Test
	void garbledMarkerIsUntrusted() throws Exception {
		Path dir = stage("1.4.5", null);
		Files.writeString(dir.resolve(AppUpdate.SOURCE), "repo=\\uZZZZ"); // malformed escape
		assertEquals(AppUpdate.Staged.UNTRUSTED, AppUpdate.staged(root, "1.4.4").state());
	}

	@Test
	void notNewerIsIgnoredEvenFromTheFork() throws Exception {
		stage("1.4.4", UpdateService.REPO);
		assertEquals(AppUpdate.Staged.NOT_NEWER, AppUpdate.staged(root, "1.4.4").state());
		stage("1.4.3", UpdateService.REPO);
		assertEquals(AppUpdate.Staged.NOT_NEWER, AppUpdate.staged(root, "1.4.4").state());
	}

	@Test
	void nothingStaged() throws Exception {
		assertEquals(AppUpdate.Staged.NONE, AppUpdate.staged(root, "1.4.4").state());
	}

	@Test
	void laterForkDownloadIsPickedUpOverAnUntrustedOne() throws Exception {
		stage("1.4.6", "velo345/velo-client");
		assertEquals(AppUpdate.Staged.UNTRUSTED, AppUpdate.staged(root, "1.4.4").state());
		stage("1.4.5", UpdateService.REPO); // install() rewrites current.txt to the fork's version
		assertEquals(new AppUpdate.StagedVersion(AppUpdate.Staged.READY, "1.4.5"), AppUpdate.staged(root, "1.4.4"));
		assertTrue(Files.isDirectory(root.resolve("1.4.6")), "the untrusted folder is left alone");
	}

	@Test
	void cleanUpKeepsUntrustedFolders() throws Exception {
		Path upstream = stage("1.4.6", "velo345/velo-client");
		Path unmarked = stage("1.4.7", null);
		Path oldFork = stage("1.4.3", UpdateService.REPO);
		Path half = Files.createDirectories(root.resolve("1.4.8.unpacking"));
		Path keep = stage("1.4.5", UpdateService.REPO);
		AppUpdate.cleanUp(root, "1.4.5");
		assertTrue(Files.isDirectory(upstream));
		assertTrue(Files.isDirectory(unmarked));
		assertTrue(Files.isDirectory(keep));
		assertFalse(Files.exists(oldFork));
		assertFalse(Files.exists(half));
	}
}
