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
	 * Any path containing {@code //} is refused (no caller sends one).
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
		if (raw.contains("//")) {
			return true; // "//host/..." would parse as an authority and hide the real path - never let one out unlocked
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

	/** Status line + refresh of the Store item page that started an equip (FX thread only). */
	public record Feedback(java.util.function.Consumer<String> status, Runnable refresh) {
		static final Feedback NONE = new Feedback(text -> { }, () -> { });
	}

	static final String DOWNLOADING = "Downloading this cloak's art...";
	static final String IMPORT_EQUIPPED = "Cloak equipped - shows in your next game.";
	static final String IMPORT_NOT_EQUIPPED = "Not equipped - the account or the offline cosmetics setting changed during the download.";
	static final String IMPORT_FAILED = "Couldn't get this cloak's art - try again later.";

	private static Feedback feedback;
	private static boolean deferred;

	/**
	 * Runs a Store item page's equip action. Returns true when it started a background cloak import instead
	 * (offline unlock), so the page shows the import's status rather than "equipped". FX thread only.
	 */
	public static boolean runEquip(Runnable equip, java.util.function.Consumer<String> status, Runnable refresh) {
		feedback = new Feedback(status, refresh);
		deferred = false;
		try {
			equip.run();
			return deferred;
		} finally {
			feedback = null;
			deferred = false;
		}
	}

	/**
	 * Whether a cloak imported for an offline-unlock click may be equipped now: the unlock is still on, it is the very
	 * same account (dashless uuid) the click came from, and no Velo session token exists. Anything else - a Microsoft
	 * account switched in, another offline account, the toggle turned off, a session token - and nothing is equipped.
	 */
	static boolean mayEquipAfterImport(String clickedAccount, boolean unlockedNow, String accountNow, String tokenNow) {
		return clickedAccount != null && unlockedNow && clickedAccount.equals(accountNow) && tokenNow == null;
	}

	private static boolean stillSameOfflineAccount(String clickedAccount) {
		return mayEquipAfterImport(clickedAccount, lockerUnlocked(), net.veloclient.launcher.data.VeloPaths.account(),
				net.veloclient.launcher.social.LauncherSocial.sessionToken());
	}

	/**
	 * Launcher Store page "Equip" on a cloak that isn't in the library yet (unlock on): imports that one cloak in the
	 * background - bundled art, or the server-only GIF through the existing public {@code /v1/cosmetics/file/<sha>}
	 * download ({@code StoreCatalog.openGif} -> {@code WingsCatalog.openFile}) - then equips it on the FX thread.
	 * Same as {@code StorePurchase.restoreMissing} does for a bought cloak, for this single item.
	 * <p>The account is captured at the click and re-checked ({@link #mayEquipAfterImport}) before the import and
	 * again on the FX thread right before equipping; on a change nothing is equipped and nothing is left pending.
	 */
	public static void importAndEquipCape(net.veloclient.launcher.data.StoreItem item, java.util.function.Supplier<java.util.Optional<String>> libraryId) {
		importAndEquipCape(item, libraryId, feedback == null ? Feedback.NONE : feedback);
	}

	/** Same, reporting to the given page (the launcher Locker's Cloaks panel). FX thread. */
	public static void importAndEquipCape(net.veloclient.launcher.data.StoreItem item, java.util.function.Supplier<java.util.Optional<String>> libraryId,
			Feedback page) {
		String clickedAccount = net.veloclient.launcher.data.VeloPaths.account();
		deferred = true;
		if (!stillSameOfflineAccount(clickedAccount)) {
			page.status().accept(IMPORT_NOT_EQUIPPED);
			return;
		}
		page.status().accept(DOWNLOADING);
		if (!IMPORTING.add(item.id())) {
			return; // already on its way (a second click while the art downloads)
		}
		Thread.ofVirtual().name("velo-offline-cape-import").start(() -> {
			boolean imported = false;
			// Server-only art: download (or find cached) the exact file first - openGif would hand back a placeholder on failure.
			boolean art = item.gifResourcePath() != null || net.veloclient.launcher.data.WingsCatalog.file(item.gifSha()) != null;
			if (!art) {
				net.veloclient.launcher.LauncherLog.info("Offline cosmetics: art for cloak " + item.id() + " isn't available - not imported");
			} else if (!stillSameOfflineAccount(clickedAccount)) {
				net.veloclient.launcher.LauncherLog.info("Offline cosmetics: account changed during the download - cloak " + item.id() + " not imported");
			} else try (java.io.InputStream in = net.veloclient.launcher.data.StoreCatalog.openGif(item)) {
				if (libraryId.get().isEmpty()) {
					net.veloclient.launcher.data.CapeLibrary.importAnimatedGif(item.name(), in,
							net.veloclient.launcher.data.CapePhysicsPresetData.defaults(), item.id());
				}
				imported = true;
			} catch (Exception e) {
				net.veloclient.launcher.LauncherLog.warn("Offline cosmetics: couldn't import cloak " + item.id(), e);
			}
			boolean done = imported;
			javafx.application.Platform.runLater(() -> {
				IMPORTING.remove(item.id());
				String message;
				if (!stillSameOfflineAccount(clickedAccount)) {
					message = IMPORT_NOT_EQUIPPED; // checked on the FX thread, where account switches happen
				} else if (!done) {
					message = IMPORT_FAILED;
				} else {
					java.util.Optional<String> id = libraryId.get();
					id.ifPresent(net.veloclient.launcher.data.CapeLibrary::equip);
					message = id.isPresent() ? IMPORT_EQUIPPED : IMPORT_FAILED;
				}
				page.status().accept(message);
				page.refresh().run();
			});
		});
	}

	/**
	 * Catalog cloaks with no copy in the cloak library yet, matched by name like the Store page's equip does (an import
	 * gets a fresh library id; its name is the item's name). Keeps catalog order; null names never match.
	 */
	static <T> List<T> notInLibrary(List<T> catalog, java.util.function.Function<T, String> name, java.util.Collection<String> libraryNames) {
		java.util.Set<String> have = new java.util.HashSet<>(libraryNames);
		List<T> missing = new java.util.ArrayList<>();
		for (T item : catalog) {
			String itemName = name.apply(item);
			if (itemName == null || !have.contains(itemName)) {
				missing.add(item);
			}
		}
		return missing;
	}

	/** Locker > Cloaks while unlocked: every catalog cloak not in the library yet (empty when locked). */
	public static List<net.veloclient.launcher.data.StoreItem> lockerCatalogCloaks(List<net.veloclient.launcher.data.CapeEntry> library) {
		if (!lockerUnlocked()) {
			return List.of();
		}
		List<String> names = library.stream().filter(net.veloclient.launcher.data.CapeEntry::animated)
				.map(net.veloclient.launcher.data.CapeEntry::name).toList();
		return notInLibrary(net.veloclient.launcher.data.StoreCatalog.all(), net.veloclient.launcher.data.StoreItem::name, names);
	}

	/** A cell factory from the Locker: (art, name, click) -> the cell, added to its grid. */
	public interface CellSink {
		void add(javafx.scene.Node art, String name, Runnable onClick);
	}

	/**
	 * Adds the {@link #lockerCatalogCloaks} cells to the launcher Locker's Cloaks grid (unless it is filtered to static
	 * cloaks - these are all animated). A click imports that one cloak in the background and wears it, re-checking the
	 * account first ({@link #importAndEquipCape}); the panel shows the download and refreshes when it's done.
	 */
	public static void addLockerCloaks(List<net.veloclient.launcher.data.StoreItem> cloaks, boolean staticOnly, CellSink sink,
			javafx.scene.Node toastAnchor, Runnable refresh) {
		if (staticOnly) {
			return;
		}
		for (net.veloclient.launcher.data.StoreItem item : cloaks) {
			sink.add(net.veloclient.launcher.ui.StoreTiles.capeArt(item, null, 96), item.name(), () -> {
				if (!lockerUnlocked()) {
					return;
				}
				Feedback page = new Feedback(text -> net.veloclient.launcher.ui.Toasts.show(toastAnchor, text), refresh);
				importAndEquipCape(item, () -> libraryIdFor(item), page);
			});
		}
	}

	private static java.util.Optional<String> libraryIdFor(net.veloclient.launcher.data.StoreItem item) {
		return net.veloclient.launcher.data.CapeLibrary.listAll().stream()
				.filter(entry -> entry.animated() && entry.name().equals(item.name()))
				.reduce((first, second) -> second)
				.map(net.veloclient.launcher.data.CapeEntry::id);
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
