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
}
