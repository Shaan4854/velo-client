package net.veloclient.velo.client.offline;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
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
	static final long MAX_BYTES = 1024 * 1024;
	private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

	public record Choice(String skinId, boolean slim) {
	}

	private static volatile boolean dirty = true;
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
		try {
			if (!Files.isRegularFile(png) || Files.size(png) > MAX_BYTES) {
				return false;
			}
			byte[] head = new byte[24];
			try (InputStream in = Files.newInputStream(png)) {
				if (in.readNBytes(head, 0, 24) != 24) {
					return false;
				}
			}
			for (int i = 0; i < PNG_SIGNATURE.length; i++) {
				if (head[i] != PNG_SIGNATURE[i]) {
					return false;
				}
			}
			if (head[12] != 'I' || head[13] != 'H' || head[14] != 'D' || head[15] != 'R') {
				return false;
			}
			int width = readInt(head, 16);
			int height = readInt(head, 20);
			return width == 64 && (height == 64 || height == 32);
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private static int readInt(byte[] b, int at) {
		return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | (b[at + 3] & 0xFF);
	}

	/** Locker "Equip" in an offline game with the unlock on: remember the choice; the next tick wears it. */
	public static void equipLocal(net.veloclient.velo.client.skins.SkinLibrary.Skin skin) throws IOException {
		write(file(), new Choice(skin.id(), skin.slim()));
		dirty = true;
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
		if (choice == null) {
			net.veloclient.velo.client.skins.SkinTryOn.setEquipped(null, false);
			return;
		}
		var skin = net.veloclient.velo.client.skins.SkinLibrary.all().stream().filter(s -> s.id().equals(choice.skinId())).findFirst().orElse(null);
		if (skin == null || !validPng(net.veloclient.velo.client.skins.SkinLibrary.png(skin))) {
			net.veloclient.velo.VeloClient.LOGGER.info("Offline skin {} is missing or not a 64x64/64x32 PNG up to 1 MB - default skin", choice.skinId());
			net.veloclient.velo.client.skins.SkinTryOn.setEquipped(null, false);
			return;
		}
		var texture = net.veloclient.velo.client.gui.VeloSkinsScreen.texture(skin);
		net.veloclient.velo.client.skins.SkinTryOn.setEquipped(texture, texture != null && choice.slim());
		net.veloclient.velo.client.gui.widget.SkinModelPreview.refreshCurrent();
	}
}
