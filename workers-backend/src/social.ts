// Friends, requests, blocks, direct messages, shared waypoints. Port of SocialService.java.
// Runs inside RegistryDO (same object as the sessions) so presence checks are local calls.
// Differences from the Java server, all for the free tier:
//  - no 25 s long-poll (it would hold the object open); /v1/social/poll answers at once and tells the
//    client when to ask again (pollAfterMs). Event queues stay in memory; a reset makes clients re-sync.
//  - persistence is key/value rows: "core" (people, friendships, requests, blocks, read markers) and one
//    "conv:a|b" row per conversation, written only when changed.

export class SocialError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export interface PresenceHost {
  hasLive(uuid: string, kind: "game" | "launcher"): boolean;
}

interface UserRecord { username?: string; lastSeen: number; invisible: boolean }
interface Message { id: number; from: string; to: string; text: string; time: number; kind: string; waypoint?: Record<string, unknown> | null }
interface Core {
  users: Record<string, UserRecord>;
  friendships: string[];
  requests: Record<string, number>;
  blocks: Record<string, string[]>;
  lastRead: Record<string, Record<string, number>>;
  nextMessageId: number;
}
export interface Activity { kind: string; detail: string | null }
interface Ev { seq: number; type: string; data: unknown }

const MAX_MESSAGE_LENGTH = 500;
const MAX_MESSAGES = 300;
const MAX_PENDING_OUTGOING = 100;
const MAX_EVENTS = 300;
const MAX_FRIENDS = 300;
const DECLINE_COOLDOWN_MS = 30 * 60_000;
export const POLL_AFTER_MS = 30_000;

type Win = [number, number]; // [max, windowMs]
const REQUEST_LIMIT: Win[] = [[5, 60_000], [30, 3_600_000]];
const MESSAGE_LIMIT: Win[] = [[8, 5_000], [60, 60_000], [600, 3_600_000]];
const SHARE_LIMIT: Win[] = [[10, 60_000], [60, 3_600_000]];
const MANAGE_LIMIT: Win[] = [[30, 60_000]];
const STATUS_LIMIT: Win[] = [[10, 60_000]];
const PRESENCE_LIMIT: Win[] = [[30, 60_000]];

const pair = (a: string, b: string): string => (a < b ? `${a}|${b}` : `${b}|${a}`);
const describeWait = (ms: number): string => (ms < 60_000 ? `${Math.max(1, Math.ceil(ms / 1000))}s` : `${Math.ceil(ms / 60_000)} min`);

export class Social {
  private core: Core = { users: {}, friendships: [], requests: {}, blocks: {}, lastRead: {}, nextMessageId: 1 };
  private convs = new Map<string, Message[]>();
  private dirtyCore = false;
  private dirtyConvs = new Set<string>();
  private startSeq = Date.now();
  private seq = this.startSeq;
  private events = new Map<string, Ev[]>();
  private droppedUpTo = new Map<string, number>();
  private gamePresence = new Map<string, Activity>();
  private lastAnnounced = new Map<string, string>();
  private declinedAt = new Map<string, number>();
  private limits = new Map<string, number[]>();

  constructor(private sql: SqlStorage, private host: PresenceHost) {
    this.sql.exec(`CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT NOT NULL)`);
    for (const row of this.sql.exec(`SELECT k, v FROM kv`)) {
      const k = row.k as string;
      const v = JSON.parse(row.v as string);
      if (k === "core") this.core = { ...this.core, ...v };
      else if (k.startsWith("conv:")) this.convs.set(k.slice(5), v);
    }
  }

  /** Writes whatever changed. Called once at the end of each request. */
  flush(): void {
    if (this.dirtyCore) {
      this.dirtyCore = false;
      this.sql.exec(`INSERT OR REPLACE INTO kv (k, v) VALUES ('core', ?)`, JSON.stringify(this.core));
    }
    for (const key of this.dirtyConvs) {
      this.sql.exec(`INSERT OR REPLACE INTO kv (k, v) VALUES (?, ?)`, "conv:" + key, JSON.stringify(this.convs.get(key) ?? []));
    }
    this.dirtyConvs.clear();
  }

  // ---------------------------------------------------------------- helpers

