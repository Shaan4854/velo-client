package net.veloclient.launcher.offline;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The local skin choice of an offline account: {@code config/accounts/<uuid>/offline-skin.json} =
 * {@code {"skinId": "<skin library id>", "slim": false}}. The game (with the unlock on) wears that library PNG on your own
 * player only; nothing is uploaded.
 */
public final class OfflineSkinSetting {

	public static final String FILE = "offline-skin.json";
	static final String INVALID = "Can't equip this skin - it needs to be a 64x64 or 64x32 PNG up to 1 MB.";
	static final long MAX_PNG_BYTES = 1024 * 1024;
	private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

	public record Choice(String skinId, boolean slim) {
	}

	private OfflineSkinSetting() {
	}

	static Path file() {
		return net.veloclient.launcher.data.VeloPaths.accountConfig(FILE);
	}

	/** The library id this offline account wears locally, or null. */
	public static String equippedId() {
		Choice choice = read(file());
		return choice == null ? null : choice.skinId();
	}

	/** Skins > Equip for an offline account: saves the choice and returns the status line to show. */
	public static String equip(net.veloclient.launcher.data.SkinLibrary.Skin skin) {
		Path png = safePng(net.veloclient.launcher.data.SkinLibrary.dir(), skin.id());
		if (png == null || !validPng(png)) {
			return INVALID;
		}
		try {
			write(file(), new Choice(skin.id(), skin.slim()));
			return "Equipped locally - only you see it (offline account).";
		} catch (IOException | RuntimeException e) {
			return "Couldn't save the skin: " + e.getMessage();
		}
	}

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

	/** {@code <dir>/<id>.png} for a skin library id (32 lowercase hex), or null when the id isn't one or leaves {@code dir}. */
	static Path safePng(Path dir, String id) {
		if (id == null || !id.matches("[0-9a-f]{32}")) {
			return null;
		}
		Path base = dir.toAbsolutePath().normalize();
		Path png = base.resolve(id + ".png").normalize();
		return png.startsWith(base) ? png : null;
	}

	/** Same check as the game (OfflineSkin): a PNG of at most 1 MB whose header says 64x64 or 64x32. */
	static boolean validPng(Path png) {
		try {
			if (png == null || !Files.isRegularFile(png) || Files.size(png) > MAX_PNG_BYTES) {
				return false;
			}
			byte[] head = new byte[24];
			try (java.io.InputStream in = Files.newInputStream(png)) {
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
}
