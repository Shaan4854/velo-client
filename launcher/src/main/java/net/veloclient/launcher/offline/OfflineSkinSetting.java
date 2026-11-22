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
}
