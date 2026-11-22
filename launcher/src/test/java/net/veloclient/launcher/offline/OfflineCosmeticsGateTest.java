package net.veloclient.launcher.offline;

import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.auth.OfflineAccounts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineCosmeticsGateTest {

	private static final MinecraftSession MICROSOFT = new MinecraftSession("real-token", Long.MAX_VALUE, "r",
			"0123456789abcdef0123456789abcdef", "AddyMS");
	private static final MinecraftSession OFFLINE = OfflineAccounts.create("Steve", List.of());

	@Test
	void toggleOnOfflineGetsFlag() {
		assertEquals(List.of("-Dvelo.offlineCosmetics=true"), OfflineCosmetics.jvmFlags(true, OFFLINE));
	}

	@Test
	void toggleOffOfflineGetsNoFlag() {
		assertEquals(List.of(), OfflineCosmetics.jvmFlags(false, OFFLINE));
	}

	@Test
	void toggleOnMicrosoftGetsNoFlag() {
		assertEquals(List.of(), OfflineCosmetics.jvmFlags(true, MICROSOFT));
	}

	@Test
	void toggleOffMicrosoftGetsNoFlag() {
		assertEquals(List.of(), OfflineCosmetics.jvmFlags(false, MICROSOFT));
	}

	@Test
	void noSessionGetsNoFlag() {
		assertEquals(List.of(), OfflineCosmetics.jvmFlags(true, null));
	}

	@Test
	void lockerGateNeedsBoth() {
		assertTrue(OfflineCosmetics.enabled(true, true));
		assertFalse(OfflineCosmetics.enabled(true, false));
		assertFalse(OfflineCosmetics.enabled(false, true));
		assertFalse(OfflineCosmetics.enabled(false, false));
	}

	@Test
	void toggleDefaultsOff() {
		assertFalse(new net.veloclient.launcher.data.LauncherSettings.Data().offlineCosmetics);
	}

	@Test
	void creditWording() {
		assertEquals("Cosmetics art \u00a9 Velo Client, used with permission from founder Velo2k.", OfflineCosmetics.CREDIT);
	}

	@Test
	void unlockedStoreAllowsOnlyThePublicCatalog() {
		assertTrue(OfflineCosmetics.allowedWhileUnlocked("/v1/store/catalog"));
		assertFalse(OfflineCosmetics.allowedWhileUnlocked("/v1/store/wallet"));
		assertFalse(OfflineCosmetics.allowedWhileUnlocked("/v1/rewards"));
		assertFalse(OfflineCosmetics.allowedWhileUnlocked("/v1/plus/status"));
		assertFalse(OfflineCosmetics.allowedWhileUnlocked("/v1/store/catalog?x=1"));
	}
}
