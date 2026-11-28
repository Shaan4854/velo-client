// Realtime (WebSocket nudge) test against a deployed/dev Worker (BASE env). Two offline players.
import { createHash, randomBytes } from "node:crypto";
import WebSocket from "ws";
const BASE = process.env.BASE ?? "http://localhost:8799";
const WS = BASE.replace(/^http/, "ws");
let fails = 0;
const ok = (c, m) => { console.log((c ? "PASS " : "FAIL ") + m); if (!c) fails++; };
const offUuid = (n) => { const m = createHash("md5").update("OfflinePlayer:" + n).digest(); m[6] = (m[6] & 0x0f) | 0x30; m[8] = (m[8] & 0x3f) | 0x80; return m.toString("hex"); };
const call = async (method, path, token, body) => {
  const r = await fetch(BASE + path, { method, headers: { "content-type": "application/json", ...(token ? { authorization: "Bearer " + token } : {}) }, body: body === undefined ? undefined : JSON.stringify(body) });
  let j = null; try { j = await r.json(); } catch {}
  return { s: r.status, j };
};
const login = async (name) => {
  const r = await call("POST", "/v1/session/offline-claim", null, { uuid: offUuid(name), username: name, secret: randomBytes(24).toString("hex"), kind: "game" });
  return { name, uuid: offUuid(name), token: r.j.sessionToken, advertised: r.j.realtime };
};
const connect = (token) => new Promise((resolve, reject) => {
  const ws = new WebSocket(WS + "/v1/ws", { headers: token ? { authorization: "Bearer " + token } : {} });
  const inbox = [];
  const waiters = [];
  ws.on("message", (d) => {
    const t = d.toString();
    const msg = t === "pong" ? { t: "pong" } : JSON.parse(t);
    const w = waiters.findIndex((x) => x.pred(msg));
    if (w >= 0) waiters.splice(w, 1)[0].resolve({ msg, at: Date.now() }); else inbox.push({ msg, at: Date.now() });
  });
  ws.on("open", () => resolve({
    ws, inbox,
    next: (pred, ms = 4000) => new Promise((res, rej) => {
      const i = inbox.findIndex((x) => pred(x.msg));
      if (i >= 0) return res(inbox.splice(i, 1)[0]);
      const entry = { pred, resolve: res };
      waiters.push(entry);
      setTimeout(() => { const k = waiters.indexOf(entry); if (k >= 0) { waiters.splice(k, 1); rej(new Error("timeout")); } }, ms);
    }),
  }));
  ws.on("unexpected-response", (_q, res) => reject(Object.assign(new Error("http " + res.statusCode), { status: res.statusCode })));
  ws.on("error", (e) => reject(e));
});
const id = Math.floor(Math.random() * 1e5);
const A = await login("RtAlice" + id), B = await login("RtBob" + id), C = await login("RtCara" + id);
ok(A.advertised === true, "session response advertises realtime");
let bad = null; try { await connect("nope"); } catch (e) { bad = e; }
ok(bad && bad.status === 401, "socket refused without a valid session (" + (bad && bad.status) + ")");
const wa = await connect(A.token), wb = await connect(B.token), wc = await connect(C.token);
await wc.next((m) => m.t === "hello");
ok((await wa.next((m) => m.t === "hello")).msg.t === "hello", "A gets hello");
await wb.next((m) => m.t === "hello");
await wa.next((m) => m.t === "online", 3000).catch(() => {}); await wb.next((m) => m.t === "online", 1500).catch(() => {});
wa.inbox.length = 0; wb.inbox.length = 0;

// keepalive is answered without waking our code
const p0 = Date.now(); wb.ws.send("ping");
const pong = await wb.next((m) => m.t === "pong", 3000).catch(() => null);
ok(!!pong, "ping answered with pong (" + (pong ? pong.at - p0 : "-") + " ms)");

// emote delay: A heartbeats an emote, B should be nudged then read the online list
const lat = [];
for (let i = 1; i <= 5; i++) {
  const t0 = Date.now();
  await call("POST", "/v1/heartbeat", null, { sessionToken: A.token, emote: "wave;" + i });
  const n = await wb.next((m) => m.t === "online", 4000).catch(() => null);
  if (!n) { ok(false, "emote " + i + " nudge arrived"); continue; }
  const ol = await call("GET", "/v1/online?since=0", B.token);
  const u = (ol.j.users ?? []).find((x) => x.uuid === A.uuid);
  const t1 = Date.now();
  lat.push(t1 - t0);
  ok(u && u.emote === "wave;" + i, "emote " + i + " visible to B " + (t1 - t0) + " ms after A sent it (nudge after " + (n.at - t0) + " ms)");
  await new Promise((r) => setTimeout(r, 300));
}
lat.sort((a, b) => a - b);
console.log("EMOTE_LATENCY_MS median=" + lat[Math.floor(lat.length / 2)] + " max=" + lat[lat.length - 1] + " n=" + lat.length);

// social: a message must nudge B, and only B
wa.inbox.length = 0;
await call("POST", "/v1/social/request", A.token, { target: B.name });
const reqN = await wb.next((m) => m.t === "social", 3000).catch(() => null);
ok(!!reqN, "friend request nudges the target");
await call("POST", "/v1/social/respond", B.token, { uuid: A.uuid, accept: true });
await wa.next((m) => m.t === "social", 3000).then(() => ok(true, "accept nudges the requester"), () => ok(false, "accept nudges the requester"));
wa.inbox.length = 0; wb.inbox.length = 0;
const t0 = Date.now();
await call("POST", "/v1/social/message", A.token, { to: B.uuid, text: "hi" });
const msgN = await wb.next((m) => m.t === "social", 3000).catch(() => null);
ok(!!msgN, "chat message nudges B " + (msgN ? msgN.at - t0 + " ms" : ""));
const sp = await call("GET", "/v1/social/poll?since=0", B.token);
ok(sp.s === 200, "social poll still works as fallback");
await new Promise((r) => setTimeout(r, 400));
ok(!wc.inbox.some((x) => x.msg.t === "social"), "an unrelated player is not nudged for a chat between two others");

// session end drops the player from the list and nudges others
await call("POST", "/v1/session/end", null, { sessionToken: A.token });
const gone = await wb.next((m) => m.t === "online", 3000).catch(() => null);
ok(!!gone, "going offline nudges B");
const ol2 = await call("GET", "/v1/online?since=0", B.token);
ok(!(ol2.j.users ?? []).some((x) => x.uuid === A.uuid), "A gone from the online list");

// second socket for the same player replaces the first
const wb2 = await connect(B.token);
await wb2.next((m) => m.t === "hello");
await new Promise((r) => setTimeout(r, 500));
ok(wb.ws.readyState !== 1, "old socket replaced by the new one");
wb2.ws.close(); wa.ws.close(); wc.ws.close();
console.log(fails ? fails + " FAILED" : "ALL PASSED");
process.exit(fails ? 1 : 0);
