// Smoke test against `wrangler dev` (BASE env, default http://localhost:8799). No Mojang needed: offline claims only.
import { createHash, randomBytes } from "node:crypto";
import { deflateSync } from "node:zlib";
const BASE = process.env.BASE ?? "http://localhost:8799";
let fails = 0;
const ok = (c, m) => { console.log((c ? "PASS " : "FAIL ") + m); if (!c) fails++; };
const post = async (p, b, h = {}) => { const r = await fetch(BASE + p, { method: "POST", headers: { "content-type": "application/json", ...h }, body: typeof b === "string" || b instanceof Uint8Array ? b : JSON.stringify(b) }); let j = null; try { j = await r.json(); } catch {} return { s: r.status, j }; };
const get = async (p) => { const r = await fetch(BASE + p); return r; };
const offUuid = (n) => { const m = createHash("md5").update("OfflinePlayer:" + n).digest(); m[6] = (m[6] & 0x0f) | 0x30; m[8] = (m[8] & 0x3f) | 0x80; return m.toString("hex"); };
const crc = (buf) => { let c, t = []; for (let n = 0; n < 256; n++) { c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } let x = 0xffffffff; for (const b of buf) x = t[(x ^ b) & 255] ^ (x >>> 8); return (x ^ 0xffffffff) >>> 0; };
const chunk = (type, data) => { const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([len, td, c]); };
const png = (w, h) => { const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6; const raw = Buffer.alloc((w * 4 + 1) * h); return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk("IHDR", ihdr), chunk("IDAT", deflateSync(raw)), chunk("IEND", Buffer.alloc(0))]); };

const name = "Smoke" + Math.floor(Math.random() * 1e6).toString().slice(0, 5);
const uuid = offUuid(name);
const secret = randomBytes(24).toString("hex");
let r = await post("/v1/session/offline-claim", { uuid, username: name, secret, kind: "game" });
ok(r.s === 200 && r.j.sessionToken, "offline claim opens a session");
let token = r.j.sessionToken;
r = await post("/v1/session/offline-claim", { uuid, username: name, secret: randomBytes(24).toString("hex"), kind: "game" });
ok(r.s === 403, "second device with another secret is refused (" + r.s + ")");
r = await post("/v1/session/offline-claim", { uuid, username: name, secret, kind: "game" });
ok(r.s === 200, "same secret can sign in again");
const old = token; token = r.j.sessionToken;
r = await post("/v1/heartbeat", { sessionToken: old });
ok(r.s === 401, "older session of the same player is replaced");
r = await post("/v1/session/offline-claim", { uuid: offUuid("Other"), username: name, secret, kind: "game" });
ok(r.s === 400, "uuid must match the offline uuid of the name");

r = await post("/v1/heartbeat", { sessionToken: token, capeId: null, wingsId: "dragon", auraId: "orbit", wear: "hat_x:ff0000", emote: "wave", nameStyle: "rainbow" });
ok(r.s === 200 && r.j.ok, "heartbeat accepted");
let on = await (await get("/v1/online")).json();
ok(on.users.some((u) => u.uuid === uuid && u.wingsId === "dragon" && u.auraId === "orbit"), "online list shows the cosmetics");
const v = on.version;
on = await (await get("/v1/online?since=" + v)).json();
ok(on.delta === true && on.users.length === 0, "delta feed is empty when nothing changed");
r = await post("/v1/heartbeat", { sessionToken: token, wingsId: "angel", auraId: "orbit" });
on = await (await get("/v1/online?since=" + v)).json();
ok(on.delta && on.users.some((u) => u.wingsId === "angel"), "delta feed carries the change");

r = await post("/v1/heartbeat", { sessionToken: token, wingsId: "angel", auraId: "orbit", emote: "wave;7" });
on = await (await get("/v1/online")).json();
ok(on.users.some((u) => u.uuid === uuid && u.emote === "wave;7" && u.emoteAtMillis > 0), "emote is listed with its server timestamp");
r = await post("/v1/heartbeat", { sessionToken: token, wingsId: "angel", auraId: "orbit", emote: "wave;7" });
const on2 = await (await get("/v1/online")).json();
ok(on2.users.find((u) => u.uuid === uuid).emoteAtMillis === on.users.find((u) => u.uuid === uuid).emoteAtMillis, "repeated heartbeat does not restart the emote");
const cape = png(64, 32);
let up = await fetch(BASE + "/v1/cape/upload", { method: "POST", headers: { authorization: "Bearer " + token }, body: cape });
const upj = await up.json();
ok(up.status === 200 && upj.capeId?.startsWith("custom:"), "cape upload accepted");
const hash = upj.capeId.slice(7);
const dl = await get("/v1/cape/" + hash);
const dlb = Buffer.from(await dl.arrayBuffer());
ok(dl.status === 200 && dlb.equals(cape) && /immutable/.test(dl.headers.get("cache-control")), "cape downloads identical and cacheable");
r = await post("/v1/heartbeat", { sessionToken: token, capeId: upj.capeId });
ok(r.j.capeId === upj.capeId, "own custom cape accepted in heartbeat");
const bad = await fetch(BASE + "/v1/cape/upload", { method: "POST", headers: { authorization: "Bearer " + token }, body: png(100, 50) });
ok(bad.status === 400, "bad cape size rejected");
r = await post("/v1/heartbeat", { sessionToken: token, capeId: "custom:" + "0".repeat(64) });
ok(r.j.capeId === null, "someone else's/unknown custom cape id is dropped");

await post("/v1/session/end", { sessionToken: token });
on = await (await get("/v1/online")).json();
ok(!on.users.some((u) => u.uuid === uuid), "ended session leaves the online list");
r = await post("/v1/cosmetics/of", { uuids: [uuid] });
ok(r.j.users.some((u) => u.uuid === uuid), "last look is still served for offline players");
r = await post("/v1/heartbeat", { sessionToken: token });
ok(r.s === 401, "heartbeat after end asks to re-authenticate");
// Public catalog reads must also work when the client sends its session token (it always does once logged in).
{
  const anon = await fetch(BASE + "/v1/cosmetics/catalog");
  if (anon.status === 200) {
    const withAuth = await fetch(BASE + "/v1/cosmetics/catalog", { headers: { authorization: "Bearer x" } });
    ok(withAuth.status === 200, "catalog with a Bearer header is served");
    const cj = withAuth.status === 200 ? await withAuth.json() : { items: [] };
    ok(cj.items.length >= 100 && cj.items.filter((i) => i.type === "emote").length >= 7, "catalog has items and the 7 emotes (" + cj.items.length + ")");
    const sha = cj.items.find((i) => i.id === "emote_wave")?.files?.anim;
    const fr = await fetch(BASE + "/v1/cosmetics/file/" + sha, { headers: { authorization: "Bearer x" } });
    ok(fr.status === 200 && createHash("sha256").update(Buffer.from(await fr.arrayBuffer())).digest("hex") === sha, "emote file with a Bearer header downloads and matches its hash");
    const st = await fetch(BASE + "/v1/staff/anything", { headers: { authorization: "Bearer x" } });
    ok(st.status === 404, "staff paths stay closed");
  } else console.log("SKIP catalog checks (no upstream configured here)");
}
console.log(fails ? fails + " FAILED" : "ALL PASSED");
process.exit(fails ? 1 : 0);