  private limit(uuid: string, action: string, windows: Win[]): void {
    const now = Date.now();
    const key = uuid + ":" + action;
    const hits = (this.limits.get(key) ?? []).filter((t) => now - t < Math.max(...windows.map((w) => w[1])));
    for (const [max, span] of windows) {
      const inWin = hits.filter((t) => now - t < span);
      if (inWin.length >= max) {
        throw new SocialError(429, "Slow down - try again in " + describeWait(inWin[0] + span - now));
      }
    }
    hits.push(now);
    this.limits.set(key, hits);
  }

  rateLimit(uuid: string, action: string, windows: [number, number][]): void {
    this.limit(uuid, action, windows);
  }

  sweepLimits(): void {
    const now = Date.now();
    for (const [k, v] of this.limits) if (!v.some((t) => now - t < 3_600_000)) this.limits.delete(k);
    for (const [k, t] of this.declinedAt) if (now - t > DECLINE_COOLDOWN_MS) this.declinedAt.delete(k);
  }

  private areFriends(a: string, b: string): boolean {
    return this.core.friendships.includes(pair(a, b));
  }
  private hasBlocked(blocker: string, target: string): boolean {
    return (this.core.blocks[blocker] ?? []).includes(target);
  }
  private user(uuid: string): UserRecord {
    return (this.core.users[uuid] ??= { lastSeen: 0, invisible: false });
  }
  nameOf(uuid: string): string {
    return this.core.users[uuid]?.username ?? uuid;
  }
  friendsOf(uuid: string): string[] {
    const out: string[] = [];
    for (const f of this.core.friendships) {
      const [a, b] = f.split("|");
      if (a === uuid) out.push(b);
      else if (b === uuid) out.push(a);
    }
    return out;
  }
  isLive(uuid: string): boolean {
    return this.host.hasLive(uuid, "game") || this.host.hasLive(uuid, "launcher");
  }
  friendsWith(a: string, b: string): boolean {
    return this.areFriends(a, b);
  }

  activityOf(uuid: string): Activity | null {
    const rec = this.core.users[uuid];
    if (rec?.invisible) return null;
    if (this.host.hasLive(uuid, "game")) return this.gamePresence.get(uuid) ?? { kind: "menu", detail: null };
    if (this.host.hasLive(uuid, "launcher")) return { kind: "launcher", detail: null };
    return null;
  }

  private unreadFrom(reader: string, other: string): number {
    const conv = this.convs.get(pair(reader, other));
    if (!conv) return 0;
    const readUpTo = this.core.lastRead[reader]?.[other] ?? 0;
    let n = 0;
    for (let i = conv.length - 1; i >= 0; i--) {
      if (conv[i].time <= readUpTo) break;
      if (conv[i].from === other) n++;
    }
    return n;
  }

  private friendView(viewer: string, uuid: string) {
    const activity = this.activityOf(uuid);
    return {
      uuid, username: this.nameOf(uuid), online: activity !== null, activity,
      lastSeen: this.core.users[uuid]?.lastSeen ?? 0, unread: this.unreadFrom(viewer, uuid),
    };
  }

  /** Queues an event for a player's next poll. Used by the party code too. */
  pushEvent(uuid: string, type: string, data: unknown): void {
    const q = this.events.get(uuid) ?? [];
    this.events.set(uuid, q);
    q.push({ seq: ++this.seq, type, data });
    while (q.length > MAX_EVENTS) this.droppedUpTo.set(uuid, q.shift()!.seq);
  }

  // ---------------------------------------------------------------- identity + presence

  onAuthenticated(uuid: string, username: string): void {
    const r = this.user(uuid);
    if (r.username !== username) {
      r.username = username;
      this.dirtyCore = true;
    }
    this.refreshPresence(uuid);
  }

  refreshPresence(uuid: string): void {
    if (!this.host.hasLive(uuid, "game")) this.gamePresence.delete(uuid);
    const rec = this.core.users[uuid];
    const activity = this.activityOf(uuid);
    const announced = this.lastAnnounced.get(uuid);
    const wasVisible = announced !== undefined && announced !== "offline";
    if (rec && (activity || wasVisible)) {
      rec.lastSeen = Date.now();
      this.dirtyCore = true;
    }
    const fp = activity ? `${activity.kind}\u0000${activity.detail}` : "offline";
    if (fp === announced) return;
    this.lastAnnounced.set(uuid, fp);
    const cameOnline = activity !== null && (announced === undefined || announced === "offline");
    for (const friend of this.friendsOf(uuid)) this.pushEvent(friend, cameOnline ? "friend_online" : "presence", this.friendView(friend, uuid));
  }

