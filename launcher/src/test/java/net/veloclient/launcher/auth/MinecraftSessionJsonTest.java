package net.veloclient.launcher.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftSessionJsonTest {

	private static final String MS_UUID = "0123456789abcdef0123456789abcdef";

	@Test
	void oldFileWithoutTypeLoadsAsMicrosoft() {
		String json = """
				{
				  "accounts": [
				    {
				      "minecraftAccessToken": "eyJhbGciOi.long.token",
				      "accessTokenExpiresAtEpochMillis": 1700000000000,
				      "microsoftRefreshToken": "M.R3_refresh",
				      "uuid": "0123456789abcdef0123456789abcdef",
				      "username": "AddyMS"
				    }
				  ],
				  "activeUuid": "0123456789abcdef0123456789abcdef"
				}""";
		AuthSession.AccountsFile file = AuthSession.parse(JsonParser.parseString(json));
		assertEquals(1, file.accounts().size());
		MinecraftSession s = file.accounts().get(0);
		assertEquals(AccountType.MICROSOFT, s.type());
		assertFalse(s.isOffline());
		assertEquals("eyJhbGciOi.long.token", s.minecraftAccessToken());
		assertEquals("M.R3_refresh", s.microsoftRefreshToken());
		assertEquals(1700000000000L, s.accessTokenExpiresAtEpochMillis());
		assertEquals(MS_UUID, file.activeUuid());
		assertTrue(s.isAccessTokenExpired()); // 2023 timestamp: still expires normally for Microsoft accounts
	}

	@Test
	void legacyBareObjectStillLoads() {
		String json = """
				{"minecraftAccessToken":"t","accessTokenExpiresAtEpochMillis":5,"microsoftRefreshToken":"r",
				 "uuid":"0123456789abcdef0123456789abcdef","username":"Old"}""";
		AuthSession.AccountsFile file = AuthSession.parse(JsonParser.parseString(json));
		assertEquals(1, file.accounts().size());
		assertEquals(AccountType.MICROSOFT, file.accounts().get(0).type());
		assertEquals(MS_UUID, file.activeUuid());
	}

	@Test
	void offlineRoundTrip() {
		MinecraftSession offline = OfflineAccounts.create("Steve");
		MinecraftSession ms = new MinecraftSession("tok", 123L, "ref", MS_UUID, "AddyMS");
		String json = AuthSession.toJson(new AuthSession.AccountsFile(List.of(ms, offline), offline.uuid()));

		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		JsonObject offlineJson = root.getAsJsonArray("accounts").get(1).getAsJsonObject();
		assertEquals("OFFLINE", offlineJson.get("type").getAsString());
		assertEquals("5627dd98e6be3c21b8a8e92344183641", offlineJson.get("uuid").getAsString());
		assertEquals("Steve", offlineJson.get("username").getAsString());
		// Field names stay the contract with the game mod.
		JsonObject msJson = root.getAsJsonArray("accounts").get(0).getAsJsonObject();
		for (String field : new String[] {"minecraftAccessToken", "accessTokenExpiresAtEpochMillis", "microsoftRefreshToken", "uuid", "username"}) {
			assertTrue(msJson.has(field), field);
		}
		assertEquals("5627dd98e6be3c21b8a8e92344183641", root.get("activeUuid").getAsString());

		AuthSession.AccountsFile back = AuthSession.parse(JsonParser.parseString(json));
		assertEquals(2, back.accounts().size());
		MinecraftSession msBack = back.accounts().get(0);
		MinecraftSession offBack = back.accounts().get(1);
		assertEquals(ms, msBack);
		assertEquals(AccountType.MICROSOFT, msBack.type());
		assertEquals(AccountType.OFFLINE, offBack.type());
		assertTrue(offBack.isOffline());
		assertNull(offBack.minecraftAccessToken());
		assertNull(offBack.microsoftRefreshToken());
		assertFalse(offBack.isAccessTokenExpired());
		assertFalse(offBack.tooShortForGame());
		assertEquals(offline, offBack);
	}

	@Test
	void fiveArgConstructorIsMicrosoft() {
		MinecraftSession s = new MinecraftSession("t", System.currentTimeMillis() - 1, "r", MS_UUID, "n");
		assertEquals(AccountType.MICROSOFT, s.type());
		assertTrue(s.isMicrosoft());
		assertTrue(s.isAccessTokenExpired());
		assertTrue(s.tooShortForGame());
	}

	@Test
	void nullTypeIsMicrosoft() {
		MinecraftSession s = new MinecraftSession("t", 0, "r", MS_UUID, "n", null);
		assertEquals(AccountType.MICROSOFT, s.type());
	}

	private static MinecraftSession single(String typeJson) {
		String json = "{\"accounts\":[{\"accessTokenExpiresAtEpochMillis\":9223372036854775807,"
				+ "\"uuid\":\"5627dd98e6be3c21b8a8e92344183641\",\"username\":\"Steve\",\"type\":" + typeJson + "}],"
				+ "\"activeUuid\":\"5627dd98e6be3c21b8a8e92344183641\"}";
		AuthSession.AccountsFile file = AuthSession.parse(JsonParser.parseString(json));
		assertEquals(1, file.accounts().size());
		return file.accounts().get(0);
	}

	@Test
	void lowercaseAndMixedCaseOfflineTypesLoadAsOffline() {
		for (String type : new String[] {"\"offline\"", "\"Offline\"", "\"OFFLINE\""}) {
			MinecraftSession s = single(type);
			assertEquals(AccountType.OFFLINE, s.type(), type);
			assertFalse(AccountRefreshPolicy.removeAfterFailedRefresh(s, true), type);
		}
		assertEquals(AccountType.MICROSOFT, single("\"microsoft\"").type());
	}

	@Test
	void unknownTypeLoadsAsMicrosoftButIsNeverRefreshedOrRemovedWithoutRefreshToken() {
		for (String type : new String[] {"\"bogus\"", "\"\"", "null", "5"}) {
			MinecraftSession s = single(type);
			assertEquals(AccountType.MICROSOFT, s.type(), type);
			assertNull(s.microsoftRefreshToken());
			assertFalse(AccountRefreshPolicy.canRefresh(s), type);
			assertFalse(AccountRefreshPolicy.refreshOnStartup(s), type);
			assertFalse(AccountRefreshPolicy.refreshBeforeLaunch(s), type);
			assertFalse(AccountRefreshPolicy.removeAfterFailedRefresh(s, true), type);
		}
	}
}
