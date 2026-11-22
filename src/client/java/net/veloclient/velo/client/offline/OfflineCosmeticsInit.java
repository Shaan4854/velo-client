package net.veloclient.velo.client.offline;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/** Separate entrypoint (fabric.mod.json) so VeloClientMod stays untouched: drives {@link OfflineSkin}. */
public final class OfflineCosmeticsInit implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		// Cheap when off: OfflineCosmetics.isActive() returns at once without the launcher's flag.
		ClientTickEvents.END_CLIENT_TICK.register(client -> OfflineSkin.tick());
	}
}
