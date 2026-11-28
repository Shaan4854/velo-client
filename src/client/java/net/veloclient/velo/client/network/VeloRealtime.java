package net.veloclient.velo.client.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.velo.client.VeloClient;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fork backend only: one WebSocket that tells us "something changed" (online list, friend/party/chat events) so the
 * normal HTTP reads run at once instead of on a timer. It carries no data of its own, so if it drops everything keeps
 * working through the slower polling (see {@link #connected()}); we reconnect with a growing delay.
 */
public final class VeloRealtime {

	private static final long PING_EVERY_SECONDS = 25;
	private static final long MAX_BACKOFF_SECONDS = 60;

	private static final Object LOCK = new Object();
	private static ScheduledExecutorService executor;
	private static WebSocket socket;
	private static String base;
	private static String token;
	private static int generation;
	private static long backoffSeconds = 2;
	private static volatile boolean connected;
	/** Latency probe for tests: millis of the last nudge received. */
	public static volatile long lastNudgeAt;
	/** Counts nudges received (tests). */
	public static volatile int nudges;

	private VeloRealtime() {
	}

	/** True while the socket is open, so the pollers may relax to a safety-net pace. */
	public static boolean connected() {
		return connected;
	}

	/** Starts (or restarts for a new token / server) the socket. Cheap to call on every heartbeat tick. */
	public static void ensure(String serverBase, String sessionToken) {
		if (serverBase == null || sessionToken == null || Boolean.getBoolean("velo.noRealtime")) {
			return;
		}
		synchronized (LOCK) {
			if (serverBase.equals(base) && sessionToken.equals(token) && (socket != null || executor != null && pendingConnect)) {
				return;
			}
			closeLocked();
			base = serverBase;
			token = sessionToken;
			backoffSeconds = 2;
			if (executor == null) {
				executor = Executors.newSingleThreadScheduledExecutor(r -> {
					Thread thread = new Thread(r, "velo-realtime");
					thread.setDaemon(true);
					return thread;
				});
				executor.scheduleWithFixedDelay(VeloRealtime::ping, PING_EVERY_SECONDS, PING_EVERY_SECONDS, TimeUnit.SECONDS);
			}
			int gen = ++generation;
			connectLocked(gen);
		}
	}

	public static void stop() {
		synchronized (LOCK) {
			closeLocked();
			base = null;
			token = null;
			++generation;
		}
	}

	private static boolean pendingConnect;

	private static void closeLocked() {
		connected = false;
		pendingConnect = false;
		WebSocket old = socket;
		socket = null;
		if (old != null) {
			try {
				old.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
			} catch (Exception ignored) {
				// closing anyway
			}
		}
	}

	private static void connectLocked(int gen) {
		String url = base.replaceFirst("^http", "ws").replaceAll("/+$", "") + "/v1/ws";
		pendingConnect = true;
		VeloServerClient.HTTP.newWebSocketBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.header("Authorization", "Bearer " + token)
			.buildAsync(URI.create(url), new Listener(gen))
			.whenComplete((ws, error) -> {
				synchronized (LOCK) {
					if (gen != generation) {
						if (ws != null) {
							ws.abort();
						}
						return;
					}
					if (error != null) {
						pendingConnect = false;
						VeloClient.LOGGER.debug("Velo realtime unavailable: {}", String.valueOf(error.getMessage()));
						scheduleReconnectLocked(gen);
					}
				}
			});
	}

	private static void scheduleReconnectLocked(int gen) {
		connected = false;
		socket = null;
		pendingConnect = false;
		long delay = backoffSeconds;
		backoffSeconds = Math.min(MAX_BACKOFF_SECONDS, backoffSeconds * 2);
		if (executor == null) {
			return;
		}
		executor.schedule(() -> {
			synchronized (LOCK) {
				if (gen == generation && base != null && token != null && socket == null) {
					connectLocked(gen);
				}
			}
		}, delay, TimeUnit.SECONDS);
	}

	private static void ping() {
		WebSocket ws;
		synchronized (LOCK) {
			ws = socket;
		}
		if (ws != null) {
			ws.sendText("ping", true);
		}
	}

	private static final class Listener implements WebSocket.Listener {
		private final int gen;
		private final StringBuilder text = new StringBuilder();

		Listener(int gen) {
			this.gen = gen;
		}

		@Override
		public void onOpen(WebSocket webSocket) {
			synchronized (LOCK) {
				if (gen != generation) {
					webSocket.abort();
					return;
				}
				socket = webSocket;
				pendingConnect = false;
				connected = true;
				backoffSeconds = 2;
			}
			VeloClient.LOGGER.info("Velo realtime connected");
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			text.append(data);
			if (last) {
				String message = text.toString();
				text.setLength(0);
				handle(message);
			}
			webSocket.request(1);
			return null;
		}

		private void handle(String message) {
			if ("pong".equals(message)) {
				return;
			}
			try {
				JsonObject json = JsonParser.parseString(message).getAsJsonObject();
				String type = json.has("t") ? json.get("t").getAsString() : "";
				if (type.equals("online")) {
					lastNudgeAt = System.currentTimeMillis();
					nudges++;
					VeloServerClient.pollNow();
				} else if (type.equals("social")) {
					lastNudgeAt = System.currentTimeMillis();
					nudges++;
					SocialClient.nudge();
				}
			} catch (Exception ignored) {
				// a malformed nudge is not worth anything
			}
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			dropped(webSocket);
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			dropped(webSocket);
		}

		private void dropped(WebSocket webSocket) {
			synchronized (LOCK) {
				if (gen != generation || socket != webSocket) {
					return;
				}
				VeloClient.LOGGER.info("Velo realtime dropped, falling back to polling");
				scheduleReconnectLocked(gen);
			}
			// the polling fallback should catch up now rather than at its slow pace
			VeloServerClient.pollNow();
			SocialClient.nudge();
		}
	}
}
