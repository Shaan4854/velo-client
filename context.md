# Velo fork: context for whoever picks this up

Owner: Shaan (GitHub: Shaan4854). Fork: https://github.com/Shaan4854/velo-client, forked from velo345/velo-client.
Live progress is in `STATUS.md`. This file is the story and the reasoning. Both are updated at each milestone commit.

## 1. Hard rules from the owner

- Never delete, recreate or detach the fork. Never force-push.
- Do not touch `main` or publish releases without the owner's say-so (relayed by the Leader agent). Work goes on feature branches (`feat/workers-backend`, later `feat/offline-session`).
- Keep the three custom features intact: offline accounts, offline cosmetics, auto-updater pointed at this fork.
- No secrets in the repo or in logs. Tokens come from the vault and are never printed.

## 2. What the fork is

Velo Client is a Minecraft (Fabric) mod plus a desktop launcher, with an online service (`server/`) that provides sessions, who-is-online, cosmetics, capes, friends and parties. The fork adds features that upstream does not have:

1. **Offline accounts.** Players without a Microsoft account can use the launcher and game. No "Advent" button or popup is shown for offline accounts (gated with `!isOffline()` in `LauncherApp.java`, five places).
2. **Offline cosmetics.** `OfflineCosmetics` unlocks the store catalog locally for offline accounts (`StoreClient.owns()` returns true while `OfflineCosmetics.isActive()`), including the Velo cloak with local skins.
3. **Auto-updater pointing at the fork.** `AppUpdate.java` / `UpdateService.java` use `REPO=Shaan4854/velo-client` and a trust check (`velo-source.properties`, `Staged.UNTRUSTED`) so installs only follow this fork's releases.

## 3. The 1.6.0 merge

Upstream released 1.6.0. The fork was synced by merging upstream `main` into the fork's `main` (merge commit `f0d68d6`, fast-forward push, no force).

- Conflicts were only in the auto-updater files. Integrated: kept the fork's `REPO`, source file and trust check, and `isInstaller` (now uses `windowsInstallerFits`); took upstream's direct-unpack flow, retry helpers and logging.
- Regression found and fixed: upstream added two new Advent button spots in `LauncherApp.java` (topRow, homeTopRow). Both are gated with `!isOffline()`.
- Offline cosmetics: 1.6.0 added no new cosmetics (194 items before and after; price cuts only; 24 unreleased emotes removed). The unlock is generic so nothing needed changing.
- Upstream bug fixes: none were reported or cherry-picked beyond the merge itself. Nothing was dropped on purpose.
- Verification: launcher compile + tests on JDK 25; mod compiled locally for 1.21.11 and 1.21.1; GitHub Actions run 46101880822 built all 6 MC versions (1.21.1, 1.21.10, 1.21.11, 26.1, 26.2, 26.3) on 4 OS jobs.
- Unverified: no in-game runtime test was possible. UI placement of `OfflineCredits` and the offline cloak tiles after the upstream `VeloWindow`/`MenuKit` rework was not checked visually.

## 4. The release

`v1.6.0` was created on the fork at the owner's approval ("Release karde"), target `f0d68d6`. Tag run 46103506835 attached 10 assets (Windows installer and launcher exe, .deb, Linux tar.gz, macOS arm64/x64 dmg, 4 app zips). Old installs built after commit `99a3add` follow only this fork's releases.

## 5. The Workers backend

### Goal
Fork users, offline and Microsoft, should see each other's cosmetics, friends and parties, without depending on upstream's server (which would not serve offline accounts) and without a paid host.

### Decisions and reasoning

