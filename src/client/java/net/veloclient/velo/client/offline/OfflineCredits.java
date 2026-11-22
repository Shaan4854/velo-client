package net.veloclient.velo.client.offline;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.MenuKit;

/**
 * The credit line in the in-game Locker, drawn from {@code CapeEquipScreen.drawSlots} on every Locker tab, for every
 * account. Locker screens only - never on the HUD or during gameplay. Never truncated.
 *
 * <p>Where: the window's bottom padding, under the whole Locker frame. Every Locker tab keeps its content above
 * {@code contentBottom} (scissors, grids and the loadout chips all stop there) and the window's edge is
 * {@code VeloWindow.PADDING} = 16 px below it, with its 1 px border at +14/+15 - so rows +0..+13 are free on every tab.
 * Layout, picked by measuring the text:
 * <ol>
 * <li>one line in the menu font (Inter 9, 8 px tall) at +4, right-aligned to the frame, if it fits the full frame width;</li>
 * <li>else one line in the small menu font (Inter 7, 7 px tall) at +4;</li>
 * <li>else two lines in the small font at +0 and +7 (rows +0..+13), split after "Velo Client,".</li>
 * </ol>
 * Worst case: Minecraft's GUI scale always leaves at least 320 scaled pixels of width (Velo's dense GUI only makes it
 * wider), so a Locker window is at least 296 wide and the frame at least 264 (222 with the dev menu shell's rail).
 * The longer of the two small-font lines is about 170 px, so the two-line layout always fits. Should a frame ever be
 * narrower, a line starts at the frame's left edge and runs past the right edge - it is still never cut.
 */
public final class OfflineCredits {

	private OfflineCredits() {
	}

	/** {@code left}/{@code right}: the Locker frame's edges; {@code contentBottom}: its bottom (the padding is below). */
	public static void draw(DrawContext context, net.minecraft.client.font.TextRenderer font, int left, int right, int contentBottom) {
		int avail = right - left;
		var full = MenuKit.ui(OfflineCosmetics.CREDIT);
		if (MenuKit.width(font, full) <= avail) {
			line(context, font, full, left, right, contentBottom + 4);
			return;
		}
		var small = MenuKit.small(OfflineCosmetics.CREDIT);
		if (MenuKit.width(font, small) <= avail) {
			line(context, font, small, left, right, contentBottom + 4);
			return;
		}
		line(context, font, MenuKit.small(OfflineCosmetics.CREDIT_LINE_1), left, right, contentBottom);
		line(context, font, MenuKit.small(OfflineCosmetics.CREDIT_LINE_2), left, right, contentBottom + 7);
	}

	/** Right-aligned to {@code right}, but never starting left of {@code left}. */
	private static void line(DrawContext context, net.minecraft.client.font.TextRenderer font, Text text, int left, int right, int y) {
		int x = Math.max(left, right - MenuKit.width(font, text));
		MenuKit.text(context, font, text, x, y, MenuKit.TEXT_3);
	}
}
