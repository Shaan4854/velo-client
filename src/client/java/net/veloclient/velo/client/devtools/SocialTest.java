package net.veloclient.velo.client.devtools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.network.SocialClient;

import java.nio.file.Files;
import java.nio.file.Path;


/**
 * CI-only (throwaway ci/screenshot branch): scripted two-client social test. -Dvelo.socialTest=A|B and
 * -Dvelo.socialTest.dir=<shared folder>. A sends a friend request, B accepts, A messages B, A invites B to a party,
 * B joins, both send party chat, then both re-read the server state. Every step logs a SOCIALTEST line.
 */
public final class SocialTest {
	private interface Check {
		boolean test() throws Exception;
	}

	private static String role;
	private static Path dir;

	private SocialTest() {
	}

	public static void initIfRequested() {
		role = System.getProperty("velo.socialTest");
		if (role == null || role.isBlank()) {
			return;
		}
		dir = Path.of(System.getProperty("velo.socialTest.dir", "/tmp/st"));
		Thread t = new Thread(SocialTest::run, "velo-socialtest");
		t.setDaemon(true);
		t.start();
	}

	private static void log(String step, boolean ok, String detail) {
		VeloClient.LOGGER.info("SOCIALTEST {} {} {} {}", role, step, ok ? "OK" : "FAIL", detail);
	}

	private static boolean await(String step, int seconds, Check cond) {
		long end = System.currentTimeMillis() + seconds * 1000L;
		while (System.currentTimeMillis() < end) {
			try {
				if (cond.test()) {
					log(step, true, "");
					return true;
				}
				Thread.sleep(1000);
			} catch (Exception e) {
				// keep polling
			}
		}
		log(step, false, "timeout " + seconds + "s");
		return false;
	}

	private static String chatText(String needle) {
		JsonObject p = SocialClient.party();
		if (p == null || !p.has("chat")) {
			return null;
		}
		for (JsonElement e : p.getAsJsonArray("chat")) {
			if (e.toString().contains(needle)) {
				return e.toString();
			}
		}
		return null;
	}

	private static String peerFriendUuid(String peer) {
		var s = SocialClient.snapshot();
		if (s == null) {
			return null;
		}
		for (var f : s.friends) {
			if (peer.equalsIgnoreCase(f.username)) {
				return f.uuid;
			}
		}
		return null;
	}

	private static final java.util.Set<String> PARTY_EVENTS = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private static java.util.UUID dashed(String u) {
		String h = u.replace("-", "");
		return java.util.UUID.fromString(h.replaceFirst("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5"));
	}

	private static boolean friendOnline(String peer) {
		var s = SocialClient.snapshot();
		if (s == null) {
			return false;
		}
		for (var f : s.friends) {
			if (peer.equalsIgnoreCase(f.username)) {
				return f.online;
			}
		}
		return false;
	}

