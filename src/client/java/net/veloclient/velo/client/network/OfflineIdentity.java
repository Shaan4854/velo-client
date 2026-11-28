package net.veloclient.velo.client.network;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The secret that proves "this is the same install" to the fork backend for an offline name (trust on first use:
 * the first install to claim a name keeps it). One random secret per install, kept in the config folder.
 */
final class OfflineIdentity {

	private OfflineIdentity() {
	}

	static String secret() {
		String fromLauncher = System.getProperty("velo.offlineSecret"); // the launcher's own secret, so both claim with the same one
		if (fromLauncher != null && fromLauncher.length() >= 32) {
			return fromLauncher;
		}
		Path file = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("velo-client").resolve("offline-identity.key");
		try {
			if (Files.exists(file)) {
				String existing = Files.readString(file, StandardCharsets.UTF_8).trim();
				if (existing.length() >= 32) {
					return existing;
				}
			}
			byte[] bytes = new byte[32];
			new SecureRandom().nextBytes(bytes);
			String created = HexFormat.of().formatHex(bytes);
			Files.createDirectories(file.getParent());
			Files.writeString(file, created, StandardCharsets.UTF_8);
			return created;
		} catch (IOException e) {
			throw new IllegalStateException("Can't read or create the offline identity", e);
		}
	}
}