  setPresence(me: string, kind: unknown, detail: unknown): void {
    this.limit(me, "presence", PRESENCE_LIMIT);
    const k = typeof kind === "string" && ["server", "singleplayer", "realm", "menu"].includes(kind) ? kind : "menu";
    const d = typeof detail === "string" && detail.trim() ? detail.slice(0, 80) : null;
    this.gamePresence.set(me, { kind: k, detail: d });
    this.refreshPresence(me);
  }

  setInvisible(me: string, invisible: boolean): void {
    this.limit(me, "status", STATUS_LIMIT);
    const r = this.user(me);
    if (r.invisible !== invisible) {
      r.invisible = invisible;
      this.dirtyCore = true;
    }
    this.pushEvent(me, "state_changed", null);
    this.refreshPresence(me);
  }

  // ---------------------------------------------------------------- lookup

  async resolve(query: unknown): Promise<{ uuid: string; username: string }> {
    if (typeof query !== "string" || !query.trim()) throw new SocialError(400, "Enter a username or UUID");
    const t = query.trim();
    const dashless = t.replace(/-/g, "").toLowerCase();
    if (/^[0-9a-f]{32}$/.test(dashless)) return { uuid: dashless, username: this.nameOf(dashless) };
    if (!/^[a-zA-Z0-9_]{1,16}$/.test(t)) throw new SocialError(400, "That isn't a valid Minecraft username");
    for (const [uuid, rec] of Object.entries(this.core.users)) {
      if (rec.username && rec.username.toLowerCase() === t.toLowerCase()) return { uuid, username: rec.username };
    }
    let res: Response;
    try {
      res = await fetch("https://api.mojang.com/users/profiles/minecraft/" + encodeURIComponent(t));
    } catch {
      throw new SocialError(502, "Couldn't look up that player right now - try their UUID instead");
    }
    if (res.status === 200) {
      const j = (await res.json()) as { id: string; name: string };
      const uuid = j.id.toLowerCase();
      const r = this.user(uuid);
      if (!r.username) {
        r.username = j.name;
        this.dirtyCore = true;
      }
      return { uuid, username: j.name };
    }
    throw new SocialError(404, `No Minecraft player is called "${t}"`);
  }

  // ---------------------------------------------------------------- reads

  state(me: string) {
    const self = this.user(me);
    const friends = this.friendsOf(me).map((f) => this.friendView(me, f));
    friends.sort((a, b) => Number(!a.online) - Number(!b.online) || a.username.toLowerCase().localeCompare(b.username.toLowerCase()));
    const incoming: unknown[] = [];
    const outgoing: unknown[] = [];
    for (const [key, time] of Object.entries(this.core.requests)) {
      const [from, to] = key.split(">");
      if (to === me && !this.hasBlocked(me, from)) incoming.push({ uuid: from, username: this.nameOf(from), time });
      else if (from === me) outgoing.push({ uuid: to, username: this.nameOf(to), time });
    }
    const blocked = (this.core.blocks[me] ?? []).map((u) => ({ uuid: u, username: this.nameOf(u) }));
    return { me: { uuid: me, username: this.nameOf(me), invisible: self.invisible }, friends, incoming, outgoing, blocked, seq: this.seq };
  }

  messages(me: string, other: string, before: number) {
    if (!this.areFriends(me, other) && !this.convs.has(pair(me, other))) throw new SocialError(403, "You aren't friends with that player");
    const conv = this.convs.get(pair(me, other)) ?? [];
    const out: Message[] = [];
    for (let i = conv.length - 1; i >= 0 && out.length < 60; i--) {
      if (before > 0 && conv[i].time >= before) continue;
      out.push(conv[i]);
    }
    return { messages: out.reverse() };
  }

  recentConversation(a: string, b: string, count: number): Message[] {
    const conv = this.convs.get(pair(a, b)) ?? [];
    return conv.slice(Math.max(0, conv.length - count));
  }