| Decision | Reasoning |
|---|---|
| Cloudflare Workers + Durable Objects, not a VM or Tunnel | Owner wants no VM to babysit and no home machine online. Workers free tier has no idle cost and a free `workers.dev` domain. A Tunnel needs an always-on machine. |
| No WebSockets | Free-tier Durable Object duration (13,000 GB-s/day) and request counts do not tolerate held connections for a group. Plain HTTP polling is cheap and the Java client already polls. |
| Throttled intervals: heartbeat 60 s, session TTL 90 s, online poll 20 s (adaptive), launcher poll 30 s | Free limit is 100k requests/day on Workers and on DO. Original intervals (heartbeat 20 s, poll 5 s) would use it up with a few dozen users. The server advertises the intervals; clients honor them. |
| Delta feed for `/v1/online` (`?since=version`) | Most polls return an empty delta: tiny responses and fewer rows read. |
| Durable Object with SQLite storage, not D1 | Free plan only offers SQLite-backed DOs. A single registry DO keeps sessions in memory with SQLite write-through, so most reads cost no rows. Row quotas (5M read, 100k written/day) are not the bottleneck. D1 would add a network hop per query for no gain at this scale. |
| One global `RegistryDO` for sessions, presence, capes, social and party | Presence must be consistent for everyone, and friends/party need live presence checks, which are plain function calls inside one object. At roughly 50 users with throttling one object is enough (a DO handles about 1,000 requests/s; we expect well under 5/s). Social and party are separate TypeScript modules (`social.ts`, `party.ts`) so they can move to their own DOs later if needed. |
| No long-poll for social events | Upstream holds `/v1/social/poll` open for 25 s. Held requests would bill duration on the free plan. The Worker answers at once with `pollAfterMs`: 30 s normally, 8 s while the player is in a party (chat and pings need to feel live). The client must honor `pollAfterMs` (M4). |
| Social data stored as key/value rows; parties in memory only | Friends/requests/blocks/read markers are one `core` row, each conversation is its own row, written only when changed. Parties were never persisted upstream; they end when everybody leaves or nobody is online. |
| Offline identity is trust on first use (TOFU) | Offline names have no authority to prove them. First claim of a name stores a hash of a client-generated secret; later claims need the same secret. The server also checks that the UUID equals the offline UUID of the name (MD5 of `OfflinePlayer:<name>`). Not strong security; stops casual impersonation. |
| Microsoft-verified names are reserved | A Microsoft verification (Mojang `hasJoined`) records the name as taken and deletes any offline claim on it, so an offline player cannot hold a name that a real account owns. |
| Cape limits: PNG only, at most 1.9 MB, width a multiple of 64 and at most 2048, height at most 1024, 2:1 or 1:1, one per player | Keeps storage and egress small on the free tier and matches what the renderer accepts. Capes are content-addressed and served with `immutable` cache headers so each download is cached. |
| Full cosmetics sharing, no ownership checks | The owner's call. He asserts he has prior permission from the upstream author (Velo2k). That claim is the owner's; it is not verified by us and the only written mention is a code comment in `OfflineCosmetics.java`. Logged here so it is visible. |
| Proxy anonymous public GETs (store catalog, art, news) to upstream, cached 10 min | Decided by the owner via the Leader: the client loads the store catalog from its configured server and offline cosmetics already did this against upstream, so fork clients send the same read-only requests today. Turning it off would break the Locker visuals (server-only art, catalog). Only unauthenticated GETs are forwarded, never admin/staff/tester paths, never POSTs. Set `UPSTREAM_BASE` empty to disable. |
| Stubbed endpoints for features outside scope | Client-called routes that the fork does not implement (bans, reports, store, panel) return safe empty responses so the client does not error. (M3.) |
| Emote timing: server keeps an emote 30 s with a server timestamp (`emoteAtMillis`); client fast-polls only when others are around | Emotes ride the heartbeat as `id;seq`. Upstream's 12 s TTL is shorter than a throttled poll, so one-shot emotes would be missed. Not instant: expect roughly 2 to 8 s delay for others. True instant needs WebSockets, rejected above. |
| Same player, same session kind: new sign-in replaces the old session | Prevents ghost duplicates in the online list. |

### Wire compatibility
The Worker keeps upstream's JSON shapes (see `server/src/main/java/net/veloclient/server/`): errors `{"error": msg}`; verify returns `sessionToken`, `heartbeatIntervalSeconds`, `sessionTtlSeconds`; online users have `uuid, username, capeId, wingsId, auraId, plus, nameStyle, emote, wear, pet, og, petAct`. New fields are additive: `emoteAtMillis` and the `/v1/session/offline-claim` route.

### Diagrams

#### Architecture
```mermaid
flowchart LR
  subgraph Player machines
    M[Velo mod]
    L[Velo launcher]
  end
  M -- HTTPS --> W[Cloudflare Worker router]
  L -- HTTPS --> W
  W --> R[(RegistryDO: sessions, presence, capes, offline claims, social, parties)]
  R --- S[Social module: friends, DMs, requests, blocks]
  R --- P[Party module: parties, chat, pings, positions]
  R -. Microsoft login only .-> J[Mojang hasJoined]
```

#### Login, poll and heartbeat
```mermaid
sequenceDiagram
  participant C as Client (mod)
  participant W as Worker / RegistryDO
  participant J as Mojang session server
  alt Microsoft account
    C->>W: POST /v1/session/challenge {uuid, username, kind}
    W-->>C: {serverId}
    C->>J: joinServer(serverId)
    C->>W: POST /v1/session/verify
    W->>J: hasJoined(username, serverId)
    J-->>W: profile ok
    W-->>C: {sessionToken, heartbeatIntervalSeconds=60, sessionTtlSeconds=90}
  else Offline account (TOFU)
    C->>W: POST /v1/session/offline-claim {uuid, username, secret, kind}
    W->>W: uuid == offlineUuid(name)? secret matches or first claim?
    W-->>C: {sessionToken, ...} or 403 already claimed
  end
  loop every 60 s
    C->>W: POST /v1/heartbeat {sessionToken, cape, wings, aura, emote, wear, pet}
    W-->>C: {ok, onlineCount, capeId}
  end
  loop every 20 s (5-8 s when others are near)
    C->>W: GET /v1/online?since=version
    W-->>C: {users (changed), removed, version, delta}
    C->>W: GET /v1/cape/hash (only for new capes, cached)
  end
  C->>W: POST /v1/session/end
```

