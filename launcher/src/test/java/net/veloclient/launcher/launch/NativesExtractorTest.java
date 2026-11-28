package net.veloclient.launcher.launch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativesExtractorTest {

	private static Path natives(Path root) throws IOException {
		Path dir = root.resolve("natives");
		Files.createDirectories(dir.resolve("sub"));
		Files.writeString(dir.resolve("lwjgl.dll"), "x");
		Files.writeString(dir.resolve("sub/other.dll"), "y");
		return dir;
	}

	@Test
	void emptiesTheFolder(@TempDir Path root) throws IOException {
		Path dir = natives(root);
		assertTrue(NativesExtractor.clearDir(dir, 3, 0, Files::deleteIfExists));
		assertFalse(Files.exists(dir.resolve("lwjgl.dll")));
		assertFalse(Files.exists(dir.resolve("sub")));
	}

	@Test
	void aDllHeldForAMomentIsRetried(@TempDir Path root) throws IOException {
		Path dir = natives(root);
		AtomicInteger refusals = new AtomicInteger(2);
		boolean ok = NativesExtractor.clearDir(dir, 5, 0, path -> {
			if (path.getFileName().toString().equals("lwjgl.dll") && refusals.getAndDecrement() > 0) {
				throw new AccessDeniedException(path.toString());
			}
			Files.deleteIfExists(path);
		});
		assertTrue(ok);
		assertFalse(Files.exists(dir), "the folder is emptied and removed");
	}

	@Test
	void aDllHeldForGoodReportsFailureInsteadOfThrowing(@TempDir Path root) throws IOException {
		Path dir = natives(root);
		boolean ok = NativesExtractor.clearDir(dir, 3, 0, path -> {
			if (path.getFileName().toString().equals("lwjgl.dll")) {
				throw new AccessDeniedException(path.toString());
			}
			Files.deleteIfExists(path);
		});
		assertFalse(ok);
		assertTrue(Files.exists(dir.resolve("lwjgl.dll")), "the locked file is left alone");
		assertFalse(Files.exists(dir.resolve("sub")), "everything else was still cleaned");
	}

	@Test
	void missingFolderIsFine(@TempDir Path root) {
		assertTrue(NativesExtractor.clearDir(root.resolve("nope"), 3, 0, Files::deleteIfExists));
	}
}
