package net.veloclient.launcher.offline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OfflineSkinSettingTest {

	@TempDir
	Path dir;

	@Test
	void writeThenRead() throws Exception {
		Path file = dir.resolve("accounts/abc/offline-skin.json");
		OfflineSkinSetting.write(file, new OfflineSkinSetting.Choice("skin-1", true));
		assertEquals(new OfflineSkinSetting.Choice("skin-1", true), OfflineSkinSetting.read(file));
		OfflineSkinSetting.write(file, new OfflineSkinSetting.Choice("skin-2", false));
		assertEquals(new OfflineSkinSetting.Choice("skin-2", false), OfflineSkinSetting.read(file));
	}

	@Test
	void missingFileIsNoChoice() {
		assertNull(OfflineSkinSetting.read(dir.resolve("nope.json")));
	}

	@Test
	void badJsonIsNoChoice() throws Exception {
		Path file = dir.resolve("offline-skin.json");
		Files.writeString(file, "{not json");
		assertNull(OfflineSkinSetting.read(file));
		Files.writeString(file, "{\"slim\":true}");
		assertNull(OfflineSkinSetting.read(file));
		Files.writeString(file, "[1,2]");
		assertNull(OfflineSkinSetting.read(file));
	}

	private static final String ID = "0123456789abcdef0123456789abcdef";

	private static Path png(Path file, int width, int height, int padding) throws Exception {
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

	@Test
	void validPngLikeTheGame() throws Exception {
		org.junit.jupiter.api.Assertions.assertTrue(OfflineSkinSetting.validPng(png(dir.resolve("a.png"), 64, 64, 0)));
		org.junit.jupiter.api.Assertions.assertTrue(OfflineSkinSetting.validPng(png(dir.resolve("b.png"), 64, 32, 0)));
		org.junit.jupiter.api.Assertions.assertFalse(OfflineSkinSetting.validPng(png(dir.resolve("c.png"), 128, 128, 0)));
		org.junit.jupiter.api.Assertions.assertFalse(OfflineSkinSetting.validPng(png(dir.resolve("d.png"), 64, 64, 1024 * 1024)));
		Path jpeg = dir.resolve("e.png");
		Files.write(jpeg, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
		org.junit.jupiter.api.Assertions.assertFalse(OfflineSkinSetting.validPng(jpeg));
		org.junit.jupiter.api.Assertions.assertFalse(OfflineSkinSetting.validPng(dir.resolve("missing.png")));
	}

	@Test
	void equipRefusesAnInvalidSkinWithoutWriting() throws Exception {
		// The skin library lives under user.home (the test task points it at build/test-home).
		Path skins = net.veloclient.launcher.data.SkinLibrary.dir();
		Files.createDirectories(skins);
		png(skins.resolve(ID + ".png"), 128, 128, 0);
		String message = OfflineSkinSetting.equip(new net.veloclient.launcher.data.SkinLibrary.Skin(ID, "Big", false, 0));
		assertEquals(OfflineSkinSetting.INVALID, message);
		assertNull(OfflineSkinSetting.read(OfflineSkinSetting.file()));
		assertEquals(OfflineSkinSetting.INVALID,
				OfflineSkinSetting.equip(new net.veloclient.launcher.data.SkinLibrary.Skin("../escape", "Bad", false, 0)));
	}

	@Test
	void equipAcceptsAValidSkin() throws Exception {
		String id = "fedcba9876543210fedcba9876543210";
		Path skins = net.veloclient.launcher.data.SkinLibrary.dir();
		Files.createDirectories(skins);
		png(skins.resolve(id + ".png"), 64, 32, 0);
		try {
			assertEquals("Equipped locally - only you see it (offline account).",
					OfflineSkinSetting.equip(new net.veloclient.launcher.data.SkinLibrary.Skin(id, "Old", true, 0)));
			assertEquals(new OfflineSkinSetting.Choice(id, true), OfflineSkinSetting.read(OfflineSkinSetting.file()));
		} finally {
			Files.deleteIfExists(OfflineSkinSetting.file());
		}
	}

	@Test
	void skinIdsCantLeaveTheLibrary() {
		assertNull(OfflineSkinSetting.safePng(dir, "../" + ID));
		assertNull(OfflineSkinSetting.safePng(dir, "abc"));
		assertEquals(dir.toAbsolutePath().normalize().resolve(ID + ".png"), OfflineSkinSetting.safePng(dir, ID));
	}
}
