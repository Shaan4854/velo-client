package net.veloclient.launcher.auth;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultSkinsTest {

	@Test
	void pickMatchesVanillaClassicRule() {
		for (String name : new String[] {"Steve", "Notch", "Addy_OnChain", "abc", "Player123"}) {
			String dashless = OfflineAccounts.offlineUuid(name);
			UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			assertEquals((uuid.hashCode() & 1) == 1, DefaultSkins.isSlim(dashless), name);
			assertEquals(uuid, DefaultSkins.parse(dashless));
			assertEquals(uuid, DefaultSkins.parse(uuid.toString()));
		}
	}

	@Test
	void badUuidFallsBackToSteve() {
		assertEquals(false, DefaultSkins.isSlim("nope"));
	}
}
