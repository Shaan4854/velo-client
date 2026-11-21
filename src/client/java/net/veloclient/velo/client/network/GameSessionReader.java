package net.veloclient.velo.client.network;

import java.util.Optional;
import java.util.UUID;

/**
 * The account the running game is actually signed in as, straight from its own session - works
 * no matter which launcher started the game (the vanilla launcher, Prism, the Velo launcher...),
 * unlike {@link ActiveAccountReader}, which only knows about accounts the Velo launcher saved.
 * Before this, launching through anything but the Velo launcher meant Velo Network never
 * authenticated at all, so nobody ever saw that player's badge or cape.
 */
final class GameSessionReader {

	private GameSessionReader() {
	}

	/**
	 * True when this is a real (non-development) game started with an offline account - a placeholder access token
	 * such as the Velo launcher's offline "0". Such a game must never sign in to Velo Network (or swap its session) as
	 * the launcher's saved Microsoft account: the player in-game is someone else. Development runs (runClient) keep
	 * using the launcher's account as before.
	 */
	static boolean isOfflineSession() {
		try {
			if (net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {
				return false;
			}
			//? if <26.1 {
			net.minecraft.client.session.Session session = net.minecraft.client.MinecraftClient.getInstance().getSession();
			//?} else {
			/*net.minecraft.client.User session = net.minecraft.client.Minecraft.getInstance().getUser();
			*///?}
			if (session == null) {
				return false;
			}
			String accessToken = session.getAccessToken();
			return accessToken == null || accessToken.length() < 32;
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** Empty for offline/dev sessions, whose placeholder tokens Mojang would reject anyway. */
	static Optional<ActiveAccountReader.Account> read() {
		try {
			//? if <26.1 {
			net.minecraft.client.session.Session session = net.minecraft.client.MinecraftClient.getInstance().getSession();
			UUID uuid = session.getUuidOrNull();
			String username = session.getUsername();
			//?} else {
			/*net.minecraft.client.User session = net.minecraft.client.Minecraft.getInstance().getUser();
			UUID uuid = session.getProfileId();
			String username = session.getName();
			*///?}
			String accessToken = session.getAccessToken();
			// Real Minecraft access tokens are long JWTs; dev/offline sessions use "0", "FabricMC" etc.
			if (uuid == null || username == null || accessToken == null || accessToken.length() < 32) {
				return Optional.empty();
			}
			String dashless = VeloServerClient.normalizeUuid(uuid.toString());
			// Expiry isn't exposed here - if Mojang rejects it, VeloServerClient falls back to refreshing.
			return Optional.of(new ActiveAccountReader.Account(dashless, username, accessToken, Long.MAX_VALUE));
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}
}
