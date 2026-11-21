package net.veloclient.launcher.auth;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Offline (non-Microsoft) accounts: a typed username plus the UUID vanilla offline mode derives from it. No
 * Microsoft, Xbox or Mojang calls are ever made for these. They work in singleplayer, LAN and
 * {@code online-mode=false} servers only, exactly like vanilla offline mode.
 */
public final class OfflineAccounts {

	/** Vanilla-compatible usernames: 3-16 letters, digits or underscores. */
	public static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
	private static final Pattern ALLOWED_CHARS = Pattern.compile("^[A-Za-z0-9_]*$");

	/** Access token passed to the game for offline launches (shorter than 32 chars, so the mod treats it as offline). */
	public static final String OFFLINE_ACCESS_TOKEN = "0";
	/** {@code ${user_type}} for offline launches; vanilla's value for non-Microsoft sessions. */
	public static final String OFFLINE_USER_TYPE = "legacy";

	private OfflineAccounts() {
	}

	/**
	 * Vanilla's offline UUID: {@code UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(UTF_8))}, a version-3
	 * UUID, returned dashless and lowercase like Microsoft profile ids. Case-sensitive (the name is not lower-cased).
	 */
	public static String offlineUuid(String name) {
		if (name == null) {
			throw new IllegalArgumentException("name");
		}
		return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
	}

	/** Returns an error message for an invalid username (after trimming), or empty when it is valid. */
	public static Optional<String> validate(String name) {
		String n = name == null ? "" : name.trim();
		if (n.isEmpty()) {
			return Optional.of("Enter a username");
		}
		if (!ALLOWED_CHARS.matcher(n).matches()) {
			return Optional.of("Only letters, numbers and _");
		}
		if (n.length() < 3 || n.length() > 16) {
			return Optional.of("3-16 characters");
		}
		return USERNAME.matcher(n).matches() ? Optional.empty() : Optional.of("Only letters, numbers and _");
	}

	/**
	 * Full check before creating: {@link #validate} plus a case-insensitive duplicate check against the saved offline
	 * accounts ({@code Steve} and {@code steve} can't both exist). Microsoft accounts with the same name are allowed.
	 */
	public static Optional<String> validateNew(String name, Collection<MinecraftSession> existing) {
		Optional<String> error = validate(name);
		if (error.isPresent()) {
			return error;
		}
		return findOfflineByName(name, existing).map(a -> "An offline account named " + a.username() + " already exists");
	}

	/** A saved offline account whose name equals {@code name} ignoring case. */
	public static Optional<MinecraftSession> findOfflineByName(String name, Collection<MinecraftSession> existing) {
		if (name == null || existing == null) {
			return Optional.empty();
		}
		String key = name.trim().toLowerCase(Locale.ROOT);
		return existing.stream().filter(a -> a != null && a.isOffline() && a.username() != null
				&& a.username().toLowerCase(Locale.ROOT).equals(key)).findFirst();
	}

	/** True when a saved Microsoft account already uses this name (ignoring case) - allowed, but worth a warning. */
	public static boolean matchesMicrosoftName(String name, Collection<MinecraftSession> existing) {
		if (name == null || existing == null) {
			return false;
		}
		String key = name.trim().toLowerCase(Locale.ROOT);
		return existing.stream().anyMatch(a -> a != null && a.isMicrosoft() && a.username() != null
				&& a.username().toLowerCase(Locale.ROOT).equals(key));
	}

	/**
	 * Builds an offline account: null tokens, an expiry that never passes, the offline UUID and the name as typed
	 * (trimmed). Throws {@link IllegalArgumentException} for an invalid name, so the rule is enforced here and not only
	 * in the UI.
	 */
	public static MinecraftSession create(String name) {
		Optional<String> error = validate(name);
		if (error.isPresent()) {
			throw new IllegalArgumentException(error.get());
		}
		String n = name.trim();
		return new MinecraftSession(null, Long.MAX_VALUE, null, offlineUuid(n), n, AccountType.OFFLINE);
	}
}
