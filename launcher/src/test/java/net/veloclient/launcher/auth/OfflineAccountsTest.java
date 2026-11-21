package net.veloclient.launcher.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineAccountsTest {

	@Test
	void offlineUuidMatchesKnownVectors() {
		assertEquals("b50ad385829d3141a2167e7d7539ba7f", OfflineAccounts.offlineUuid("Notch"));
		assertEquals("5627dd98e6be3c21b8a8e92344183641", OfflineAccounts.offlineUuid("Steve"));
		assertEquals("f1eb7e4c24803451a341c6bd9beb1537", OfflineAccounts.offlineUuid("Addy_OnChain"));
	}

	@Test
	void offlineUuidIsVanillaVersion3DashlessLowercase() {
		String uuid = OfflineAccounts.offlineUuid("Steve");
		assertEquals(32, uuid.length());
		assertEquals(uuid.toLowerCase(), uuid);
		UUID expected = UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes(StandardCharsets.UTF_8));
		assertEquals(expected.toString().replace("-", ""), uuid);
		assertEquals(3, expected.version());
	}

	@Test
	void offlineUuidIsCaseSensitive() {
		assertNotEquals(OfflineAccounts.offlineUuid("Steve"), OfflineAccounts.offlineUuid("steve"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"abc", "A_1234567890bcde", "Steve", "___", "Addy_OnChain", "  Steve  "})
	void acceptsValidNames(String name) {
		assertTrue(OfflineAccounts.validate(name).isEmpty(), name);
	}

	@ParameterizedTest
	@ValueSource(strings = {"ab", "abcdefghijklmnopq", "bad-name", "bad name", "Stéve", "名前名前", "a.b.c", "   "})
	void rejectsInvalidNames(String name) {
		assertTrue(OfflineAccounts.validate(name).isPresent(), name);
	}

	@ParameterizedTest
	@NullAndEmptySource
	void rejectsNullAndEmpty(String name) {
		assertTrue(OfflineAccounts.validate(name).isPresent());
	}

	@Test
	void lengthBoundaries() {
		assertTrue(OfflineAccounts.validate("ab").isPresent());           // 2
		assertTrue(OfflineAccounts.validate("abc").isEmpty());            // 3
		assertTrue(OfflineAccounts.validate("abcdefghijklmnop").isEmpty()); // 16
		assertTrue(OfflineAccounts.validate("abcdefghijklmnopq").isPresent()); // 17
		assertEquals("3-16 characters", OfflineAccounts.validate("ab").orElseThrow());
		assertEquals("Only letters, numbers and _", OfflineAccounts.validate("bad-name").orElseThrow());
	}

	@Test
	void createBuildsOfflineSession() {
		MinecraftSession s = OfflineAccounts.create("  Steve ");
		assertEquals("Steve", s.username());
		assertEquals("5627dd98e6be3c21b8a8e92344183641", s.uuid());
		assertEquals(AccountType.OFFLINE, s.type());
		assertTrue(s.isOffline());
		assertNull(s.minecraftAccessToken());
		assertNull(s.microsoftRefreshToken());
		assertEquals(Long.MAX_VALUE, s.accessTokenExpiresAtEpochMillis());
		assertFalse(s.isAccessTokenExpired());
		assertFalse(s.tooShortForGame());
	}

	@Test
	void createEnforcesTheRule() {
		assertThrows(IllegalArgumentException.class, () -> OfflineAccounts.create("ab"));
		assertThrows(IllegalArgumentException.class, () -> OfflineAccounts.create("bad name"));
		assertThrows(IllegalArgumentException.class, () -> OfflineAccounts.create(null));
	}

	@Test
	void caseInsensitiveDuplicatesAreBlocked() {
		List<MinecraftSession> saved = List.of(OfflineAccounts.create("Steve"));
		assertTrue(OfflineAccounts.validateNew("steve", saved).isPresent());
		assertTrue(OfflineAccounts.validateNew("STEVE", saved).isPresent());
		assertTrue(OfflineAccounts.validateNew("Steve", saved).isPresent());
		assertTrue(OfflineAccounts.validateNew("Alex", saved).isEmpty());
	}

	@Test
	void microsoftNameIsAllowedButFlagged() {
		MinecraftSession ms = new MinecraftSession("token", System.currentTimeMillis() + 3_600_000, "refresh",
				"0123456789abcdef0123456789abcdef", "Steve");
		List<MinecraftSession> saved = List.of(ms);
		assertTrue(OfflineAccounts.validateNew("steve", saved).isEmpty());
		assertTrue(OfflineAccounts.matchesMicrosoftName("steve", saved));
		assertFalse(OfflineAccounts.matchesMicrosoftName("Alex", saved));
	}
}
