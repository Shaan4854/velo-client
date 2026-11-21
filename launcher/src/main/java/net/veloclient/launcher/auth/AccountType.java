package net.veloclient.launcher.auth;

/**
 * Which kind of account a saved {@link MinecraftSession} is. Stored as the {@code "type"} field in
 * {@code account.json}; entries written before this existed have no field and load as {@link #MICROSOFT}.
 */
public enum AccountType {
	/** Signed in through Microsoft / Xbox Live; has real Minecraft and refresh tokens. */
	MICROSOFT,
	/**
	 * A local offline profile (vanilla offline-mode style): a typed username and a name-derived v3 UUID, no tokens.
	 * Works in singleplayer, LAN and {@code online-mode=false} servers only.
	 */
	OFFLINE
}
