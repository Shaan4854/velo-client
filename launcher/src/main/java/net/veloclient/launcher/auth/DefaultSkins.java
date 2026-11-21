package net.veloclient.launcher.auth;

import net.veloclient.launcher.data.VeloPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The default Steve/Alex skin for offline accounts (which have no Mojang profile). The PNGs come from a vanilla
 * {@code client.jar} the launcher already downloaded (no network, nothing Mojang-owned bundled in this repo) and are
 * cached under {@code cache/skins/default-*.png}. Before any version was downloaded there is no default skin and the
 * usual initial-letter placeholder shows.
 */
public final class DefaultSkins {

	private static final String[] STEVE = {"assets/minecraft/textures/entity/player/wide/steve.png", "assets/minecraft/textures/entity/steve.png"};
	private static final String[] ALEX = {"assets/minecraft/textures/entity/player/slim/alex.png", "assets/minecraft/textures/entity/alex.png"};

	private DefaultSkins() {
	}

	/**
	 * Vanilla's classic Steve/Alex pick for a profile id ({@code (uuid.hashCode() & 1) == 1} is Alex), as used by
	 * 1.8-1.19.2. Newer game versions pick from more default skins, so in-game it can differ; the launcher only shows
	 * Steve or Alex.
	 */
	public static boolean isSlim(String dashlessOrDashedUuid) {
		try {
			return (parse(dashlessOrDashedUuid).hashCode() & 1) == 1;
		} catch (RuntimeException e) {
			return false;
		}
	}

	static UUID parse(String uuid) {
		String s = uuid.replace("-", "");
		if (s.length() != 32) {
			throw new IllegalArgumentException("uuid");
		}
		return new UUID(Long.parseUnsignedLong(s.substring(0, 16), 16), Long.parseUnsignedLong(s.substring(16), 16));
	}

	/** The default skin for this profile id, or null when no vanilla client jar is available yet. */
	public static SkinFetcher.SkinData forUuid(String uuid) {
		boolean slim = isSlim(uuid);
		byte[] png = load(slim);
		return png == null ? null : new SkinFetcher.SkinData(png, slim);
	}

	private static byte[] load(boolean slim) {
		Path cache = VeloPaths.root().resolve("cache").resolve("skins").resolve(slim ? "default-alex.png" : "default-steve.png");
		try {
			if (Files.exists(cache)) {
				return Files.readAllBytes(cache);
			}
		} catch (IOException ignored) {
			// fall through to the jar
		}
		for (Path jar : clientJars()) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (String name : slim ? ALEX : STEVE) {
					ZipEntry entry = zip.getEntry(name);
					if (entry == null) {
						continue;
					}
					try (InputStream in = zip.getInputStream(entry)) {
						byte[] png = in.readAllBytes();
						try {
							Files.createDirectories(cache.getParent());
							Files.write(cache, png);
						} catch (IOException ignored) {
							// still usable this run
						}
						return png;
					}
				}
			} catch (IOException ignored) {
				// a half-downloaded jar: try the next one
			}
		}
		return null;
	}

	private static List<Path> clientJars() {
		List<Path> jars = new ArrayList<>();
		Path versions = VeloPaths.sharedRoot().resolve("gamedata").resolve("versions");
		if (!Files.isDirectory(versions)) {
			return jars;
		}
		try (DirectoryStream<Path> dirs = Files.newDirectoryStream(versions)) {
			for (Path dir : dirs) {
				Path jar = dir.resolve("client.jar");
				if (Files.isRegularFile(jar)) {
					jars.add(jar);
				}
			}
		} catch (IOException ignored) {
			// none
		}
		return jars;
	}
}