#### Party flow (M3, planned)
```mermaid
flowchart TD
  A[Leader creates party in launcher] --> B[PartyDO creates party id and invite code]
  B --> C[Invite friend: social request or code]
  C --> D{Friend accepts?}
  D -- no --> E[Invite expires]
  D -- yes --> F[Friend joins party via /v1/party/join]
  F --> G[Members short-poll party state every 30 s]
  G --> H[Leader picks server / starts game]
  H --> I[Members get the join target on next poll]
  I --> J{Leader leaves?}
  J -- yes --> K[Next member becomes leader or party disbands]
  J -- no --> G
```

## 6. Milestones

- M1 limits and design: done.
- M2 session and presence/cosmetics core in TypeScript, tested with `wrangler dev`: done on this branch (19 smoke checks pass, `workers-backend/test/smoke.mjs`).
- M3 social (14 routes) and party (20 routes) ported (`social.ts`, `party.ts`, tests in `test/social.mjs`, 28 checks pass). Stubs for other client-called routes (store, plus, rewards, worlds, notes, etc.): decided in M4 after checking how the client reacts to 404.
- M4 client patches: done and compiled (launcher compile + tests, mod 1.21.11 and 1.21.1; the other versions go through Actions). What changed:
  - Default server URL is `VeloNetworkConfig.DEFAULT_SERVER_URL` / `LauncherSocial.DEFAULT_SERVER_URL` (placeholder `https://velo-fork.shaaanansari786.workers.dev`, set at M5). `network.json` entries naming the upstream server follow the move. `-Dvelo.serverUrl=` overrides it in the mod too (used for tests against `wrangler dev`).
  - Offline sign-in: `VeloServerClient.tryOfflineClaim` and `LauncherSocial.authenticateOffline` call `/v1/session/offline-claim` with the uuid, name and a per-install secret (`OfflineIdentity`, file `offline-identity.key`). The launcher passes its secret to offline games as `-Dvelo.offlineSecret` so launcher and game claim the same name with the same secret.
  - `OfflineCosmetics` gate: an offline-claim session no longer switches the local unlock off (`VeloServerClient.sessionTokenForOfflineGate()`), so the Locker stays unlocked while the player is online-visible. Microsoft sessions still switch it off.
  - Intervals: mod heartbeat follows the server's advertised period (60 s; clamped 20 to 300 s); online poll 8 s while other Velo players are online, 20 s alone; social polls (mod and launcher) follow `pollAfterMs` instead of long-polling; emotes older than 8 s on the server clock are skipped.
  - The Worker proxies anonymous public GETs it does not own (store catalog, art, news) to `UPSTREAM_BASE` with a 10 minute cache, because the client loads the store catalog from its configured server. Anything else it does not own returns 404 `{"error":"Not available on this server"}`. This keeps the same read-only requests to upstream that every fork client already made; set `UPSTREAM_BASE` empty to turn it off. Decision for the owner.
- Test aid: `.github/workflows/client-screenshot.yml` lives only on the throwaway `ci/screenshot` branch (deleted after testing) boots the mod under Xvfb with Mesa software GL against a local `wrangler dev`, saves screenshots, and fails if the client did not appear in `/v1/online`.
- M5 deploy with `wrangler deploy` after the owner's Cloudflare login, then a test release build. The owner approves any release.

## 7. How to run the backend locally
```
cd workers-backend
npm install
npx wrangler dev --port 8799 --local
BASE=http://localhost:8799 node test/smoke.mjs
BASE=http://localhost:8799 node test/social.mjs
```
Sandbox notes: 1 CPU, 2 GB RAM. Mod/launcher builds need JDK 25 with `--no-daemon --no-configuration-cache --configure-on-demand --no-parallel -Dorg.gradle.jvmargs=-Xmx1400m`.

## M5 deploy
- Deployed with wrangler using a Workers-scoped API token (vault item `Cloudflare API token - velo deploy`, account-only, expires 2027-02-26). Account ID is in the vault entry username. Redeploy: `cd workers-backend && CLOUDFLARE_API_TOKEN=<from vault> CLOUDFLARE_ACCOUNT_ID=<id> npx wrangler deploy`.
- Live URL https://velo-fork.shaaanansari786.workers.dev. Live test players created by the checks (names starting with test) remain in the registry.

## Release 1.6.1 (2026-11-28)
Main = upstream 1.6.0 merge + Workers backend (workers-backend/) + live default URL. Version bumped in launcher/build.gradle (launcherAppVersion) and stonecutter.properties.toml (mod.version). Test/CI-only code (screenshot tour, two-client SocialTest) lives only on ci/screenshot.
