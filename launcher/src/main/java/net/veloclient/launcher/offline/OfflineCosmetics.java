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
		return label;
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
