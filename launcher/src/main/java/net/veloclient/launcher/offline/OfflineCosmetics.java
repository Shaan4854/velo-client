package net.veloclient.launcher.offline;

import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.data.LauncherSettings;

import java.util.List;

/**
 * Launcher side of the local cosmetics unlock for offline accounts (Settings > "Unlock cosmetics for offline
 * accounts", off by default). Only when that toggle is on <b>and</b> the account is an offline one:
 * <ul>
 * <li>the game gets {@code -Dvelo.offlineCosmetics=true} (the in-game unlock also checks for an offline session and no
 * Velo token itself);</li>
 * <li>the launcher's Locker shows every Store cosmetic as owned (local files only - nothing is bought, claimed or sent);</li>
 * <li>Skins > Equip saves a local skin choice instead of uploading to Mojang.</li>
 * </ul>
 * Microsoft accounts are never affected. Every hook into existing code is tagged {@code // offline-cosmetics}.
 */
public final class OfflineCosmetics {

	public static final String FLAG = "-Dvelo.offlineCosmetics=true";
	/** The credit Velo's founder asked for - always shown in the Locker header (and in-game Locker, README, NOTICE.md). */
	public static final String CREDIT = "Cosmetics art \u00a9 Velo Client, used with permission from founder Velo2k.";

	private OfflineCosmetics() {
	}

	/** The gate: the toggle is on and the account is an offline one. */
	static boolean enabled(boolean toggleOn, boolean offlineAccount) {
		return toggleOn && offlineAccount;
	}

	/** JVM flags for a launch: the opt-in flag only for an offline session with the toggle on. */
	static List<String> jvmFlags(boolean toggleOn, MinecraftSession session) {
		return session != null && enabled(toggleOn, session.isOffline()) ? List.of(FLAG) : List.of();
	}

	public static List<String> jvmFlags(MinecraftSession session) {
		return jvmFlags(toggleOn(), session);
	}

	public static boolean toggleOn() {
		try {
			return LauncherSettings.get().offlineCosmetics;
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** The launcher's own Locker and Store treat everything as owned: offline account in use and the toggle on. */
	public static boolean lockerUnlocked() {
		return enabled(toggleOn(), net.veloclient.launcher.social.LauncherSocial.offlineAccount());
	}

	/** The Locker header's credit line (always shown). */
	public static javafx.scene.control.Label creditLabel() {
		javafx.scene.control.Label label = new javafx.scene.control.Label(CREDIT);
		label.getStyleClass().add("t-muted");
		label.setWrapText(true);
		label.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
		return label;
	}

	private static final String STORE = "/v1/store";
	private static final String CATALOG = "/v1/store/catalog";

	/**
	 * GETs refused while the Locker is unlocked: the store/ownership API - everything under {@code /v1/store/}
	 * (wallet, order, partner code, ...) except exactly the public catalog ({@code /v1/store/catalog}, query string
	 * allowed). Every other path (worlds, partners, stats, Discord, referral, rewards, Plus, ...) is not touched and
	 * goes out exactly as before. Matching ignores the query/fragment, is case-insensitive for the store prefix, also
	 * checks the dot-segment-normalized path, and needs a segment boundary ({@code /v1/storefront} is not the store).
	 */
	public static boolean blockedWhileUnlocked(String path) {
		if (path == null) {
			return false;
		}
		String raw = path;
		int cut = indexOfAny(raw, '?', '#');
		if (cut >= 0) {
			raw = raw.substring(0, cut);
		}
		String normalized;
		try {
			normalized = java.net.URI.create(raw).normalize().getPath();
		} catch (IllegalArgumentException e) {
			return isStore(raw); // unparsable: decide on the raw path alone, never as the catalog
		}
		if (normalized == null) {
			normalized = raw;
		}
		if (!isStore(raw) && !isStore(normalized)) {
			return false;
		}
		return !(CATALOG.equals(raw) && CATALOG.equals(normalized));
	}

	private static boolean isStore(String path) {
		String p = path.toLowerCase(java.util.Locale.ROOT);
		return p.equals(STORE) || p.startsWith(STORE + "/");
	}

	private static int indexOfAny(String s, char a, char b) {
		int i = s.indexOf(a);
		int j = s.indexOf(b);
		return i < 0 ? j : j < 0 ? i : Math.min(i, j);
	}

	private static final java.util.Set<String> IMPORTING = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/**
	 * Launcher Store page "Equip" on a cloak that isn't in the library yet (unlock on): imports that one cloak in the
	 * background - bundled art, or the server-only GIF through the existing public {@code /v1/cosmetics/file/<sha>}
	 * download ({@code StoreCatalog.openGif} -> {@code WingsCatalog.openFile}) - then equips it on the FX thread.
	 * Same as {@code StorePurchase.restoreMissing} does for a bought cloak, for this single item.
	 */
	public static void importAndEquipCape(net.veloclient.launcher.data.StoreItem item, java.util.function.Supplier<java.util.Optional<String>> libraryId,
			Runnable done) {
		if (!IMPORTING.add(item.id())) {
			return; // already on its way (a second click while the art downloads)
		}
		Thread.ofVirtual().name("velo-offline-cape-import").start(() -> {
			// Server-only art: download (or find cached) the exact file first - openGif would hand back a placeholder on failure.
			boolean art = item.gifResourcePath() != null || net.veloclient.launcher.data.WingsCatalog.file(item.gifSha()) != null;
			if (!art) {
				net.veloclient.launcher.LauncherLog.info("Offline cosmetics: art for cloak " + item.id() + " isn't available - not imported");
			} else try (java.io.InputStream in = net.veloclient.launcher.data.StoreCatalog.openGif(item)) {
				if (libraryId.get().isEmpty()) {
					net.veloclient.launcher.data.CapeLibrary.importAnimatedGif(item.name(), in,
							net.veloclient.launcher.data.CapePhysicsPresetData.defaults(), item.id());
				}
			} catch (Exception e) {
				net.veloclient.launcher.LauncherLog.warn("Offline cosmetics: couldn't import cloak " + item.id(), e);
			}
			IMPORTING.remove(item.id());
			javafx.application.Platform.runLater(() -> {
				libraryId.get().ifPresent(net.veloclient.launcher.data.CapeLibrary::equip);
				done.run();
			});
		});
	}

	/** Settings row with the toggle (default off). */
	public static void addSettingsRow(javafx.scene.layout.VBox card) {
		javafx.scene.control.CheckBox box = new javafx.scene.control.CheckBox();
		box.setSelected(toggleOn());
		box.setOnAction(e -> {
			LauncherSettings.Data data = LauncherSettings.get();
			data.offlineCosmetics = box.isSelected();
			LauncherSettings.save(data);
		});
		net.veloclient.launcher.ui.SettingsUi.row(card, "Unlock cosmetics for offline accounts",
				"Offline accounts only - local, only you see them. Off by default",
				"With an offline account, every Velo cosmetic can be equipped in your own game and in this Locker, and Skins > Equip"
						+ " wears a skin from your library locally. Nothing is bought, claimed or sent to any server, other players never"
						+ " see it, and Microsoft accounts are not affected. Takes effect on the next game launch. " + CREDIT, box);
	}
}
