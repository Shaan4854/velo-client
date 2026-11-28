// RegistryDO: sessions, presence/online feed, equipped cosmetics, offline name claims and custom capes.
// Hot data lives in memory (cheap reads, the free plan counts rows read); SQLite is write-through for
// what must survive an eviction. Sessions are only re-written when something changes, an expired one
// simply makes the client sign in again (it already handles a 401 that way).

const SESSION_TTL_MS = 240_000;
const HEARTBEAT_INTERVAL_S = 60;
const CHALLENGE_TTL_MS = 60_000;
const MAX_CAPE_BYTES = 1_900_000; // a Durable Object SQLite row tops out near 2 MB
const CUSTOM_PREFIX = "custom:";
const REDACTED = "cape_redacted";

type Kind = "game" | "launcher";

interface Session {
  emoteAt?: number;
  uuid: string;
  username: string;
  kind: Kind;
  capeId: string | null;
  wingsId: string | null;
  auraId: string | null;
  nameStyle: string | null;
  emote: string | null;
  wear: string | null;
  pet: string | null;
  petAct: string | null;
  og: boolean;
  expires: number;
}

interface Challenge {
  uuid: string;
  username: string;
  kind: Kind;
  expires: number;
  offline: boolean;
}

interface OnlineUser {
  emoteAtMillis?: number;
  uuid: string;
  username: string;
  capeId: string | null;
  wingsId: string | null;
  auraId: string | null;
  plus: boolean;
  nameStyle: string | null;
  emote: string | null;
  wear: string | null;
  pet: string | null;
  og: boolean;
  petAct: string | null;
}

class HttpError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

const json = (status: number, body: unknown, extra: Record<string, string> = {}): Response =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...extra } });

const normalizeUuid = (u: unknown): string | null => {
  if (typeof u !== "string") return null;
  const n = u.replace(/-/g, "").toLowerCase();
  return /^[0-9a-f]{32}$/.test(n) ? n : null;
};
const validUsername = (n: unknown): string | null => (typeof n === "string" && /^[a-zA-Z0-9_]{1,16}$/.test(n) ? n : null);
const hex = (buf: ArrayBuffer): string => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
const randomHex = (bytes: number): string => hex(crypto.getRandomValues(new Uint8Array(bytes)).buffer);

async function sha256Hex(data: ArrayBuffer | Uint8Array): Promise<string> {
  const view = data instanceof Uint8Array ? data : new Uint8Array(data);
  return hex(await crypto.subtle.digest("SHA-256", view));
}

/** Java's UUID.nameUUIDFromBytes("OfflinePlayer:<name>"), dashless: what an offline Minecraft account gets. */
async function offlineUuid(name: string): Promise<string> {
  const md5 = new Uint8Array(await crypto.subtle.digest("MD5", new TextEncoder().encode("OfflinePlayer:" + name)));
  md5[6] = (md5[6] & 0x0f) | 0x30;
  md5[8] = (md5[8] & 0x3f) | 0x80;
  return hex(md5.buffer);
}

