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
	void unlockedBlocksStoreOwnershipGets() {
		for (String path : new String[] {"/v1/store/wallet", "/v1/store/owned", "/v1/store/order?id=abc", "/v1/store/code?c=VELO",
				"/v1/store", "/v1/store/", "/v1/store?x=1", "/v1/store/catalog/", "/v1/store/catalog/extra", "/v1/store/catalogue",
				"/v1/Store/wallet", "/v1/STORE/catalog", "/v1/store/catalog/../wallet", "/v1/plus/../store/wallet", "/v1/store/./wallet",
				"/v1/store/wallet#x"}) {
			assertTrue(OfflineCosmetics.blockedWhileUnlocked(path), path);
		}
	}

	@Test
	void unlockedAllowsTheCatalog() {
		assertFalse(OfflineCosmetics.blockedWhileUnlocked("/v1/store/catalog"));
		assertFalse(OfflineCosmetics.blockedWhileUnlocked("/v1/store/catalog?lang=en"));
		assertFalse(OfflineCosmetics.blockedWhileUnlocked("/v1/store/catalog#top"));
	}

	@Test
	void unlockedLeavesEveryNonStoreGetAlone() {
		for (String path : new String[] {"/v1/worlds/public", "/v1/worlds/joinable", "/v1/partners", "/v1/stats/live",
				"/v1/discord/status", "/v1/referral", "/v1/rewards", "/v1/plus/status", "/v1/plus/token", "/v1/plus/addons",
				"/v1/staff/reports?status=open", "/v1/admin/partners", "/v1/admin/cosmetics", "/v1/storefront", "/v1/storefront/x",
				"/v1/stores", "/v1/rewards?from=/v1/store/wallet"}) {
			assertFalse(OfflineCosmetics.blockedWhileUnlocked(path), path);
		}
		assertFalse(OfflineCosmetics.blockedWhileUnlocked(null));
	}

	private static final String OFFLINE_A = "0123456789abcdef0123456789abcdef";
	private static final String OFFLINE_B = "fedcba9876543210fedcba9876543210";
	private static final String MS_UUID = "11112222333344445555666677778888";

	@Test
	void cloakImportEquipsWhenNothingChanged() {
		assertTrue(OfflineCosmetics.mayEquipAfterImport(OFFLINE_A, true, OFFLINE_A, null));
	}

	@Test
	void cloakImportDoesNotEquipAfterAMicrosoftSwitch() {
		// A Microsoft session: a different uuid, the unlock off (not an offline account) and usually a Velo token.
		assertFalse(OfflineCosmetics.mayEquipAfterImport(OFFLINE_A, false, MS_UUID, "velo-token"));
		assertFalse(OfflineCosmetics.mayEquipAfterImport(OFFLINE_A, false, MS_UUID, null));
		assertFalse(OfflineCosmetics.mayEquipAfterImport(OFFLINE_A, true, MS_UUID, null));
	}

	@Test
	void cloakImportDoesNotEquipOnAnotherOfflineAccount() {
		assertFalse(OfflineCosmetics.mayEquipAfterImport(OFFLINE_A, true, OFFLINE_B, null));
		assertFalse(OfflineCosmetics.mayEquipAfterImport(OFFLINE_A, true, null, null));
		assertFalse(OfflineCosmetics.mayEquipAfterImport(null, true, null, null));
	}

	@Test
	void cloakImportDoesNotEquipWhenTheToggleWasTurnedOff() {
		assertFalse(OfflineCosmetics.mayEquipAfterImport(OFFLINE_A, false, OFFLINE_A, null));
	}

	@Test
	void cloakImportDoesNotEquipWithASessionToken() {
		assertFalse(OfflineCosmetics.mayEquipAfterImport(OFFLINE_A, true, OFFLINE_A, "velo-token"));
	}

	@Test
	void doubleSlashPathsAreBlocked() {
		assertTrue(OfflineCosmetics.blockedWhileUnlocked("//v1/store/wallet"));
		assertTrue(OfflineCosmetics.blockedWhileUnlocked("/v1//store/wallet"));
		assertTrue(OfflineCosmetics.blockedWhileUnlocked("/v1/store//catalog"));
		assertTrue(OfflineCosmetics.blockedWhileUnlocked("/v1//rewards"));
		assertFalse(OfflineCosmetics.blockedWhileUnlocked("/v1/rewards"));
		assertFalse(OfflineCosmetics.blockedWhileUnlocked("/v1/store/catalog"));
	}
}
