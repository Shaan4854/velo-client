package net.veloclient.launcher.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountRefreshPolicyTest {

	private static final String MS_UUID = "0123456789abcdef0123456789abcdef";

	private static MinecraftSession ms(long expiresAt, String refresh) {
		return new MinecraftSession("token", expiresAt, refresh, MS_UUID, "AddyMS");
	}

	@Test
	void offlineIsNeverRefreshedOrRemoved() {
		MinecraftSession off = OfflineAccounts.create("Steve");
		assertFalse(AccountRefreshPolicy.canRefresh(off));
		assertFalse(AccountRefreshPolicy.refreshOnStartup(off));
		assertFalse(AccountRefreshPolicy.refreshBeforeLaunch(off));
		assertFalse(AccountRefreshPolicy.removeAfterFailedRefresh(off, true));
		assertFalse(AccountRefreshPolicy.removeAfterFailedRefresh(off, false));
	}

	@Test
	void offlineEvenWithExpiredLookingFieldsIsSafe() {
		MinecraftSession off = new MinecraftSession(null, 0L, null, OfflineAccounts.offlineUuid("Steve"), "Steve", AccountType.OFFLINE);
		assertFalse(AccountRefreshPolicy.refreshOnStartup(off));
		assertFalse(AccountRefreshPolicy.refreshBeforeLaunch(off));
		assertFalse(AccountRefreshPolicy.removeAfterFailedRefresh(off, true));
	}

	@Test
	void microsoftExpiredIsRefreshedAndRemovedOnlyWhenDead() {
		MinecraftSession expired = ms(System.currentTimeMillis() - 1, "refresh");
		assertTrue(AccountRefreshPolicy.canRefresh(expired));
		assertTrue(AccountRefreshPolicy.refreshOnStartup(expired));
		assertTrue(AccountRefreshPolicy.refreshBeforeLaunch(expired));
		assertTrue(AccountRefreshPolicy.removeAfterFailedRefresh(expired, true));
		assertFalse(AccountRefreshPolicy.removeAfterFailedRefresh(expired, false));
	}

	@Test
	void microsoftValidTokenSkipsRefresh() {
		MinecraftSession fresh = ms(System.currentTimeMillis() + 24L * 3_600_000L, "refresh");
		assertFalse(AccountRefreshPolicy.refreshOnStartup(fresh));
		assertFalse(AccountRefreshPolicy.refreshBeforeLaunch(fresh));
		// 3 hours left: fine for the launcher, too short for a game session
		MinecraftSession shortLeft = ms(System.currentTimeMillis() + 3L * 3_600_000L, "refresh");
		assertFalse(AccountRefreshPolicy.refreshOnStartup(shortLeft));
		assertTrue(AccountRefreshPolicy.refreshBeforeLaunch(shortLeft));
	}

	@Test
	void missingRefreshTokenIsNeverRefreshedOrRemoved() {
		for (String refresh : new String[] {null, "", "  "}) {
			MinecraftSession noRefresh = ms(0L, refresh);
			assertFalse(AccountRefreshPolicy.canRefresh(noRefresh));
			assertFalse(AccountRefreshPolicy.refreshOnStartup(noRefresh));
			assertFalse(AccountRefreshPolicy.refreshBeforeLaunch(noRefresh));
			assertFalse(AccountRefreshPolicy.removeAfterFailedRefresh(noRefresh, true));
		}
	}

	@Test
	void nullSessionIsSafe() {
		assertFalse(AccountRefreshPolicy.canRefresh(null));
		assertFalse(AccountRefreshPolicy.refreshOnStartup(null));
		assertFalse(AccountRefreshPolicy.refreshBeforeLaunch(null));
		assertFalse(AccountRefreshPolicy.removeAfterFailedRefresh(null, true));
	}
}