const EMOTE_TTL_MS = 30_000; // longer than the slowest poll so one-shot emotes are not missed
const cleanId = (v: unknown, max = 120): string | null =>
  typeof v === "string" && v.length > 0 && v.length <= max && /^[A-Za-z0-9_:;.,#-]+$/.test(v) ? v : null;

export class RegistryDO {
  private sql: SqlStorage;
  private sessions = new Map<string, Session>();
  private challenges = new Map<string, Challenge>();
  private lastLooks = new Map<string, { capeId: string | null; wingsId: string | null; auraId: string | null; wear: string | null; pet: string | null }>();
  private capeOf = new Map<string, string>(); // uuid -> hash
  private rate = new Map<string, number[]>();
  private seenToday = new Set<string>();
  private seenDay = "";

  // online feed (version bumps on any visible change)
  private version = Date.now();
  private oldest = this.version;
  private current = new Map<string, OnlineUser>();
  private changedAt = new Map<string, number>();
  private removedAt = new Map<string, { v: number; t: number }>();

  constructor(private state: DurableObjectState, private env: unknown) {
    this.sql = state.storage.sql;
    state.blockConcurrencyWhile(async () => {
      this.sql.exec(`CREATE TABLE IF NOT EXISTS sessions (token TEXT PRIMARY KEY, data TEXT NOT NULL)`);
      this.sql.exec(`CREATE TABLE IF NOT EXISTS mc_names (lname TEXT PRIMARY KEY, uuid TEXT NOT NULL)`);
      this.sql.exec(`CREATE TABLE IF NOT EXISTS offline_claims (lname TEXT PRIMARY KEY, uuid TEXT NOT NULL, secret_hash TEXT NOT NULL)`);
      this.sql.exec(`CREATE TABLE IF NOT EXISTS capes (hash TEXT PRIMARY KEY, uuid TEXT NOT NULL, bytes BLOB NOT NULL)`);
      this.sql.exec(`CREATE TABLE IF NOT EXISTS last_looks (uuid TEXT PRIMARY KEY, data TEXT NOT NULL)`);
      const now = Date.now();
      for (const row of this.sql.exec(`SELECT token, data FROM sessions`)) {
        const s = JSON.parse(row.data as string) as Session;
        if (s.expires >= now) this.sessions.set(row.token as string, s);
      }
      for (const row of this.sql.exec(`SELECT uuid, data FROM last_looks`)) this.lastLooks.set(row.uuid as string, JSON.parse(row.data as string));
      for (const row of this.sql.exec(`SELECT hash, uuid FROM capes`)) this.capeOf.set(row.uuid as string, row.hash as string);
    });
  }

  async fetch(request: Request): Promise<Response> {
    try {
      this.sweep();
      return await this.route(request);
    } catch (e) {
      if (e instanceof HttpError) return json(e.status, { error: e.message });
      console.error(e);
      return json(500, { error: "Internal server error" });
    }
  }

  // ---------------------------------------------------------------- routing

  private async route(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method;
    const need = (m: string) => {
      if (method !== m) throw new HttpError(405, "Method not allowed");
    };
    switch (path) {
      case "/v1/health":
        need("GET");
        return json(200, { status: "ok", onlineCount: this.onlineUsers().length });
      case "/v1/stats/live":
        need("GET");
        return json(200, { online: this.onlineUsers().length, today: this.seenToday.size, at: Date.now() }, { "cache-control": "public, max-age=30" });
      case "/v1/session/challenge":
        need("POST");
        return this.challenge(request);
      case "/v1/session/verify":
        need("POST");
        return this.verify(request);
      case "/v1/session/offline-claim":
        need("POST");
        return this.offlineClaim(request);
      case "/v1/session/end":
      case "/v1/session/end-game": {
        need("POST");
        const body = await this.body(request);
        const token = typeof body.sessionToken === "string" ? body.sessionToken : null;
        if (token) path.endsWith("end-game") ? this.endGameSessions(token) : this.endSession(token);
        return json(200, { ok: true, onlineCount: this.onlineUsers().length, capeId: null, ogEligible: false });
      }
      case "/v1/heartbeat":
        need("POST");
        return this.heartbeat(request);
      case "/v1/online":
        need("GET");
        return json(200, this.onlineAnswer(Number(url.searchParams.get("since") ?? "-1")), { "cache-control": "no-store" });
      case "/v1/cosmetics/of":
        need("POST");
        return this.cosmeticsOf(request);
      case "/v1/cape/upload":
        need("POST");
        return this.capeUpload(request);
      case "/v1/cape/remove":
        need("POST");
        return this.capeRemove(request);
    }
    if (path.startsWith("/v1/cape/") && method === "GET") return this.capeDownload(path.slice("/v1/cape/".length));
    throw new HttpError(404, "Not found");
  }

  private async body(request: Request): Promise<Record<string, unknown>> {
    try {
      const v = await request.json();
      return v && typeof v === "object" ? (v as Record<string, unknown>) : {};
    } catch {
      throw new HttpError(400, "Bad request");
    }
  }

  private limited(request: Request, action: string): void {
    const ip = request.headers.get("x-client-ip") ?? "?";
    const key = action + "|" + ip;
    const now = Date.now();
    const hits = (this.rate.get(key) ?? []).filter((t) => now - t < 60_000);
    if (hits.length >= 40) throw new HttpError(429, "Too many sign-in attempts - try again in a minute");
    hits.push(now);
    this.rate.set(key, hits);
  }

  private sweep(): void {
    const now = Date.now();
    const day = new Date(now).toISOString().slice(0, 10);
    if (day !== this.seenDay) {
      this.seenDay = day;
      this.seenToday.clear();
    }
    for (const [token, s] of this.sessions) {
      if (s.expires < now) {
        this.sessions.delete(token);
        this.sql.exec(`DELETE FROM sessions WHERE token = ?`, token);
      }
    }
    for (const [id, c] of this.challenges) if (c.expires < now) this.challenges.delete(id);
    if (this.rate.size > 2000) this.rate.clear();
  }

  // ---------------------------------------------------------------- sessions

  private async challenge(request: Request): Promise<Response> {
    this.limited(request, "challenge");
    const b = await this.body(request);
    const uuid = normalizeUuid(b.uuid);
    const username = validUsername(b.username);
    if (!uuid || !username) throw new HttpError(400, "uuid and username are required");
    const kind: Kind = b.kind === "launcher" ? "launcher" : "game";
    const serverId = randomHex(16);
    this.challenges.set(serverId, { uuid, username, kind, expires: Date.now() + CHALLENGE_TTL_MS, offline: false });
    return json(200, { serverId });
  }

  private async verify(request: Request): Promise<Response> {
    this.limited(request, "verify");
    const b = await this.body(request);
    const uuid = normalizeUuid(b.uuid);
    const serverId = typeof b.serverId === "string" ? b.serverId : "";
    if (!uuid || !serverId) throw new HttpError(400, "uuid and serverId are required");
    const ch = this.challenges.get(serverId);
    this.challenges.delete(serverId);
    if (!ch || ch.uuid !== uuid || ch.expires < Date.now()) throw new HttpError(400, "Unknown or expired challenge - request a new one");
    const res = await fetch(
      `https://sessionserver.mojang.com/session/minecraft/hasJoined?username=${encodeURIComponent(ch.username)}&serverId=${encodeURIComponent(serverId)}`,
    );
    let verified: string | null = null;
    if (res.status === 200) {
      const data = (await res.json()) as { id?: string };
      verified = normalizeUuid(data.id);
    }
    if (!verified || verified !== uuid) throw new HttpError(401, "Mojang could not verify this session - did the client actually join with this serverId?");
    // A real Microsoft account owns its name: it reserves it and ends any offline claim of the same name.
    const lname = ch.username.toLowerCase();
    this.sql.exec(`INSERT OR REPLACE INTO mc_names (lname, uuid) VALUES (?, ?)`, lname, uuid);
    this.sql.exec(`DELETE FROM offline_claims WHERE lname = ?`, lname);
    return json(200, this.openSession(uuid, ch.username, ch.kind));
  }

  /**
   * Offline accounts have no Mojang proof. Trust on first use: the first client to claim a name stores a random
   * secret on its device; only that secret can open a session for the name later. Microsoft names are reserved.
   */
  private async offlineClaim(request: Request): Promise<Response> {
    this.limited(request, "claim");
    const b = await this.body(request);
    const uuid = normalizeUuid(b.uuid);
    const username = validUsername(b.username);
    const secret = typeof b.secret === "string" ? b.secret : "";
    if (!uuid || !username || secret.length < 16 || secret.length > 128) throw new HttpError(400, "uuid, username and secret are required");
    if ((await offlineUuid(username)) !== uuid) throw new HttpError(400, "That is not the offline UUID of this name");
    const lname = username.toLowerCase();
    const mc = this.sql.exec(`SELECT uuid FROM mc_names WHERE lname = ?`, lname).toArray()[0];
    if (mc) throw new HttpError(409, "That name belongs to a Microsoft account - pick another offline name");
    const secretHash = await sha256Hex(new TextEncoder().encode(secret));
    const existing = this.sql.exec(`SELECT secret_hash FROM offline_claims WHERE lname = ?`, lname).toArray()[0];
    if (!existing) {
      this.sql.exec(`INSERT INTO offline_claims (lname, uuid, secret_hash) VALUES (?, ?, ?)`, lname, uuid, secretHash);
    } else if (existing.secret_hash !== secretHash) {
      throw new HttpError(403, "This offline name is already claimed by another device");
    }
    const kind: Kind = b.kind === "launcher" ? "launcher" : "game";
    return json(200, this.openSession(uuid, username, kind));
  }

  private openSession(uuid: string, username: string, kind: Kind) {
    const token = randomHex(24);
    for (const [t, old] of this.sessions) if (old.uuid === uuid && old.kind === kind) this.endSession(t);
    const s: Session = {
      uuid, username, kind, capeId: null, wingsId: null, auraId: null, nameStyle: null,
      emote: null, wear: null, pet: null, petAct: null, og: false, expires: Date.now() + SESSION_TTL_MS,
    };
    this.sessions.set(token, s);
    this.persist(token, s);
    this.seenToday.add(uuid);
    return { sessionToken: token, heartbeatIntervalSeconds: HEARTBEAT_INTERVAL_S, sessionTtlSeconds: SESSION_TTL_MS / 1000 };
  }

  private persist(token: string, s: Session): void {
    this.sql.exec(`INSERT OR REPLACE INTO sessions (token, data) VALUES (?, ?)`, token, JSON.stringify(s));
  }

  private endSession(token: string): void {
    if (this.sessions.delete(token)) this.sql.exec(`DELETE FROM sessions WHERE token = ?`, token);
  }

  private endGameSessions(launcherToken: string): void {
    const l = this.sessions.get(launcherToken);
    if (!l) return;
    for (const [t, s] of this.sessions) if (s.uuid === l.uuid && s.kind === "game") this.endSession(t);
  }

  private session(token: string | null | undefined): Session | null {
    if (!token) return null;
    const s = this.sessions.get(token);
    return s && s.expires >= Date.now() ? s : null;
  }

  private bearer(request: Request): string | null {
    const h = request.headers.get("authorization") ?? "";
    return h.toLowerCase().startsWith("bearer ") ? h.slice(7).trim() : null;
  }

  // ---------------------------------------------------------------- heartbeat + online feed

  private async heartbeat(request: Request): Promise<Response> {
    const b = await this.body(request);
    const token = typeof b.sessionToken === "string" ? b.sessionToken : "";
    if (!token) throw new HttpError(400, "sessionToken is required");
    const s = this.session(token);
    if (!s) throw new HttpError(401, "Unknown or expired session - re-authenticate");
    s.expires = Date.now() + SESSION_TTL_MS;
    this.seenToday.add(s.uuid);
    let capeId = cleanId(b.capeId, 80);
    if (capeId && capeId.startsWith(CUSTOM_PREFIX)) {
      const mine = this.capeOf.get(s.uuid);
      if (!mine || capeId !== CUSTOM_PREFIX + mine) capeId = null;
    }
    const before = JSON.stringify([s.capeId, s.wingsId, s.auraId, s.nameStyle, s.emote, s.wear, s.pet, s.petAct]);
    s.capeId = capeId;
    if (s.kind === "game") {
      s.wingsId = cleanId(b.wingsId);
      s.auraId = cleanId(b.auraId);
      s.nameStyle = cleanId(b.nameStyle);
      const emote = cleanId(b.emote);
      if (emote !== s.emote) s.emoteAt = emote ? Date.now() : 0;
      s.emote = emote;
      s.wear = typeof b.wear === "string" && b.wear.length <= 400 && /^[a-z0-9_:,-]*$/.test(b.wear) ? b.wear || null : null;
      s.pet = typeof b.pet === "string" && /^[a-z0-9_]{1,40}$/.test(b.pet) ? b.pet : null;
      s.petAct = typeof b.petAct === "string" && /^[01];(trick|cheer|none);[0-9]{1,5}$/.test(b.petAct) ? b.petAct : null;
      s.og = false;
    }
    const after = JSON.stringify([s.capeId, s.wingsId, s.auraId, s.nameStyle, s.emote, s.wear, s.pet, s.petAct]);
    if (before !== after) {
      this.persist(token, s);
      if (s.kind === "game") this.recordLook(s);
    }
    return json(200, { ok: true, onlineCount: this.onlineUsers().length, capeId: s.capeId, ogEligible: false });
  }

  private recordLook(s: Session): void {
    const look = { capeId: s.capeId, wingsId: s.wingsId, auraId: s.auraId, wear: s.wear, pet: s.pet };
    if (!look.capeId && !look.wingsId && !look.auraId && !look.wear && !look.pet) return;
    this.lastLooks.set(s.uuid, look);
    this.sql.exec(`INSERT OR REPLACE INTO last_looks (uuid, data) VALUES (?, ?)`, s.uuid, JSON.stringify(look));
  }

  private onlineUsers(): OnlineUser[] {
    const now = Date.now();
    const out = new Map<string, OnlineUser>();
    for (const s of this.sessions.values()) {
      if (s.kind !== "game" || s.expires < now) continue;
      out.set(s.uuid, {
        uuid: s.uuid, username: s.username, capeId: s.capeId, wingsId: s.wingsId, auraId: s.auraId, plus: false,
        nameStyle: s.nameStyle, emote: s.emote && now - (s.emoteAt ?? 0) < EMOTE_TTL_MS ? s.emote : null,
        emoteAtMillis: s.emote && now - (s.emoteAt ?? 0) < EMOTE_TTL_MS ? s.emoteAt : undefined, wear: s.wear, pet: s.pet, og: false, petAct: s.petAct,
      });
    }
    return [...out.values()];
  }

  private onlineAnswer(since: number) {
    const now = Date.now();
    const next = new Map(this.onlineUsers().map((u) => [u.uuid, u]));
    const v = this.version + 1;
    let dirty = false;
    for (const [id, u] of next) {
      if (JSON.stringify(u) !== JSON.stringify(this.current.get(id))) {
        this.changedAt.set(id, v);
        this.removedAt.delete(id);
        dirty = true;
      }
    }
    for (const id of this.current.keys()) {
      if (!next.has(id)) {
        this.changedAt.delete(id);
        this.removedAt.set(id, { v, t: now });
        dirty = true;
      }
    }
    for (const [id, r] of this.removedAt) if (now - r.t > 600_000) this.removedAt.delete(id);
    if (dirty) {
      this.version = v;
      this.oldest = Math.min(this.oldest, v);
      this.current = next;
    }
    if (since < this.oldest || since > this.version) {
      return { users: [...this.current.values()], version: this.version, serverTimeMillis: now };
    }
    const users: OnlineUser[] = [];
    if (since < this.version) {
      for (const [id, at] of this.changedAt) {
        if (at > since) {
          const u = this.current.get(id);
          if (u) users.push(u);
        }
      }
    }
    const removed: string[] = [];
    if (since < this.version) for (const [id, r] of this.removedAt) if (r.v > since) removed.push(id);
    return { users, removed, version: this.version, delta: true, serverTimeMillis: now };
  }

  private async cosmeticsOf(request: Request): Promise<Response> {
    const b = await this.body(request);
    const includeOnline = b.includeOnline === true;
    const uuids = Array.isArray(b.uuids) ? (b.uuids as unknown[]).slice(0, 200) : [];
    const online = new Map(this.onlineUsers().map((u) => [u.uuid, u]));
    const users: unknown[] = [];
    for (const raw of uuids) {
      const uuid = normalizeUuid(raw);
      if (!uuid) continue;
      const now = online.get(uuid);
      if (now) {
        if (includeOnline && (now.capeId || now.wingsId || now.auraId || now.wear || now.pet)) {
          users.push({ uuid, capeId: now.capeId, wingsId: now.wingsId, auraId: now.auraId, wear: now.wear, pet: now.pet });
        }
        continue;
      }
      const look = this.lastLooks.get(uuid);
      if (look) users.push({ uuid, ...look });
    }
    return json(200, { users });
  }

  // ---------------------------------------------------------------- custom capes

  private async capeUpload(request: Request): Promise<Response> {
    const s = this.session(this.bearer(request));
    if (!s) throw new HttpError(401, "Unknown or expired session - re-authenticate");
    const bytes = new Uint8Array(await request.arrayBuffer());
    if (bytes.length > MAX_CAPE_BYTES) throw new HttpError(413, "Cloak file too large (max 1.9 MB on this server)");
    const sig = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
    if (bytes.length < 33 || sig.some((v, i) => bytes[i] !== v)) throw new HttpError(400, "Only PNG cloaks are supported");
    const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const w = dv.getUint32(16);
    const h = dv.getUint32(20);
    const okShape = w % 64 === 0 && w >= 64 && w <= 2048 && h <= 1024 && (w === h * 2 || w === h);
    if (!okShape) throw new HttpError(400, "Cloak must be a 2:1 (or 1:1 cape+elytra) PNG up to 2048x1024, width a multiple of 64");
    const hash = await sha256Hex(bytes);
    const old = this.capeOf.get(s.uuid);
    this.sql.exec(`INSERT OR REPLACE INTO capes (hash, uuid, bytes) VALUES (?, ?, ?)`, hash, s.uuid, bytes);
    if (old && old !== hash) this.sql.exec(`DELETE FROM capes WHERE hash = ? AND uuid = ?`, old, s.uuid);
    this.capeOf.set(s.uuid, hash);
    return json(200, { capeId: CUSTOM_PREFIX + hash });
  }

  private async capeRemove(request: Request): Promise<Response> {
    const s = this.session(this.bearer(request));
    if (!s) throw new HttpError(401, "Unknown or expired session - re-authenticate");
    const old = this.capeOf.get(s.uuid);
    if (!old) return json(200, { ok: false });
    this.sql.exec(`DELETE FROM capes WHERE hash = ? AND uuid = ?`, old, s.uuid);
    this.capeOf.delete(s.uuid);
    return json(200, { ok: true });
  }

  private capeDownload(hash: string): Response {
    if (!/^[0-9a-f]{64}$/.test(hash)) throw new HttpError(404, "No such cloak");
    const row = this.sql.exec(`SELECT bytes FROM capes WHERE hash = ?`, hash).toArray()[0];
    if (!row) throw new HttpError(404, "No such cloak");
    return new Response(row.bytes as ArrayBuffer, {
      headers: { "content-type": "image/png", "cache-control": "public, max-age=31536000, immutable" },
    });
  }
}

export { REDACTED };
