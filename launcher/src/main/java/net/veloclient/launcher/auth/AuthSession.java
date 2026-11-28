package net.veloclient.launcher.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.veloclient.launcher.data.VeloPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persists every signed-in account's refresh token + last-known profile to
 * {@code ~/.velo-client/config/account.json} (multiple accounts, one marked
 * active), and re-authenticates the active one silently on startup when
 * possible so the user doesn't have to enter the device code every launch.
 * The Minecraft access token itself is short-lived and always re-derived
 * from the refresh token rather than cached long-term.
 *
 * <p>Transparently migrates the older single-account file format (a bare
 * {@code MinecraftSession} object, from before account switching existed)
 * into a one-account version of the new format the first time it's read.
 */
public final class AuthSession {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String CONFIG_ID = "account.json";

	/**
	 * The {@code account.json} shape. Field names are a contract with the game mod (ActiveAccountReader,
	 * VeloAccountAuth), so they must never be renamed. Each account may carry {@code "type"} ({@code "MICROSOFT"} or
	 * {@code "OFFLINE"}); older entries have none and load as Microsoft accounts with no rewrite.
	 */
	record AccountsFile(List<MinecraftSession> accounts, String activeUuid) {
	}

	private AuthSession() {
	}

	/** The currently-active account, if any are saved. */
	public static Optional<MinecraftSession> loadCached() {
		AccountsFile file = loadForRead();
		return file.accounts().stream().filter(a -> a.uuid().equals(file.activeUuid())).findFirst()
				.or(() -> file.accounts().stream().findFirst());
	}

	/** Every saved account, active one first. */
	public static List<MinecraftSession> loadAllAccounts() {
		AccountsFile file = loadForRead();
		List<MinecraftSession> ordered = new ArrayList<>(file.accounts());
		ordered.sort((a, b) -> Boolean.compare(!a.uuid().equals(file.activeUuid()), !b.uuid().equals(file.activeUuid())));
		return ordered;
	}

	/** Adds/updates an account by uuid (e.g. after a fresh sign-in or a token refresh) and marks it active. */
	public static void save(MinecraftSession session) {
		AccountsFile file = loadForWrite();
		if (file == null) {
			return;
		}
		List<MinecraftSession> accounts = new ArrayList<>(file.accounts());
		accounts.removeIf(a -> a.uuid().equals(session.uuid()));
		accounts.add(session);
		write(new AccountsFile(accounts, session.uuid()));
	}

	/** Switches the active account to an already-saved one (the account switcher dropdown), without re-authenticating. */
	public static Optional<MinecraftSession> switchTo(String uuid) {
		AccountsFile file = loadForWrite();
		if (file == null) {
			return loadAllAccounts().stream().filter(a -> a.uuid().equals(uuid)).findFirst();
		}
		Optional<MinecraftSession> match = file.accounts().stream().filter(a -> a.uuid().equals(uuid)).findFirst();
		match.ifPresent(m -> write(new AccountsFile(file.accounts(), uuid)));
		return match;
	}

	/**
	 * Signs out one account. If other saved accounts remain, the first of
	 * them becomes active and is returned so the caller can switch straight
	 * into it instead of dropping back to "not signed in".
	 */
	public static Optional<MinecraftSession> remove(String uuid) {
		AccountsFile file = loadForWrite();
		if (file == null) {
			return Optional.empty();
		}
		List<MinecraftSession> remaining = file.accounts().stream().filter(a -> !a.uuid().equals(uuid)).toList();
		String newActive = remaining.isEmpty() ? null : remaining.get(0).uuid();
		write(new AccountsFile(remaining, newActive));
		return remaining.stream().findFirst();
	}

	/** Wipes every saved account. */
	public static void clear() {
		write(new AccountsFile(List.of(), null));
	}

	private static final String BACKUP_SUFFIX = ".bak";

	/** For readers: whatever could be read, else nothing. Never writes. */
	private static AccountsFile loadForRead() {
		AccountsFile file = tryLoad();
		return file != null ? file : new AccountsFile(List.of(), null);
	}