  poll(me: string, since: number, pollAfterMs: number = POLL_AFTER_MS) {
    const q = this.events.get(me) ?? [];
    const dropped = this.droppedUpTo.get(me);
    if (since < this.startSeq || since > this.seq || (dropped !== undefined && since < dropped)) {
      return { reset: true, seq: this.seq, events: [], pollAfterMs };
    }
    const fresh = q.filter((e) => e.seq > since);
    if (fresh.length) return { reset: false, seq: fresh[fresh.length - 1].seq, events: fresh, pollAfterMs };
    return { reset: false, seq: since, events: [], pollAfterMs };
  }

  // ---------------------------------------------------------------- friend graph

  async sendRequest(me: string, target: unknown) {
    this.limit(me, "request", REQUEST_LIMIT);
    const ref = await this.resolve(target);
    if (ref.uuid === me) throw new SocialError(400, "You can't add yourself");
    if (this.areFriends(me, ref.uuid)) throw new SocialError(409, `${ref.username} is already your friend`);
    if (this.hasBlocked(me, ref.uuid)) throw new SocialError(409, `Unblock ${ref.username} first`);
    if (this.core.requests[`${me}>${ref.uuid}`]) throw new SocialError(409, `You already sent ${ref.username} a request`);
    if (this.core.requests[`${ref.uuid}>${me}`]) {
      this.accept(me, ref.uuid);
      return ref;
    }
    const declined = this.declinedAt.get(`${me}>${ref.uuid}`);
    if (declined && Date.now() - declined < DECLINE_COOLDOWN_MS) {
      throw new SocialError(429, `${ref.username} declined your last request - try again in ${describeWait(declined + DECLINE_COOLDOWN_MS - Date.now())}`);
    }
    if (Object.keys(this.core.requests).filter((k) => k.startsWith(me + ">")).length >= MAX_PENDING_OUTGOING) {
      throw new SocialError(429, "Too many pending requests - cancel some first");
    }
    if (this.friendsOf(me).length >= MAX_FRIENDS) throw new SocialError(409, "Your friends list is full");
    this.user(ref.uuid);
    const now = Date.now();
    this.core.requests[`${me}>${ref.uuid}`] = now;
    this.dirtyCore = true;
    if (!this.hasBlocked(ref.uuid, me)) this.pushEvent(ref.uuid, "friend_request", { uuid: me, username: this.nameOf(me), time: now });
    this.pushEvent(me, "state_changed", null);
    return ref;
  }

  respond(me: string, from: string, accept: boolean): void {
    this.limit(me, "manage", MANAGE_LIMIT);
    if (!this.core.requests[`${from}>${me}`]) throw new SocialError(404, "That request no longer exists");
    if (accept) return this.accept(me, from);
    delete this.core.requests[`${from}>${me}`];
    this.declinedAt.set(`${from}>${me}`, Date.now());
    this.dirtyCore = true;
    this.pushEvent(me, "state_changed", null);
    this.pushEvent(from, "state_changed", null);
  }

  private accept(me: string, other: string): void {
    delete this.core.requests[`${other}>${me}`];
    delete this.core.requests[`${me}>${other}`];
    const p = pair(me, other);
    if (!this.core.friendships.includes(p)) this.core.friendships.push(p);
    this.dirtyCore = true;
    this.pushEvent(me, "friend_added", this.friendView(me, other));
    this.pushEvent(other, "friend_added", this.friendView(other, me));
    this.lastAnnounced.delete(me);
    this.lastAnnounced.delete(other);
  }

  cancelRequest(me: string, to: string): void {
    this.limit(me, "manage", MANAGE_LIMIT);
    if (this.core.requests[`${me}>${to}`]) {
      delete this.core.requests[`${me}>${to}`];
      this.dirtyCore = true;
      this.pushEvent(me, "state_changed", null);
      this.pushEvent(to, "state_changed", null);
    }
  }

  unfriend(me: string, other: string): void {
    this.limit(me, "manage", MANAGE_LIMIT);
    const p = pair(me, other);
    const i = this.core.friendships.indexOf(p);
    if (i >= 0) {
      this.core.friendships.splice(i, 1);
      this.dirtyCore = true;
      this.pushEvent(me, "friend_removed", { uuid: other, username: this.nameOf(other) });
      this.pushEvent(other, "friend_removed", { uuid: me, username: this.nameOf(me) });
    }
  }