	private static void run() {
		try {
			SocialClient.addPartyListener((type, data) -> PARTY_EVENTS.add(type));
			String other = role.equals("A") ? "B" : "A";
			if (!await("ready", 120, () -> SocialClient.isReady() && SocialClient.snapshot().me != null)) {
				return;
			}
			String me = SocialClient.snapshot().me.username;
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("name-" + role), me);
			log("name", true, me);
			String[] peerHolder = new String[1];
			if (!await("peer-name", 150, () -> {
				Path f = dir.resolve("name-" + other);
				if (Files.exists(f)) {
					peerHolder[0] = Files.readString(f).trim();
				}
				return peerHolder[0] != null && !peerHolder[0].isBlank();
			})) {
				return;
			}
			String peer = peerHolder[0];
			if (role.equals("B2")) {
				log("reconnect-client-side", true, "restarted client, me=" + me);
				await("b2-friend-list-intact", 90, () -> peerFriendUuid(peer) != null);
				{
					long end = System.currentTimeMillis() + 60_000;
					while (System.currentTimeMillis() < end && !SocialClient.inParty()) {
						Thread.sleep(1000);
					}
					log("b2-party-after-graceful-quit-INFO", true, "inParty=" + SocialClient.inParty());
				}
				Files.writeString(dir.resolve("done-B2"), "1");
				log("finished", true, "");
				return;
			}
			if (role.equals("A")) {
				String[] err = new String[1];
				if (!await("send-friend-request", 90, () -> {
					err[0] = SocialClient.sendRequest(peer).get();
					return err[0] == null;
				})) {
					log("send-friend-request-error", false, String.valueOf(err[0]));
					return;
				}
				if (!await("friend-accepted-by-peer", 120, () -> peerFriendUuid(peer) != null)) {
					return;
				}
				String uuid = peerFriendUuid(peer);
				log("dm-send", SocialClient.sendMessage(uuid, "dm hello from A").get() == null, "");
				await("party-create", 30, () -> SocialClient.partyCreate().get() == null || SocialClient.inPartyOrSolo());
				await("party-invite", 30, () -> SocialClient.partyInvite(peer).get() == null);
				await("party-has-2-members", 120, SocialClient::inParty);
				await("party-chat-send", 30, () -> SocialClient.partyChat("party hello from A").get() == null);
				await("party-chat-from-B", 120, () -> chatText("party hello from B") != null);
				await("server-state-friend-persisted", 60, () -> {
					var st = SocialClient.apiGet("/v1/social/state").get();
					return st != null && st.toString().contains(peer);
				});
				// phase 2: ping, waypoint, cloak equip + visibility
				log("party-ping-send", SocialClient.partyPing(java.util.Map.of("label", "ci-ping", "x", 10, "y", 64, "z", 5)).get() == null, "");
				log("party-waypoint-send", SocialClient.partyWaypoint(java.util.Map.of("name", "ci-wp", "x", 20, "y", 64, "z", 7)).get() == null, "");
				String[] cape = new String[1];
				if (await("cloak-catalog-offered", 90, () -> !net.veloclient.velo.client.offline.OfflineLockerCloaks.missing().isEmpty())) {
					var item = net.veloclient.velo.client.offline.OfflineLockerCloaks.missing().get(0);
					await("cloak-import", 120, () -> {
						cape[0] = net.veloclient.velo.client.offline.OfflineCosmetics.importCape(item.id());
						return cape[0] != null;
					});
					if (cape[0] != null) {
						net.veloclient.velo.client.cosmetics.CapeManager.equip(cape[0]);
						net.veloclient.velo.client.network.VeloServerClient.syncSoon();
						boolean worn = net.veloclient.velo.client.cosmetics.CapeManager.equippedSourceItemId().filter(item.id()::equals).isPresent();
						log("cloak-equipped-locally", worn, item.name() + " (" + item.id() + ")");
						Files.writeString(dir.resolve("cape-item"), item.id());
						await("live-online-list-shows-my-cloak", 60, () -> {
							var on = SocialClient.apiGet("/v1/online").get();
							String t = on == null ? "" : on.toString();
							int i = t.indexOf(me);
							return i >= 0 && t.indexOf("\"capeId\":\"" + item.id() + "\"", i) > 0;
						});
					}
				}
				{
					var ids = net.veloclient.velo.client.emotes.EmoteLibrary.bundledIds();
					String emoteId = ids.isEmpty() ? null : ids.get(0);
					log("emote-id", emoteId != null, String.valueOf(emoteId));
					if (emoteId != null) {
						java.util.UUID self = dashed(SocialClient.myUuidPublic());
						boolean[] live = {false};
						long end = System.currentTimeMillis() + 90_000;
						while (System.currentTimeMillis() < end && !(live[0] && Files.exists(dir.resolve("emote-seen-B")))) {
							net.veloclient.velo.client.emotes.Emotes.playLocal(self, emoteId);
							try {
								var on = SocialClient.apiGet("/v1/online").get();
								String t = on == null ? "" : on.toString();
								int i = t.indexOf(me);
								if (!live[0] && i >= 0 && t.indexOf("\"emote\":\"" + emoteId, i) > 0) {
									live[0] = true;
									log("live-online-list-shows-my-emote", true, emoteId);
								}
							} catch (Exception ignored) {
								// keep going
							}
							Thread.sleep(3000);
						}
						if (!live[0]) {
							log("live-online-list-shows-my-emote", false, "never seen");
						}
						log("emote-seen-by-B", Files.exists(dir.resolve("emote-seen-B")), "");
					}
				}
				{
					// emote delay: A starts the emote at a wall-clock time written to a file, B logs when it sees it play
					var ids = net.veloclient.velo.client.emotes.EmoteLibrary.bundledIds();
					if (!ids.isEmpty()) {
						java.util.UUID self = dashed(SocialClient.myUuidPublic());
						for (int i = 1; i <= 5; i++) {
							final int n = i;
							await("trial-" + n + "-my-emote-ended", 60, () -> !net.veloclient.velo.client.emotes.Emotes.playing(self));
							Thread.sleep(12_000); // B sees "stopped" even at the slow polling pace
							Files.writeString(dir.resolve("delay-t0-" + n), String.valueOf(System.currentTimeMillis()));
							net.veloclient.velo.client.emotes.Emotes.playLocal(self, ids.get(n % ids.size()));
							await("trial-" + n + "-B-ack", 40, () -> Files.exists(dir.resolve("delay-ack-" + n)));
						}
					}
				}
				Files.writeString(dir.resolve("p2-A"), "1");
				// phase 3: B is killed and restarted by the workflow; watch it go offline and come back
				long t0 = System.currentTimeMillis();
				await("graceful-quit-peer-offline-within-60s", 60, () -> !friendOnline(peer));
				log("graceful-quit-offline-after-ms", true, String.valueOf(System.currentTimeMillis() - t0));
				await("peer-back-online", 240, () -> friendOnline(peer));
				log("party-after-peer-restart-INFO", true, "inParty=" + SocialClient.inParty());
				Files.writeString(dir.resolve("p3-A"), "1");
				await("wait-for-hardkill-marker", 400, () -> Files.exists(dir.resolve("hardkill-at")));
				long killedAt = Long.parseLong(Files.readString(dir.resolve("hardkill-at")).trim()) * 1000L;
				await("hardkill-peer-offline-within-130s", 130, () -> !friendOnline(peer));
				log("hardkill-offline-after-s", true, String.valueOf((System.currentTimeMillis() - killedAt) / 1000));
				Files.writeString(dir.resolve("done-A"), "1");
			} else {
				await("incoming-request", 150, () -> {
					var s = SocialClient.snapshot();
					return s != null && s.incoming.stream().anyMatch(r -> peer.equalsIgnoreCase(r.username));
				});
				String ruuid = SocialClient.snapshot().incoming.stream().filter(r -> peer.equalsIgnoreCase(r.username)).findFirst().map(r -> r.uuid).orElse(null);
				if (ruuid == null) {
					return;
				}
				log("accept-request", SocialClient.respond(ruuid, true).get() == null, "");
				await("now-friends", 60, () -> peerFriendUuid(peer) != null);
				String uuid = peerFriendUuid(peer);
				await("dm-received", 120, () -> uuid != null && SocialClient.conversation(uuid).stream().anyMatch(m -> m.text != null && m.text.contains("dm hello from A")));
				await("party-invite-received", 120, () -> !SocialClient.partyInvites().isEmpty());
				String pid = SocialClient.partyInvites().get(0).get("party").getAsString();
				log("party-accept", SocialClient.partyRespond(pid, true).get() == null, "");
				await("in-party", 60, SocialClient::inParty);
				await("party-chat-from-A", 120, () -> chatText("party hello from A") != null);
				await("party-chat-send", 30, () -> SocialClient.partyChat("party hello from B").get() == null);
				await("server-state-friend-persisted", 60, () -> {
					var st = SocialClient.apiGet("/v1/social/state").get();
					return st != null && st.toString().contains(peer);
				});
				await("party-ping-received", 90, () -> PARTY_EVENTS.contains("party_ping"));
				await("party-waypoint-received", 90, () -> PARTY_EVENTS.contains("party_waypoint"));
				String[] capeSeen = new String[1];
				await("peer-cloak-visible-in-my-registry", 150, () -> {
					Path cf = dir.resolve("cape-item");
					if (!Files.exists(cf) || uuid == null) {
						return false;
					}
					capeSeen[0] = net.veloclient.velo.client.network.VeloUserRegistry.capeIdFor(dashed(uuid), peer);
					return capeSeen[0] != null && capeSeen[0].equals(Files.readString(cf).trim());
				});
				log("peer-cloak-value", capeSeen[0] != null, String.valueOf(capeSeen[0]));
				if (await("emote-of-A-playing-in-my-client", 200, () -> uuid != null && net.veloclient.velo.client.emotes.Emotes.playing(dashed(uuid)))) {
					Files.writeString(dir.resolve("emote-seen-B"), "1");
				}
				for (int i = 1; i <= 5; i++) {
					final int n = i;
					// The previous emote must be over on this side first, or "playing" is already true and the delay reads ~0
					await("trial-" + n + "-B-idle-before", 40, () -> uuid != null && !net.veloclient.velo.client.emotes.Emotes.playing(dashed(uuid)));
					if (!await("trial-" + n + "-start", 90, () -> Files.exists(dir.resolve("delay-t0-" + n)))) {
						break;
					}
					long t0 = Long.parseLong(Files.readString(dir.resolve("delay-t0-" + n)).trim());
					boolean seen = false;
					long deadline = System.currentTimeMillis() + 30_000;
					while (System.currentTimeMillis() < deadline) {
						if (uuid != null && net.veloclient.velo.client.emotes.Emotes.playing(dashed(uuid))) {
							seen = true;
							break;
						}
						Thread.sleep(10);
					}
					long ms = System.currentTimeMillis() - t0;
					log("EMOTE_DELAY_MS", seen, "trial " + n + " " + ms + " realtime=" + net.veloclient.velo.client.network.VeloRealtime.connected()
							+ " nudges=" + net.veloclient.velo.client.network.VeloRealtime.nudges);
					Files.writeString(dir.resolve("delay-ack-" + n), "1");
				}
				Files.writeString(dir.resolve("p2-B"), "1");
				await("wait-for-quit-request", 600, () -> Files.exists(dir.resolve("quit-B")));
				log("graceful-quit-now", true, "scheduleStop");
				var mc = net.minecraft.client.MinecraftClient.getInstance();
				mc.execute(mc::scheduleStop);
				Thread.sleep(600_000);
			}
			log("finished", true, "");
		} catch (Exception e) {
			log("exception", false, String.valueOf(e));
		}
	}
}
