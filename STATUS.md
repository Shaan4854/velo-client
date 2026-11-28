# STATUS (updated at each milestone commit)

Branch: `feat/workers-backend` (main untouched; v1.6.0 released earlier).

| Milestone | State |
|---|---|
| M1 limits + design | done |
| M2 session/presence/cosmetics/capes core (`workers-backend/`) | done, 19/19 local smoke checks |
| M3 social + party + stubs | not started |
| M4 client patches | not started |
| M5 deploy | waiting for owner's Cloudflare login (coordinated by Leader) |

## M2 contents
Routes: health, stats/live, session challenge/verify/offline-claim/end/end-game, heartbeat, online (full + delta), cosmetics/of, cape upload/remove/get. Emotes are kept 30 s with a server timestamp.

## Open items
- Domain: default plan is a free `workers.dev` subdomain.
- Offline-claim is trust on first use.
- No in-game runtime test is possible in the build sandbox; client changes in M4 are compile-verified only.
- Agent expiry: the coding agent expires Nov 29 2026 01:59 IST; this file and `context.md` are the handover.
