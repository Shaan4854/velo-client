# STATUS (final wrap-up, 2026-11-28 ~14:40 IST, v1.6.2)

Cold start for a fresh agent. Details and design are in `context.md`.

## What this is
Fork `Shaan4854/velo-client` of upstream `velo345/velo-client` (Velo Client, Minecraft mod + launcher). Owner: Shaan. Three custom features must survive every sync: offline accounts (no Advent for offline), offline cosmetics (Velo cloak with local skins), and the auto-updater pointing at `Shaan4854/velo-client`. Hard rules: never delete, recreate or detach the repo, never force-push.

## State
- `main` = upstream 1.6.0 merged + his three features + the Cloudflare Workers backend (`workers-backend/`) + live default URL. Version 1.6.2 (launcher + mod).
- Latest release: https://github.com/Shaan4854/velo-client/releases/tag/v1.6.2 (tag at main 90ebabf, 10 installers: Windows exe x2 + zip, macOS arm64/x64 dmg + zip, Linux tar.gz/deb/zip). v1.6.1 (c432f4f) and v1.6.0 stay too. 1.6.2 = v1.6.1 + one change: the client sends /v1/session/end when the game exits.
- Backend: Worker `velo-fork`, https://velo-fork.shaaanansari786.workers.dev, free tier, one SQLite Durable Object, no WebSockets. Current version cbd6195d (session TTL 90 s). main has the same source.
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
- Emotes and social polling are not instant by design (poll based, about 2 to 8 s).
