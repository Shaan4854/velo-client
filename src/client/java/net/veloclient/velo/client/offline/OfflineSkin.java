package net.veloclient.velo.client.offline;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A custom skin for offline accounts, worn only in this client: the PNG comes from the shared skin library
 * ({@code ~/.velo-client/skins}), the choice is per account in {@code config/accounts/<uuid>/offline-skin.json}
 * ({@code {"skinId": "...", "slim": false}}), written by the launcher's Skins tab or the in-game Locker. Nothing is
 * uploaded anywhere; it rides on {@code SkinTryOn.setEquipped}, which only changes how your own player is drawn.
 */
public final class OfflineSkin {

	public static final String FILE = "offline-skin.json";
	public static final String EQUIPPED_MESSAGE = "Equipped locally - only you see it (offline account).";

	public record Choice(String skinId, boolean slim) {
	}

	private static volatile boolean dirty = true;
	/** The choice last read or saved (for the Locker's "Equipped" state). */
	private static volatile Choice current;
	private static long stamp = Long.MIN_VALUE;
	private static int ticks;

	private OfflineSkin() {
	}

	static Path file() {
		return net.veloclient.velo.config.VeloPaths.accountConfig(FILE);
	}

	/** The saved choice, or null when there's none or it's unreadable. */
	static Choice read(Path file) {
		try {
			if (!Files.isRegularFile(file)) {
				return null;
			}
			JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			String id = json.has("skinId") && !json.get("skinId").isJsonNull() ? json.get("skinId").getAsString() : null;
			if (id == null || id.isBlank()) {
				return null;
			}
			return new Choice(id, json.has("slim") && json.get("slim").getAsBoolean());
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	static void write(Path file, Choice choice) throws IOException {
		Files.createDirectories(file.getParent());
		JsonObject json = new JsonObject();
		json.addProperty("skinId", choice.skinId());
		json.addProperty("slim", choice.slim());
		Path tmp = file.resolveSibling(FILE + ".tmp");
		Files.writeString(tmp, new Gson().toJson(json), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** A PNG of at most 1 MB whose header says 64x64 or 64x32 (the decode itself happens when it's registered). */
	static boolean validPng(Path png) {
		return OfflineGate.validPng(png);
	}

	/** Locker "Equip" in an offline game with the unlock on: remember the choice; the next tick wears it. */
	public static void equipLocal(net.veloclient.velo.client.skins.SkinLibrary.Skin skin) throws IOException {
		Path png = safePng(skin);
		if (png == null || !validPng(png)) {
			throw new IOException("Skins need a 64x64 or 64x32 PNG up to 1 MB");
		}
		Choice choice = new Choice(skin.id(), skin.slim());
		write(file(), choice);
		current = choice;
		dirty = true;
	}

	/** The Locker's equipped skin id: the offline choice while the unlock is on, else {@code fallback} (the account's). */
	public static String equippedIdOr(String fallback) {
		if (!OfflineCosmetics.isActive()) {
			return fallback;
		}
		Choice choice = current;
		return choice == null ? null : choice.skinId();
	}

	/** The skin's PNG in the skin library, or null for an id that isn't a plain library id or would leave that folder. */
	static Path safePng(net.veloclient.velo.client.skins.SkinLibrary.Skin skin) {
		return safePng(net.veloclient.velo.client.skins.SkinLibrary.dir(), skin.id());
	}

	static Path safePng(Path dir, String id) {
		return OfflineGate.safePng(dir, id);
	}

	/** Client tick: wears the saved choice once, then again whenever the file changes (launcher Equip). */
	static void tick() {
		if (!OfflineCosmetics.isActive()) {
			return;
		}
		if (!dirty && ++ticks % 10 != 0) {
			return;
		}
		long now = stamp(file());
		if (!dirty && now == stamp) {
			return;
		}
		dirty = false;
		stamp = now;
		apply();
	}

	private static long stamp(Path file) {
		try {
			return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : -1;
		} catch (IOException e) {
			return -1;
		}
	}

	/** Render thread only: registers the PNG as a texture and wears it on your own player (or the default skin). */
	private static void apply() {
		Choice choice = read(file());
		current = choice;
		if (choice == null) {
			net.veloclient.velo.client.skins.SkinTryOn.setEquipped(null, false);
			return;
		}
		var skin = net.veloclient.velo.client.skins.SkinLibrary.all().stream().filter(s -> s.id().equals(choice.skinId())).findFirst().orElse(null);
		Path png = skin == null ? null : safePng(skin);
		if (png == null || !validPng(png)) {
			net.veloclient.velo.VeloClient.LOGGER.info("Offline skin {} is missing or not a 64x64/64x32 PNG up to 1 MB - default skin", choice.skinId());
			net.veloclient.velo.client.skins.SkinTryOn.setEquipped(null, false);
			return;
		}
		var texture = net.veloclient.velo.client.gui.VeloSkinsScreen.texture(skin);
		net.veloclient.velo.client.skins.SkinTryOn.setEquipped(texture, texture != null && choice.slim());
		net.veloclient.velo.client.gui.widget.SkinModelPreview.refreshCurrent();
	}
}
