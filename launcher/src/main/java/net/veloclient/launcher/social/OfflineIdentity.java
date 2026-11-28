package net.veloclient.launcher.social;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import net.veloclient.launcher.data.VeloPaths;

/**
 * The secret that proves "same install" to the fork backend for an offline name (trust on first use). One random
 * secret per launcher install; offline games get it as {@code -Dvelo.offlineSecret} so the launcher and the game
 * claim the same name with the same secret.
 */
public final class OfflineIdentity {

	private OfflineIdentity() {
	}

	public static synchronized String secret() {
		Path file = VeloPaths.config().resolve("offline-identity.key");
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
