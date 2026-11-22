package net.veloclient.launcher.auth;

/**
 * When the launcher may refresh a saved account with Microsoft, and when a failed refresh may delete it. Offline
 * accounts (and any entry without a refresh token, e.g. an unknown or hand-edited {@code "type"}) are never refreshed
 * and never removed by the "dead sign-in" path.
 */
public final class AccountRefreshPolicy {

	private AccountRefreshPolicy() {
	}

	/** A Microsoft account with a refresh token. */
	public static boolean canRefresh(MinecraftSession session) {
		return session != null && !session.isOffline() && session.microsoftRefreshToken() != null
				&& !session.microsoftRefreshToken().isBlank();
	}

	/** Startup / token refresher: refresh only a refreshable account whose token ran out. */
	public static boolean refreshOnStartup(MinecraftSession session) {
		return canRefresh(session) && session.isAccessTokenExpired();
	}

	/** Before launching: refresh only a refreshable account with under 6 hours left. */
	public static boolean refreshBeforeLaunch(MinecraftSession session) {
		return canRefresh(session) && session.tooShortForGame();
	}

	/**
	 * After a failed refresh: remove the account only when Microsoft really refused its refresh token
	 * ({@code accountDead}) and it is a refreshable Microsoft account. Offline accounts are never removed here.
	 */
	public static boolean removeAfterFailedRefresh(MinecraftSession session, boolean accountDead) {
		return accountDead && canRefresh(session);
	}
}
