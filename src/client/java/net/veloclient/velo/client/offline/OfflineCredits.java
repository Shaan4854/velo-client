package net.veloclient.velo.client.offline;

import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.gui.title.MenuKit;

/**
 * The credit line in the in-game Locker: one muted line in the window's bottom padding, right-aligned under the
 * catalog, on every Locker tab (drawn from {@code CapeEquipScreen.drawSlots}). Always shown, for every account.
 * Locker screens only - never on the HUD or during gameplay.
 */
public final class OfflineCredits {

	private OfflineCredits() {
	}

	/** {@code right}/{@code contentBottom}: the Locker frame's right edge and bottom (the window padding is below it). */
	public static void draw(DrawContext context, net.minecraft.client.font.TextRenderer font, int left, int right, int contentBottom) {
		var text = MenuKit.fit(font, MenuKit.ui(OfflineCosmetics.CREDIT), Math.max(20, right - left));
		int width = MenuKit.width(font, text);
		MenuKit.text(context, font, text, right - width, contentBottom + 4, MenuKit.TEXT_3);
	}
}
