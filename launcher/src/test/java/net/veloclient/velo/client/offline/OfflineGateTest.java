package net.veloclient.velo.client.offline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The mod's offline-cosmetics gate (OfflineGate.java, copied in by launcher/build.gradle). */
class OfflineGateTest {

	private static final String ID = "0123456789abcdef0123456789abcdef";

	@TempDir
	Path dir;

	@Test
	void onlyFlagTrueOfflineAndNoToken() {
		assertTrue(OfflineGate.decide("true", true, null));
		assertFalse(OfflineGate.decide(null, true, null)); // no flag: other launchers, Microsoft launches, toggle off
		assertFalse(OfflineGate.decide("TRUE", true, null));
		assertFalse(OfflineGate.decide("1", true, null));
		assertFalse(OfflineGate.decide("true", false, null)); // Microsoft session, or a dev run
		assertFalse(OfflineGate.decide("true", true, "velo-session-token")); // signed in to Velo Network
		assertFalse(OfflineGate.decide("true", false, "velo-session-token"));
	}

	@Test
	void skinIdsAreDashlessUuidsOnly() throws Exception {
		assertTrue(OfflineGate.validSkinId(ID));
		assertFalse(OfflineGate.validSkinId(null));
		assertFalse(OfflineGate.validSkinId("../../etc/passwd"));
		assertFalse(OfflineGate.validSkinId(ID.toUpperCase()));
		assertFalse(OfflineGate.validSkinId(ID + "0"));
		assertEquals(dir.toAbsolutePath().normalize().resolve(ID + ".png"), OfflineGate.safePng(dir, ID));
		assertNull(OfflineGate.safePng(dir, "../" + ID));
	}

	@Test
	void pngHeaderChecks() throws Exception {
		assertTrue(OfflineGate.validPng(Pngs.write(dir.resolve("a.png"), 64, 64, 0)));
		assertTrue(OfflineGate.validPng(Pngs.write(dir.resolve("b.png"), 64, 32, 0)));
		assertFalse(OfflineGate.validPng(Pngs.write(dir.resolve("c.png"), 128, 128, 0)));
		assertFalse(OfflineGate.validPng(Pngs.write(dir.resolve("d.png"), 64, 48, 0)));
		assertFalse(OfflineGate.validPng(Pngs.write(dir.resolve("e.png"), 64, 64, 1024 * 1024))); // over 1 MB
		Path jpeg = dir.resolve("f.png");
		Files.write(jpeg, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
		assertFalse(OfflineGate.validPng(jpeg));
		assertFalse(OfflineGate.validPng(dir.resolve("missing.png")));
		assertFalse(OfflineGate.validPng(null));
	}

	/** Minimal PNG headers (signature + IHDR) of a given size, padded to make the file bigger. */
	static final class Pngs {
		static Path write(Path file, int width, int height, int padding) throws Exception {
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'});
			java.io.DataOutputStream data = new java.io.DataOutputStream(out);
			data.writeInt(width);
			data.writeInt(height);
			data.write(new byte[] {8, 6, 0, 0, 0, 0, 0, 0, 0});
			data.write(new byte[padding]);
			Files.write(file, out.toByteArray());
			return file;
		}
	}
}
