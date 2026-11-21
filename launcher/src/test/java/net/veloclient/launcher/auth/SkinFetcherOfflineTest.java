package net.veloclient.launcher.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Runs with user.home pointed at build/test-home (see launcher/build.gradle), so no real launcher data is touched. */
class SkinFetcherOfflineTest {

	private final AtomicInteger calls = new AtomicInteger();
	private SkinFetcher.ProfileApi original;

	@BeforeEach
	void stubProfileApi() {
		original = SkinFetcher.profileApi;
		SkinFetcher.profileApi = token -> {
			calls.incrementAndGet();
			throw new IOException("stubbed - no network in tests");
		};
	}

	@AfterEach
	void restore() {
		SkinFetcher.profileApi = original;
	}

	@Test
	void offlineAccountMakesNoProfileCall() {
		MinecraftSession off = OfflineAccounts.create("SkinTestOffline");
		SkinFetcher.fetch(off);
		assertEquals(0, calls.get());
	}

	@Test
	void entryWithoutTokenMakesNoProfileCall() {
		// e.g. an unknown "type" that loaded as Microsoft but has no tokens
		MinecraftSession noToken = new MinecraftSession(null, 0L, null, "00000000000000000000000000000abc", "NoToken");
		assertNull(SkinFetcher.fetch(noToken));
		assertEquals(0, calls.get());
	}

	@Test
	void microsoftAccountStillUsesTheProfileApi() {
		MinecraftSession ms = new MinecraftSession("token", Long.MAX_VALUE, "refresh", "00000000000000000000000000000def", "AddyMS");
		assertNull(SkinFetcher.fetch(ms)); // stub fails, nothing cached
		assertEquals(1, calls.get());
	}
}