  async block(me: string, target: unknown): Promise<void> {
    this.limit(me, "manage", MANAGE_LIMIT);
    const ref = await this.resolve(target);
    if (ref.uuid === me) throw new SocialError(400, "You can't block yourself");
    const list = (this.core.blocks[me] ??= []);
    if (!list.includes(ref.uuid)) list.push(ref.uuid);
    const p = pair(me, ref.uuid);
    const i = this.core.friendships.indexOf(p);
    if (i >= 0) this.core.friendships.splice(i, 1);
    delete this.core.requests[`${me}>${ref.uuid}`];
    delete this.core.requests[`${ref.uuid}>${me}`];
    this.dirtyCore = true;
    this.pushEvent(me, "state_changed", null);
    if (i >= 0) this.pushEvent(ref.uuid, "friend_removed", { uuid: me, username: this.nameOf(me) });
  }

  unblock(me: string, target: string): void {
    this.limit(me, "manage", MANAGE_LIMIT);
    const list = this.core.blocks[me];
    const i = list ? list.indexOf(target) : -1;
    if (i >= 0) {
      list.splice(i, 1);
      this.dirtyCore = true;
      this.pushEvent(me, "state_changed", null);
    }
  }

  // ---------------------------------------------------------------- messages

  sendMessage(me: string, to: string, text: unknown) {
    if (typeof text !== "string" || !text.trim()) throw new SocialError(400, "Message is empty");
    const t = text.trim();
    if (t.length > MAX_MESSAGE_LENGTH) throw new SocialError(400, `Messages are limited to ${MAX_MESSAGE_LENGTH} characters`);
    this.limit(me, "message", MESSAGE_LIMIT);
    return this.store(me, to, "text", t, null);
  }

  shareWaypoint(me: string, to: string[], waypoint: Record<string, unknown> | undefined): void {
    if (!waypoint || !("name" in waypoint)) throw new SocialError(400, "Missing waypoint");
    if (to.length > 20) throw new SocialError(400, "Share with at most 20 friends at once");
    this.limit(me, "share", SHARE_LIMIT);
    const clean: Record<string, unknown> = {};
    for (const key of ["name", "x", "y", "z", "dimension", "world", "color", "icon"]) {
      let v = waypoint[key];
      if (typeof v === "string" && v.length > 120) v = v.slice(0, 120);
      if (v !== undefined && v !== null) clean[key] = v;
    }
    for (const friend of to) this.store(me, friend, "waypoint", "Shared a waypoint: " + clean.name, clean);
  }

  private store(me: string, to: string, kind: string, text: string, waypoint: Record<string, unknown> | null): Message {
    if (!this.areFriends(me, to)) throw new SocialError(403, "You can only message friends");
    if (this.hasBlocked(to, me) || this.hasBlocked(me, to)) throw new SocialError(403, "This player isn't accepting messages from you");
    const key = pair(me, to);
    const conv = this.convs.get(key) ?? [];
    this.convs.set(key, conv);
    const last = conv.length ? conv[conv.length - 1].time : 0;
    const msg: Message = { id: this.core.nextMessageId++, from: me, to, text, time: Math.max(Date.now(), last + 1), kind, waypoint };
    conv.push(msg);
    while (conv.length > MAX_MESSAGES) conv.shift();
    (this.core.lastRead[me] ??= {})[to] = msg.time;
    this.dirtyCore = true;
    this.dirtyConvs.add(key);
    const payload = { message: msg, fromName: this.nameOf(me) };
    this.pushEvent(to, "message", payload);
    this.pushEvent(me, "message", payload);
    return msg;
  }

  markRead(me: string, other: string): void {
    const conv = this.convs.get(pair(me, other));
    const last = conv && conv.length ? conv[conv.length - 1].time : 0;
    (this.core.lastRead[me] ??= {})[other] = Math.max(Date.now(), last);
    this.dirtyCore = true;
    this.pushEvent(me, "read", { uuid: other, username: this.nameOf(other) });
  }
}
