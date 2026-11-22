package net.veloclient.velo.client.offline;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The offline-cosmetics decision with no Minecraft or Velo types, so the launcher's unit tests can compile and test
 * this one file (see launcher/build.gradle, "offline-cosmetics").
 */
final class OfflineGate {

	static final String PROPERTY = "velo.offlineCosmetics";

	private OfflineGate() {
	}

	/** On only for the flag exactly "true", a real offline game session, and no Velo Network session token. */
	static boolean decide(String flag, boolean offlineGameSession, String veloSessionToken) {
		return "true".equals(flag) && offlineGameSession && veloSessionToken == null;
	}

	/**
	 * Catalog cloaks with no copy in the cloak library yet, matched by name like the Store's own equip does (an import
	 * gets a fresh library id, its name is the item's name). Keeps catalog order; null names never match.
	 */
	static <T> java.util.List<T> notInLibrary(java.util.List<T> catalog, java.util.function.Function<T, String> name,
			java.util.Collection<String> libraryNames) {
		java.util.Set<String> have = new java.util.HashSet<>(libraryNames);
		java.util.List<T> missing = new java.util.ArrayList<>();
		for (T item : catalog) {
			String itemName = name.apply(item);
			if (itemName == null || !have.contains(itemName)) {
				missing.add(item);
			}
		}
		return missing;
	}

	/** Skin library ids are 32 lowercase hex characters (a dashless UUID); anything else is never turned into a path. */
	static boolean validSkinId(String id) {
		return id != null && id.matches("[0-9a-f]{32}");
	}

	static final long MAX_PNG_BYTES = 1024 * 1024;
	private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

	/** {@code <dir>/<id>.png} for a valid id, or null when the id isn't one or the path would leave {@code dir}. */
	static Path safePng(Path dir, String id) {
		if (!validSkinId(id)) {
			return null;
		}
		Path base = dir.toAbsolutePath().normalize();
		Path png = base.resolve(id + ".png").normalize();
		return png.startsWith(base) ? png : null;
	}

	/** A PNG of at most 1 MB whose header (signature, IHDR) says 64x64 or 64x32. */
	static boolean validPng(Path png) {
		try {
			if (png == null || !Files.isRegularFile(png) || Files.size(png) > MAX_PNG_BYTES) {
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
}
