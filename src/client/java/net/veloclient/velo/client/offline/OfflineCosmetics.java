package net.veloclient.velo.client.offline;

import net.veloclient.velo.client.network.VeloServerClient;

/**
 * Local-only cosmetics unlock for offline accounts started from the Velo launcher with "Unlock cosmetics for offline
 * accounts" switched on. While active, every Store cosmetic counts as owned <b>in this client only</b>: nothing is
 * claimed on, bought from or sent to the Velo server (an offline game never gets a Velo session token, so it never
 * sends a heartbeat), and other players never see it.
 *
 * <p>Fails closed - all three must hold, any exception means off:
 * <ol>
 * <li>the launcher's opt-in flag {@code -Dvelo.offlineCosmetics=true} (only added for offline launches with the toggle on);</li>
 * <li>a real offline game session (placeholder access token, never a dev run - see {@code GameSessionReader});</li>
 * <li>no Velo Network session token (never while signed in to Velo with a Microsoft account).</li>
 * </ol>
 * Every hook into existing code is one line tagged {@code // offline-cosmetics}.
 */
public final class OfflineCosmetics {

	public static final String PROPERTY = OfflineGate.PROPERTY;
	/** The credit Velo's founder asked for, split where it may wrap - always shown in the Locker, README and NOTICE.md. */
	public static final String CREDIT_LINE_1 = "Cosmetics art \u00a9 Velo Client,";
	public static final String CREDIT_LINE_2 = "used with permission from founder Velo2k.";
	public static final String CREDIT = CREDIT_LINE_1 + " " + CREDIT_LINE_2;
	public static final String STORE_BLOCKED = "Offline account - Store purchases need a Microsoft account";
	public static final String DOWNLOADING = "Downloading the cloak... press Equip again in a moment";
	public static final String SKINS_NEED_MICROSOFT = "Skins need a Microsoft account";

	private static final long CACHE_NANOS = 1_000_000_000L;
	private static volatile boolean cached;
	private static volatile long checkedAt;
	private static volatile boolean everChecked;

	private OfflineCosmetics() {
	}

	/** Whether the local unlock is on right now (cheap: called many times a frame through {@code StoreClient.owns}). */
	public static boolean isActive() {
		try {
			String flag = System.getProperty(PROPERTY);
			if (!"true".equals(flag)) {
				return false; // every Microsoft launch, and offline launches with the toggle off
			}
			String token = VeloServerClient.sessionToken();
			if (token != null) {
				return false; // checked on every call: a cached "true" never outlives a Velo session
			}
			long now = System.nanoTime();
			if (everChecked && now - checkedAt < CACHE_NANOS) {
				return cached;
			}
			boolean active = decide(flag, VeloServerClient.isOfflineGameSession(), token);
			cached = active;
			checkedAt = now;
			everChecked = true;
			return active;
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** The gate itself, without Minecraft: flag exactly "true", an offline game, and no Velo session token. */
	static boolean decide(String flag, boolean offlineGameSession, String veloSessionToken) {
		return OfflineGate.decide(flag, offlineGameSession, veloSessionToken);
	}

	/** Store requests that may still run while active: only the public catalog the Store page always loads. */
	public static boolean allowedWhileActive(String path) {
		return "/v1/store/catalog".equals(path);
	}

	/**
	 * Puts a Store cloak into the cape library for local wear (same as the Store's restore does for bought ones):
	 * bundled art right away, server-only art once its public download (the one the Store page already starts) is
	 * there. Returns the library id, or null while the art is still downloading or on failure.
	 */
	public static String importCape(String itemId) {
		var item = net.veloclient.velo.client.store.StoreCatalog.byId(itemId).orElse(null);
		if (item == null || !net.veloclient.velo.client.store.StoreAssets.ready(item)) {
			return null;
		}
		try (var in = net.veloclient.velo.client.store.StoreAssets.openGif(item)) {
			return net.veloclient.velo.client.cosmetics.CapeManager.importAnimatedGif(item.name(), in,
					net.veloclient.velo.client.cosmetics.CapePhysicsPreset.defaults(), item.id()).id();
		} catch (Exception e) {
			net.veloclient.velo.VeloClient.LOGGER.warn("Offline cosmetics: couldn't import cloak {}", itemId, e);
			return null;
		}
	}
}
