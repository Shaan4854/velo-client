package net.veloclient.launcher.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Prepares the per-launch natives directory. Minecraft versions this
 * launcher supports (1.21+/26.x) ship LWJGL natives as ordinary per-platform
 * library jars that get put straight on the classpath and self-extract at
 * runtime, so this normally only needs to create an empty directory - the
 * legacy "natives"+"downloads.classifiers" library shape (pre-1.19) is
 * handled defensively in case it's ever encountered.
 */
public final class NativesExtractor {

	private NativesExtractor() {
	}

	public static Path prepare(Path nativesDir, JsonArray vanillaLibraries) throws IOException {
		return prepare(nativesDir, vanillaLibraries, null);
	}

	/** Deletes one path; a seam so tests can simulate Windows holding a DLL open. */
	interface Deleter {
		void delete(Path path) throws IOException;
	}

	/**
	 * Empties {@code dir}, retrying with a growing pause when Windows (a leftover game process, or antivirus scanning
	 * the DLL it just saw) refuses a delete. Returns false when something stayed locked after every attempt.
	 */
	static boolean clearDir(Path dir, int attempts, long pauseMillis, Deleter deleter) {
		if (!Files.exists(dir)) {
			return true;
		}
		for (int attempt = 1; attempt <= attempts; attempt++) {
			boolean allGone = true;
			try (var stream = Files.walk(dir)) {
				for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
					try {
						deleter.delete(path);
					} catch (IOException e) {
						allGone = false;
					}
				}
			} catch (IOException e) {
				allGone = false;
			}
			if (allGone) {
				return true;
			}
			if (attempt < attempts) {
				try {
					Thread.sleep(pauseMillis * attempt);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return false;
				}
			}
		}
		return false;
	}

	/** Best effort: removes natives-xxxx folders an earlier launch left behind when it could not reuse natives/. */
	private static void removeStaleFallbacks(Path nativesDir, Path keep) {
		Path parent = nativesDir.getParent();
		if (parent == null || !Files.isDirectory(parent)) {
			return;
		}
		String prefix = nativesDir.getFileName() + "-";
		try (var siblings = Files.list(parent)) {
			for (Path sibling : siblings.filter(p -> p.getFileName().toString().startsWith(prefix) && !p.equals(keep)).toList()) {
				clearDir(sibling, 1, 0, Files::deleteIfExists);
			}
		} catch (IOException ignored) {
			// leftovers are harmless
		}
	}

	/**
	 * {@code loaderLibraries}: Legacy Fabric replaces LWJGL 2 with its own build whose natives sit next to it on its maven
	 * ({@code natives} map, no {@code downloads}) - unpacked after vanilla's so the matching ones win.
	 *
	 * <p>Returns the directory the game must use. Normally {@code nativesDir}; if a file in it stays locked after a few
	 * retries (Windows "access denied" on lwjgl.dll) a fresh {@code natives-<stamp>} folder is used for this launch
	 * instead of failing the whole launch, and old fallbacks are cleaned up on later launches.
	 */
	public static Path prepare(Path nativesDir, JsonArray vanillaLibraries, JsonArray loaderLibraries) throws IOException {
		Path target = nativesDir;
		if (!clearDir(nativesDir, 5, 300, Files::deleteIfExists)) {
			target = nativesDir.resolveSibling(nativesDir.getFileName() + "-" + Long.toString(System.currentTimeMillis(), 36));
			net.veloclient.launcher.LauncherLog.warn("natives folder is locked (a game or antivirus holds a file); using " + target.getFileName(), null);
		} else {
			removeStaleFallbacks(nativesDir, null);
		}
		nativesDir = target;
		Files.createDirectories(nativesDir);
		if (vanillaLibraries != null) {
			extractAll(nativesDir, vanillaLibraries);
		}
		if (loaderLibraries == null) {
			return nativesDir;
		}
		for (var element : loaderLibraries) {
			JsonObject library = element.getAsJsonObject();
			if (!library.has("natives") || library.has("downloads") || !library.has("name")) {
				continue;
			}
			JsonObject natives = library.getAsJsonObject("natives");
			String osKey = OsRules.currentOsName();
			if (!natives.has(osKey)) {
				continue;
			}
			String classifier = natives.get(osKey).getAsString().replace("${arch}", "64");
			String[] parts = library.get("name").getAsString().split(":");
			String base = library.has("url") ? library.get("url").getAsString() : "https://maven.fabricmc.net/";
			base = base.endsWith("/") ? base : base + "/";
			String path = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + parts[1] + "-" + parts[2] + "-" + classifier + ".jar";
			Path jarPath = GameDataPaths.libraries().resolve(path);
			Downloader.ensure(java.net.URI.create(base + path), jarPath, null, -1, b -> { });
			extractJar(jarPath, nativesDir);
		}
		return nativesDir;
	}

	private static void extractAll(Path nativesDir, JsonArray vanillaLibraries) throws IOException {
		for (var element : vanillaLibraries) {
			JsonObject library = element.getAsJsonObject();
			if (!library.has("natives") || !library.has("downloads")) {
				continue;
			}
			JsonObject downloads = library.getAsJsonObject("downloads");
			if (!downloads.has("classifiers")) {
				continue;
			}
			if (library.has("rules") && !OsRules.isAllowed(library.getAsJsonArray("rules"), OsRules.NO_FEATURES)) {
				continue;
			}
			JsonObject natives = library.getAsJsonObject("natives");
			String osKey = OsRules.currentOsName();
			if (!natives.has(osKey)) {
				continue;
			}
			String classifier = natives.get(osKey).getAsString().replace("${arch}", "64");
			JsonObject classifiers = downloads.getAsJsonObject("classifiers");
			if (!classifiers.has(classifier)) {
				continue;
			}
			JsonObject artifact = classifiers.getAsJsonObject(classifier);
			Path jarPath = GameDataPaths.libraries().resolve(artifact.get("path").getAsString());
			Downloader.ensure(java.net.URI.create(artifact.get("url").getAsString()), jarPath,
					artifact.has("sha1") ? artifact.get("sha1").getAsString() : null,
					artifact.has("size") ? artifact.get("size").getAsLong() : -1, b -> { });
			extractJar(jarPath, nativesDir);
		}
	}

	private static void extractJar(Path jar, Path destDir) throws IOException {
		try (JarFile jarFile = new JarFile(jar.toFile())) {
			Enumeration<JarEntry> entries = jarFile.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) {
					continue;
				}
				Path dest = destDir.resolve(entry.getName()).normalize();
				if (!dest.startsWith(destDir)) {
					continue;
				}
				Files.createDirectories(dest.getParent());
				try (InputStream in = jarFile.getInputStream(entry)) {
					Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}
}
