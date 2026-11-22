package net.veloclient.velo.client.offline;

import net.veloclient.velo.client.cosmetics.CapeDefinition;
import net.veloclient.velo.client.cosmetics.CapeManager;
import net.veloclient.velo.client.gui.widget.StoreCard;
import net.veloclient.velo.client.gui.widget.VeloStoreItemTile;
import net.veloclient.velo.client.store.StoreCatalog;
import net.veloclient.velo.client.store.StoreCategory;
import net.veloclient.velo.client.store.StoreItem;

import java.util.List;
import java.util.function.Consumer;

/**
 * Locker > Cloaks while the offline unlock is active: every catalog cloak that isn't in the cape library yet is listed
 * as owned, and a click imports it (bundled art right away, server art through the public cosmetics download the Store
 * already uses) and wears it - the same as the Store page's Equip. Nothing is downloaded up front beyond the small card
 * pictures the Store grid shows too; the full GIF only for the one hovered or clicked. Gate off: nothing is added.
 */
public final class OfflineLockerCloaks {

	public static final String LOCKER_DOWNLOADING = "Downloading the cloak... click it again in a moment";

	private OfflineLockerCloaks() {
	}

	/** Catalog cloaks not in the library yet - empty unless the offline unlock is active. */
	public static List<StoreItem> missing() {
		if (!OfflineCosmetics.isActive()) {
			return List.of();
		}
		List<String> names = CapeManager.library().values().stream().filter(CapeDefinition::animated).map(CapeDefinition::name).toList();
		List<StoreItem> capes = StoreCatalog.all().stream().filter(item -> item.category() == StoreCategory.CAPES).toList();
		return OfflineGate.notInLibrary(capes, StoreItem::name, names);
	}

	/** The Cloaks heading's "N OWNED": the library plus the catalog cloaks listed by {@link #addTiles}. */
	public static int ownedCount(int librarySize) {
		return librarySize + missing().size();
	}

	/** Adds one tile per {@link #missing()} cloak, sized to the Locker grid's cells; {@code status} gets the feedback. */
	public static void addTiles(int cellWidth, int cellHeight, Consumer<VeloStoreItemTile> add, Consumer<String> status) {
		int iconHeight = Math.max(16, cellHeight - StoreCard.INFO_H);
		for (StoreItem item : missing()) {
			add.accept(new VeloStoreItemTile(0, 0, cellWidth, iconHeight, item, () -> equip(item, status)));
		}
	}

	private static void equip(StoreItem item, Consumer<String> status) {
		if (!OfflineCosmetics.isActive()) {
			return;
		}
		String id = OfflineCosmetics.importCape(item.id());
		if (id == null) {
			status.accept(LOCKER_DOWNLOADING);
			return;
		}
		CapeManager.equip(id);
		status.accept("Equipped \"" + item.name() + "\"");
	}
}
