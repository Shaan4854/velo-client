# STATUS (2026-11-28 ~21:15 IST, v1.7.0)

Cold start for a fresh agent. Details and design are in `context.md`.

## What this is
Fork `Shaan4854/velo-client` of upstream `velo345/velo-client` (Velo Client, Minecraft mod + launcher). Owner: Shaan. Three custom features must survive every sync: offline accounts (no Advent for offline), offline cosmetics (Velo cloak with local skins), and the auto-updater pointing at `Shaan4854/velo-client`. Hard rules: never delete, recreate or detach the repo, never force-push.

## State
- `main` = upstream 1.6.0 merged + his three features + the Cloudflare Workers backend (`workers-backend/`) + live default URL. Version 1.7.0 (launcher + mod).
- Latest release: https://github.com/Shaan4854/velo-client/releases/tag/v1.7.0 (v1.7.0 = v1.6.2 + WebSocket realtime, natives/lwjgl lock fix, offline-account self-heal; see section v1.7.0 below). Previous: https://github.com/Shaan4854/velo-client/releases/tag/v1.6.2 (tag at main 90ebabf, 10 installers: Windows exe x2 + zip, macOS arm64/x64 dmg + zip, Linux tar.gz/deb/zip). v1.6.1 (c432f4f) and v1.6.0 stay too. 1.6.2 = v1.6.1 + one change: the client sends /v1/session/end when the game exits.
- Backend: Worker `velo-fork`, https://velo-fork.shaaanansari786.workers.dev, free tier, one SQLite Durable Object with hibernation WebSocket nudges (/v1/ws). Current version 7cfe80a0-1c78-4364-a0cf-fea109c34d9e (WebSocket + catalog Authorization fix, session TTL 90 s). main has the same source.
- `ci/realtime` = throwaway test branch for v1.7.0 (two-client delay test, launcher tests, CI overrides). Keep until told to delete. `feat/restore-emotes` = ready-to-use restore of the 24 emotes upstream 1.6.0 removed (see below), NOT in any release. `fix/fps-defaults` = a one-line default change (Throttle Hidden Window off), dropped by the owner, NOT merged.
- `ci/screenshot` = throwaway test branch (screenshot tour, two-client SocialTest, CI-only overrides). Keep until the owner or Leader says delete. Never merge it into main.

## Redeploy the Worker
`cd workers-backend && CLOUDFLARE_API_TOKEN=<vault "Cloudflare API token - velo deploy" password> CLOUDFLARE_ACCOUNT_ID=<same item's username field WITHOUT the "account:" prefix> npx wrangler deploy`. Fetch values with `tools vault write-file` into files, never print them. Then run `BASE=<live url> node --experimental-strip-types test/smoke.mjs` and `test/social.mjs` (19/19 and 28/28 expected).

## Tests and proof (all against the LIVE Worker unless noted)
| What | Result | Run |
|---|---|---|
| Smoke + social scripted suites | 19/19, 28/28 | local + live |
| Real client claims and shows in live online list | PASS | https://github.com/Shaan4854/velo-client/actions/runs/46117552748 |
| Full in-world screenshot tour (34 screens) | done | .../runs/46116310310 |
| Two real clients: friend request, accept, DM, party invite/join, party chat both ways, state re-read | PASS | .../runs/46118805166 |
| Party ping + waypoint received by 2nd client | PASS | .../runs/46122914361 |
| Cloak equipped by Locker click; live online list shows capeId; 2nd client registry has it | PASS | .../runs/46123043092, 46123043373 |
| Emote via heartbeat: live list shows it, 2nd client plays it | PASS | .../runs/46123043373 |
| Client restart: friend list + party intact | PASS | .../runs/46124123933 |
| Hard-killed client shows offline after ~91 s (TTL 90 s) | PASS | .../runs/46124123933 |
| Main build check (4 OS) | green | .../runs/46118366423 (1.6.1), .../runs/46125372298 (1.6.2) |
| Graceful quit shows offline in ~6 s (1.6.2 fix) | PASS | .../runs/46125373898 |
| v1.6.1 release build (4 OS, Windows re-run once after a flaky legacy-jar step) | green | .../runs/46119994583 |
| v1.6.2 release build (4 OS, 10 assets) | green | .../runs/46127107721 |

