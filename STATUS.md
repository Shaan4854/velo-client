# STATUS (updated at each milestone commit)

Branch: `feat/workers-backend` (main untouched; v1.6.0 released earlier).

| Milestone | State |
|---|---|
| M1 limits + design | done |
| M2 session/presence/cosmetics/capes core (`workers-backend/`) | done, 19/19 local smoke checks |
| M3 social + party | done, 28/28 local checks (stubs for store/plus/rewards/worlds/etc. decided in M4) |
| M4 client patches | done, compiled (launcher + tests, mod 1.21.11/1.21.1); not run in-game |
| M5 deploy | waiting for owner's Cloudflare login (coordinated by Leader) |

## M2 contents
Routes: health, stats/live, session challenge/verify/offline-claim/end/end-game, heartbeat, online (full + delta), cosmetics/of, cape upload/remove/get. Emotes are kept 30 s with a server timestamp.

## M3 contents
social.ts (state, poll, messages, request/respond/cancel/unfriend/block/unblock, status, presence, message, read, share-waypoint) and party.ts (state, create, invite, respond, leave, kick, transfer, sync, launch, voice, follow, chat, ping, position(s), storage-share(s), emote, waypoint). Social poll is short-poll with `pollAfterMs` (30 s, 8 s in a party).

## M4 / test aid
Client patches listed in context.md section 6. Screenshot workflow: `client-screenshot.yml` (dispatch only). First run result: see below once recorded.

## Open items
- Domain: default plan is a free `workers.dev` subdomain.
- Offline-claim is trust on first use.
- No in-game runtime test is possible in the build sandbox; client changes in M4 are compile-verified only.
- Agent expiry: the coding agent expires Nov 29 2026 01:59 IST; this file and `context.md` are the handover.

## M5 deploy (2026-11-28 11:41 IST)
- Worker `velo-fork` live at https://velo-fork.shaaanansari786.workers.dev (account subdomain already existed). Version f3269aed-ef14-4a5a-822b-a527c5a420ff.
- Live smoke (19) + social (28) checks all PASS against the live URL.
- Client + launcher default URL now points at it (compiles pending).