	/**
	 * For writers: every writer reads, changes and writes the whole file, so a read that failed must not turn into
	 * "no accounts" and overwrite the saved ones. Returns null (do not write) when the file exists but could not be
	 * read; a file that is plain corrupt (and has no usable backup) is set aside and treated as empty so signing in
	 * is never blocked.
	 */
	private static AccountsFile loadForWrite() {
		AccountsFile file = tryLoad();
		if (file != null) {
			return file;
		}
		Path path = VeloPaths.config().resolve(CONFIG_ID);
		if (Files.exists(path) && lastFailureWasCorruption) {
			try {
				Files.move(path, path.resolveSibling(CONFIG_ID + ".corrupt-" + System.currentTimeMillis()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				net.veloclient.launcher.LauncherLog.warn("Could not set aside the unreadable account file", e);
				return null;
			}
			return new AccountsFile(List.of(), null);
		}
		net.veloclient.launcher.LauncherLog.warn("Saved accounts could not be read right now; not overwriting them", null);
		return null;
	}

	private static volatile boolean lastFailureWasCorruption;

	/** Missing file = no accounts. A read error is retried; a bad file falls back to account.json.bak. Null if neither worked. */
	private static AccountsFile tryLoad() {
		Path path = VeloPaths.config().resolve(CONFIG_ID);
		if (!Files.exists(path)) {
			return new AccountsFile(List.of(), null);
		}
		boolean corrupt = false;
		for (int attempt = 1; attempt <= 3; attempt++) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				return parse(JsonParser.parseReader(reader));
			} catch (JsonSyntaxException e) {
				corrupt = true;
				break; // retrying a bad file does not help
			} catch (IOException e) {
				corrupt = false;
				if (attempt < 3) {
					try {
						Thread.sleep(100L * attempt);
					} catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						break;
					}
				}
			}
		}
		Path backup = path.resolveSibling(CONFIG_ID + BACKUP_SUFFIX);
		if (Files.exists(backup)) {
			try (Reader reader = Files.newBufferedReader(backup, StandardCharsets.UTF_8)) {
				AccountsFile restored = parse(JsonParser.parseReader(reader));
				net.veloclient.launcher.LauncherLog.warn("account.json could not be read; using account.json.bak", null);
				return restored;
			} catch (IOException | JsonSyntaxException ignored) {
				// no usable backup either
			}
		}
		lastFailureWasCorruption = corrupt;
		return null;
	}

	/** Parses {@code account.json} content (current or legacy single-object format). Package-private for tests. */
	static AccountsFile parse(JsonElement root) {
		if (root == null || root.isJsonNull()) {
			return new AccountsFile(List.of(), null);
		}
		if (root.isJsonObject() && root.getAsJsonObject().has("accounts")) {
			AccountsFile parsed = GSON.fromJson(root, AccountsFile.class);
			if (parsed == null) {
				return new AccountsFile(List.of(), null);
			}
			return new AccountsFile(healed(parsed.accounts()), parsed.activeUuid());
		}
		// Legacy pre-multi-account format: a bare MinecraftSession (always a Microsoft account).
		MinecraftSession legacy = GSON.fromJson(root, MinecraftSession.class);
		return legacy == null ? new AccountsFile(List.of(), null) : new AccountsFile(List.of(legacy), legacy.uuid());
	}

	/** Drops entries without a uuid and puts the offline type back on entries that lost it. Package-private for tests. */
	static List<MinecraftSession> healed(List<MinecraftSession> accounts) {
		List<MinecraftSession> out = new ArrayList<>();
		if (accounts == null) {
			return out;
		}
		for (MinecraftSession a : accounts) {
			if (a == null || a.uuid() == null) {
				continue;
			}
			out.add(a.lostItsOfflineType() ? a.asOffline() : a);
		}
		return out;
	}

	/** Serialises the file exactly as {@link #write} does. Package-private for tests. */
	static String toJson(AccountsFile file) {
		return GSON.toJson(file);
	}

	/**
	 * Writes to a temporary file and moves it over the real one, so a launcher that's killed (or exits) mid-write
	 * never leaves the sign-in empty or half written.
	 */
	private static void write(AccountsFile file) {
		VeloPaths.ensureDirectories();
		Path path = VeloPaths.config().resolve(CONFIG_ID);
		Path tmp = path.resolveSibling(CONFIG_ID + ".tmp");
		try {
			try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(file, writer);
			}
			try {
				if (Files.exists(path) && Files.size(path) > 2) {
					Files.copy(path, path.resolveSibling(CONFIG_ID + BACKUP_SUFFIX), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (IOException ignored) {
				// the backup is a bonus
			}
			moveIntoPlace(tmp, path);
		} catch (IOException e) {
			// Never fatal: the signed-in session still works for this run, it just has to sign in again next start.
			net.veloclient.launcher.LauncherLog.warn("Failed to save account session", e);
		}
	}

	/**
	 * Windows refuses to replace a file another process (antivirus, OneDrive, a second launcher) has open for a
	 * moment, so retry briefly and fall back to writing the file in place.
	 */
	private static void moveIntoPlace(Path tmp, Path path) throws IOException {
		IOException last = null;
		for (int attempt = 0; attempt < 5; attempt++) {
			try {
				try {
					Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
				} catch (java.nio.file.AtomicMoveNotSupportedException e) {
					Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
				return;
			} catch (IOException e) {
				last = e;
				try {
					Thread.sleep(60L * (attempt + 1));
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		try {
			Files.write(path, Files.readAllBytes(tmp));
			Files.deleteIfExists(tmp);
		} catch (IOException e) {
			e.addSuppressed(last);
			throw e;
		}
	}
}
