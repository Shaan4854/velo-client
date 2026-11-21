package net.veloclient.launcher.launch;

import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.auth.OfflineAccounts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameLauncherAuthArgsTest {

	@Test
	void microsoftKeepsRealTokenAndMsa() {
		MinecraftSession ms = new MinecraftSession("real-token", Long.MAX_VALUE, "r", "0123456789abcdef0123456789abcdef", "AddyMS");
		assertEquals("real-token", GameLauncher.launchAccessToken(ms));
		assertEquals("msa", GameLauncher.launchUserType(ms));
	}

	@Test
	void offlineUsesZeroTokenAndLegacy() {
		MinecraftSession off = OfflineAccounts.create("Steve", java.util.List.of());
		assertEquals("0", GameLauncher.launchAccessToken(off));
		assertEquals("legacy", GameLauncher.launchUserType(off));
	}
}
