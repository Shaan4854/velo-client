package net.veloclient.launcher.auth;

/**
 * A fully-authenticated Minecraft session: what's needed to show account
 * info in the launcher (and, eventually, to launch the game with
 * {@code --accessToken}/{@code --uuid}/{@code --username}).
 *
 * <p>{@code type} tells Microsoft accounts and offline accounts apart (see {@link AccountType}). It is additive in
 * {@code account.json}: entries saved before it existed have no {@code "type"} and load as
 * {@link AccountType#MICROSOFT}. Offline accounts have null tokens and never expire.
 */
public record MinecraftSession(
		String minecraftAccessToken,
		long accessTokenExpiresAtEpochMillis,
		String microsoftRefreshToken,
		String uuid,
		String username,
		AccountType type) {

	/** A Microsoft account (what {@link MicrosoftAuth} builds). */
	public MinecraftSession(String minecraftAccessToken, long accessTokenExpiresAtEpochMillis, String microsoftRefreshToken,
			String uuid, String username) {
		this(minecraftAccessToken, accessTokenExpiresAtEpochMillis, microsoftRefreshToken, uuid, username, AccountType.MICROSOFT);
	}

	/** Never null: entries from before the field existed are Microsoft accounts. */
	@Override
	public AccountType type() {
		return type == null ? AccountType.MICROSOFT : type;
	}

	public boolean isOffline() {
		return type() == AccountType.OFFLINE;
	}

	/**
	 * An entry with no access token and no refresh token cannot be a working Microsoft account. It is an offline entry
	 * that lost its {@code "type"} (an older launcher that does not know the field rewrote account.json without it).
	 */
	public boolean lostItsOfflineType() {
		return !isOffline() && (minecraftAccessToken == null || minecraftAccessToken.isBlank())
				&& (microsoftRefreshToken == null || microsoftRefreshToken.isBlank())
				&& uuid != null && username != null && !username.isBlank();
	}

	/** The same entry with its offline type put back. */
	public MinecraftSession asOffline() {
		return new MinecraftSession(null, Long.MAX_VALUE, null, uuid, username, AccountType.OFFLINE);
	}

	public boolean isMicrosoft() {
		return type() == AccountType.MICROSOFT;
	}

	public boolean isAccessTokenExpired() {
		if (isOffline()) {
			return false;
		}
		return System.currentTimeMillis() >= accessTokenExpiresAtEpochMillis - 30_000;
	}

	/**
	 * For starting the game: the game keeps this token for the whole session and uses it on every server join, so it
	 * must have hours left, not seconds. Starting with one that ran out soon after made joins fail with "Invalid
	 * session" / "Failed to log in" (players and the owner, 2026-11-21) - now anything under 6 hours is refreshed first.
	 */
	public boolean tooShortForGame() {
		if (isOffline()) {
			return false;
		}
		return System.currentTimeMillis() >= accessTokenExpiresAtEpochMillis - 6L * 3_600_000L;
	}
}