## Known gaps
- FIXED in 1.6.2: graceful game exit now calls /v1/session/end (CLIENT_STOPPING -> VeloServerClient.stopOnGameExit, 2 s max wait). Proof: run 46125373898, a friend saw the other client offline ~6 s after a normal quit (before: still online after 60 s). A killed/crashed game still shows online until the 90 s session TTL (measured 87-91 s).
- Not verified: the installers on real desktops; two real players in the same world seeing each other's cloak/emote pixels (data paths verified, not rendering); long-run free-tier usage (100k requests/day) with many players.
- Locker offline-cosmetics grid was shot with a CI-only override (`velo.ciOfflineSession`, ci/screenshot only) because the game's offline-session check is false in a dev environment.
- Offline identity is trust on first use; Microsoft-verified names are reserved.
- The Worker proxies anonymous public GETs to upstream (https://api.velo-client.com) with a 10 min cache; owner decided to keep it on.
- OfflineCosmetics.java carries an upstream comment "used with permission from founder Velo2k". The owner's rights claim is unverified (see context.md).
- Emotes and social: v1.7.0 adds the WebSocket nudge (about 0.4 s), the polling path stays as fallback (about 7 s) and as a 90 s safety net while the socket is up. Killed games still show online up to 90 s.
- Worker `/v1/store/*` answers 404 (the proxy only does anonymous public reads; store state and wallet need a session). Offline cosmetics bypasses it, so nobody notices today; Microsoft-account fork users would see no store or wallet state. Parked on purpose.

## v1.7.0 (2026-11-28)
Scope: WebSocket realtime + natives/lwjgl lock fix + offline-account self-heal + Worker catalog Authorization fix. rc/1.7.0 is the release candidate branch.
- WebSocket: Worker `/v1/ws` (Bearer session, hibernation Durable Object) sends only nudges `{"t":"online"}` / `{"t":"social"}`; the client (VeloRealtime) then runs its normal HTTP reads. Session response carries `realtime:true`. `-Dvelo.noRealtime=true` turns it off. Polling stays as the fallback.
- Measured in the same CI harness against the live Worker, 5 trials each, emote from client A to client B (time from A starting the emote to B seeing it): WebSocket 427-443 ms (run 46156314458); polling 2.6-7.8 s, median 7.4 s (run 46159773221). Live Worker script test (realtime.mjs): median 188 ms. Earlier harness runs (46151062853, 46151064737, 46152141577, 46153811554, 46154887769, 46157202569) are not valid or failed (test bugs / name collision) and must not be quoted.
- Natives fix: NativesExtractor.prepare retries the delete and otherwise uses a fresh natives-<stamp> folder (launcher locked lwjgl.dll case). Offline-account self-heal: AuthSession retries reads, falls back to account.json.bak, sets a corrupt file aside, never writes after a failed read, and heals a type-less entry to OFFLINE (the "token khali" case). Unit tests in the launcher test suite (run 46153813511, green).
- Worker fix: public reads of /v1/cosmetics/catalog|item|file are now served even when the client sends its session token (before: 404 "Not available on this server", so the catalog/emote data did not load after login). Verified live: catalog with a Bearer header = 200, 143 items; staff paths still 404.
- Build check: Package Launcher on rc/1.7.0 0b6e9e8, 4/4 green (run 46155433084). Live Worker tests after deploy: smoke, realtime, social all pass.
- Not verified: installers on real desktops; real players; the cloak/emote visuals.
- Emote restore (NOT shipped, owner said later): 1.5.1 had 31 emotes, upstream 1.6.0 cut to 7. Branch `feat/restore-emotes` (commit d99171c) restores the 24 removed emotes byte-identical from 1.5.1 (animations, sounds, 14 music tracks by Kevin MacLeod CC BY 4.0 with licenses/emote-music.txt, 10 props, merged sounds.json), about +9 MB. Resource check passed. Merge it to ship.

## Ready-to-send upstream report for Velo2k (the owner decided these are upstream's to fix; nothing here is changed in the fork)
Findings are from code reading only, not profiling or runtime tests. Versions: 1.5.1 = cceb454, 1.6.0 = f4b81db.
1. Low FPS after 1.5.1 -> 1.6.0 (user "ZyrexPlayz" and the owner: 1.5.1 was clearly smoother). 1.5.1 -> 1.6.0 is 630 files, +26k lines. Suspects, by likelihood: (a) `util/WindowOcclusion` + `util/FrameCaps` + `FrameCapMixin`: "Throttle Hidden Window" is on by default and drops the game to 15 FPS (`HIDDEN_FPS`) when the window is judged covered; it never throttles while the game window has focus, so it only explains unfocused or misdetected windows (overlays, second monitor, borderless windows). Test: `-Dvelo.noHiddenThrottle=true` or the Performance Boost toggle. (b) new GUI/HUD pipeline: `hud/HudCanvas`, `hud/HudFrameCache`, TurboHud* mixins, `gui/glass/GuiFrameCache`, `SharpFrame`, `GlassPrefs`, PauseBlur* mixins; extra GPU passes whose cost depends on GPU/driver and Minecraft version. HUD Caching, Fast Draw Path and HUD Canvas are already on by default. (c) `util/ParkedGame` frame cap. Not causes: PerfTelemetry, updater, Low Latency Mode and Tier Tagger (off by default). Ask users for GPU, Minecraft version, FPS per version and focus state.
2. Cloak swings to the front of the player while an emote plays (all cloak types, custom: and catalog alike). `cosmetics/render/CapeFeatureRenderer.renderCloak` and `cosmetics/physics/ClothCape` have no emote awareness: `EmotePoseMixin` overwrites the body part origin and pitch/yaw/roll, renderCloak feeds body.pitch and body.originX/Y/Z into the cloth simulation as normal motion, and ClothCape.frame passes only TURN_INERTIA (0.25) of body turns to the cloth, so for emotes with a big body pitch or shift (bow up to 93 degrees and 14 px, backflip -359 degrees and 20 px, cheer, heart) the cloth lags and is thrown past the body to the front; body yaw/roll from the emote is drawn but not simulated. Suggested fix, about 15 lines: while the wearer has an emote playing (`Emotes.sample(uuid, ...)` weight above 0), do not step the cloth; reset it every frame so it hangs rigid in body space, then resume when the emote ends. Unverified at runtime; first check which emote triggers it.
3. 1.6.0 removed 24 of the 31 bundled emotes (and their sounds, music, props) that 1.5.1 had; users noticed ("only 7 emotes").
