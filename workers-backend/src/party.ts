// Parties: invites, members, leader tools, party chat, pings/waypoints/emotes, live positions and
// storage sharing. Port of PartyService.java. In memory only: a party is gone when everybody left or
// nobody is online, exactly like the Java server (it never persisted them either).
import { Social, SocialError } from "./social";

const MAX_MEMBERS = 8;
const INVITE_MS = 5 * 60_000;
const CHAT_KEEP = 100;
const FOLLOW_SAME_SERVER_MS = 120_000;
const POSITION_FRESH_MS = 8_000;
const STORAGE_FRESH_MS = 30 * 60_000;
export const STORAGE_MAX_CHARS = 1_500_000;

type Win = [number, number];
const CHAT_LIMIT: Win[] = [[8, 5_000], [60, 60_000]];
const PING_LIMIT: Win[] = [[6, 5_000], [60, 60_000]];
const FOLLOW_LIMIT: Win[] = [[3, 60_000], [12, 600_000]];
const MANAGE_LIMIT: Win[] = [[30, 60_000]];
const POSITION_LIMIT: Win[] = [[8, 4_000]];
const STORAGE_LIMIT: Win[] = [[6, 30_000], [60, 600_000]];

interface ChatLine { id: number; from: string | null; name: string | null; text: string; time: number; kind: string }
interface ModSync { profile: string | null; mcVersion: string | null; mods: Record<string, unknown>[]; setAt: number }
interface Party {
  id: string;
  leader: string;
  members: string[];
  invites: Map<string, { expires: number; from: string }>;
  chat: ChatLine[];
  sync: ModSync | null;
  launch: { server: string | null; mcVersion: string | null; at: number } | null;
  voice: { name: string; password: string; setAt: number } | null;
  lastFollowServer?: string;
  lastFollowAt?: number;
}
interface Position { uuid: string; name: string; x: number; y: number; z: number; yaw: number; dimension: string; world: string; at: number }
interface StorageShare { uuid: string; name: string; server: string; world: string; at: number; data: string }

const clip = (t: unknown, max: number): string | null => (typeof t === "string" ? (t.length > max ? t.slice(0, max) : t) : null);
const num = (v: unknown): number => {
  const n = typeof v === "number" && Number.isFinite(v) ? v : 0;
  return Math.max(-3.0e7, Math.min(3.0e7, n));
};

export class Parties {
  private parties = new Map<string, Party>();
  private partyOf = new Map<string, string>();
  private positionsOf = new Map<string, Position>();
  private storageOf = new Map<string, StorageShare>();
  private chatIds = Date.now();

  constructor(private social: Social) {}

  // ---------------------------------------------------------------- helpers

  private party(uuid: string): Party | null {
    const id = this.partyOf.get(uuid);
    return id ? this.parties.get(id) ?? null : null;
  }
  private requireParty(me: string): Party {
    const p = this.party(me);
    if (!p) throw new SocialError(400, "You're not in a party");
    return p;
  }
  private requireLeader(me: string): Party {
    const p = this.requireParty(me);
    if (p.leader !== me) throw new SocialError(403, "Only the party leader can do that");
    return p;
  }
  private name(uuid: string): string {
    return this.social.nameOf(uuid);
  }
  private member(uuid: string, leader: boolean) {
    const a = this.social.activityOf(uuid);
    return { uuid, name: this.name(uuid), leader, online: a !== null, activity: a ? a.kind : null, detail: a ? a.detail : null };
  }
  private view(p: Party) {
    return {
      id: p.id,
      leader: p.leader,
      members: p.members.map((u) => this.member(u, u === p.leader)),
      invited: [...p.invites.keys()].map((u) => this.member(u, false)),
      chat: p.chat.slice(-50),
      sync: p.sync,
      launch: p.launch,
      voice: p.voice,
    };
  }
  private expireInvites(): void {
    const now = Date.now();
    for (const p of this.parties.values()) for (const [u, i] of p.invites) if (i.expires < now) p.invites.delete(u);
  }
  private add(p: Party, line: ChatLine): ChatLine {
    p.chat.push(line);
    while (p.chat.length > CHAT_KEEP) p.chat.shift();
    return line;
  }
  private system(p: Party, text: string): void {
    this.add(p, { id: ++this.chatIds, from: null, name: null, text, time: Date.now(), kind: "system" });
  }
  private broadcast(p: Party): void {
    const view = this.view(p);
    for (const m of p.members) this.social.pushEvent(m, "party", { party: view, invites: [] });
  }

  inParty(uuid: string): boolean {
    return this.partyOf.has(uuid);
  }

  // ---------------------------------------------------------------- state

  state(me: string) {
    this.expireInvites();
    const p = this.party(me);
    const invites: unknown[] = [];
    for (const q of this.parties.values()) {
      const i = q.invites.get(me);
      if (i) invites.push({ party: q.id, from: i.from, fromName: this.name(i.from), leaderName: this.name(q.leader), members: q.members.length, expires: i.expires });
    }
    return { party: p ? this.view(p) : null, invites };
  }

  create(me: string) {
    const existing = this.party(me);
    if (existing) return this.view(existing);
    const p: Party = {
      id: crypto.randomUUID().replace(/-/g, "").slice(0, 10), leader: me, members: [me], invites: new Map(),
      chat: [], sync: null, launch: null, voice: null,
    };
    this.parties.set(p.id, p);
    this.partyOf.set(me, p.id);
    this.system(p, this.name(me) + " started the party");
    this.broadcast(p);
    return this.view(p);
  }

  invite(me: string, target: string) {
    this.social.rateLimit(me, "party-manage", MANAGE_LIMIT);
    if (target === me) throw new SocialError(400, "You're already in your party");
    if (!this.social.friendsWith(me, target)) throw new SocialError(403, "You can only invite friends");
    let p = this.party(me);
    if (!p) {
      this.create(me);
      p = this.party(me)!;
    }
    if (p.members.includes(target)) throw new SocialError(400, this.name(target) + " is already in the party");
    if (p.members.length + p.invites.size >= MAX_MEMBERS) throw new SocialError(400, `Parties are limited to ${MAX_MEMBERS} players`);
    const expires = Date.now() + INVITE_MS;
    p.invites.set(target, { expires, from: me });
    this.social.pushEvent(target, "party_invite", { party: p.id, from: me, fromName: this.name(me), leaderName: this.name(p.leader), members: p.members.length, expires });
    this.system(p, `${this.name(me)} invited ${this.name(target)}`);
    this.broadcast(p);
    return this.view(p);
  }

  respond(me: string, partyId: unknown, accept: boolean) {
    this.expireInvites();
    const p = typeof partyId === "string" ? this.parties.get(partyId) : undefined;
    if (!p || !p.invites.delete(me)) throw new SocialError(404, "That invite expired");
    if (!accept) {
      this.system(p, this.name(me) + " declined the invite");
      this.broadcast(p);
      return;
    }
    if (this.party(me)) this.leave(me);
    p.members.push(me);
    this.partyOf.set(me, p.id);
    this.system(p, this.name(me) + " joined the party");
    this.broadcast(p);
  }

  leave(me: string): void {
    const p = this.requireParty(me);
    this.removeMember(p, me, this.name(me) + " left the party");
    this.social.pushEvent(me, "party", { party: null, invites: [] });
  }

  kick(me: string, target: string): void {
    const p = this.requireLeader(me);
    if (p.invites.delete(target)) return this.broadcast(p);
    if (!p.members.includes(target) || target === me) throw new SocialError(400, "Not in your party");
    this.removeMember(p, target, this.name(target) + " was removed from the party");
    this.social.pushEvent(target, "party", { party: null, invites: [] });
  }

  private removeMember(p: Party, uuid: string, message: string): void {
    p.members = p.members.filter((m) => m !== uuid);
    this.partyOf.delete(uuid);
    this.positionsOf.delete(uuid);
    this.storageOf.delete(uuid);
    if (p.members.length === 0) {
      this.parties.delete(p.id);
      return;
    }
    if (p.leader === uuid) {
      p.leader = p.members[0];
      p.sync = null;
      p.launch = null;
      p.voice = null;
      this.system(p, `${message} - ${this.name(p.leader)} leads now`);
    } else {
      this.system(p, message);
    }
    this.broadcast(p);
  }

  transfer(me: string, target: string): void {
    const p = this.requireLeader(me);
    if (!p.members.includes(target)) throw new SocialError(400, "Not in your party");
    p.leader = target;
    p.sync = null;
    p.launch = null;
    p.voice = null;
    this.system(p, this.name(target) + " is the party leader now");
    this.broadcast(p);
  }

  setSync(me: string, sync: Record<string, unknown> | null | undefined): void {
    const p = this.requireLeader(me);
    let clean: ModSync | null = null;
    if (sync) {
      const mods: Record<string, unknown>[] = [];
      const list = Array.isArray(sync.mods) ? (sync.mods as Record<string, unknown>[]) : [];
      for (const mod of list) {
        if (mods.length >= 300) break;
        const c: Record<string, unknown> = {};
        for (const key of ["name", "file", "url", "sha1", "projectId", "versionId", "size"]) {
          let v = mod?.[key];
          if (typeof v === "string" && v.length > 300) v = v.slice(0, 300);
          if (v !== undefined && v !== null) c[key] = v;
        }
        mods.push(c);
      }
      clean = { profile: clip(sync.profile, 60), mcVersion: clip(sync.mcVersion, 20), mods, setAt: Date.now() };
      this.system(p, `${this.name(me)} asks everyone to use their mod profile "${clean.profile}"`);
    } else if (p.sync) {
      this.system(p, this.name(me) + " stopped sharing their mods");
    }
    p.sync = clean;
    this.broadcast(p);
  }

  launch(me: string, server: unknown, mcVersion: unknown): void {
    this.social.rateLimit(me, "party-manage", MANAGE_LIMIT);
    const p = this.requireLeader(me);
    const srv = clip(server, 120);
    const ver = clip(mcVersion, 20);
    p.launch = { server: srv, mcVersion: ver, at: Date.now() };
    this.system(p, `${this.name(me)} is launching${srv && srv.trim() ? " - joining " + srv : ""}`);
    for (const m of p.members) {
      if (m !== me) this.social.pushEvent(m, "party_launch", { party: p.id, leader: me, leaderName: this.name(me), server: srv ?? "", mcVersion: ver ?? "" });
    }
    this.broadcast(p);
  }

  setVoice(me: string, name: unknown, password: unknown): void {
    this.social.rateLimit(me, "party-manage", MANAGE_LIMIT);
    const p = this.requireLeader(me);
    p.voice = typeof name !== "string" || !name.trim() ? null : { name: clip(name.trim(), 24)!, password: clip(password, 40) ?? "", setAt: Date.now() };
    this.broadcast(p);
  }

  follow(me: string, server: unknown, serverName: unknown): void {
    this.social.rateLimit(me, "party-follow", FOLLOW_LIMIT);
    const p = this.requireLeader(me);
    const address = clip(typeof server === "string" ? server.trim() : "", 120) ?? "";
    if (!address) throw new SocialError(400, "No server");
    const now = Date.now();
    if (p.lastFollowServer?.toLowerCase() === address.toLowerCase() && now - (p.lastFollowAt ?? 0) < FOLLOW_SAME_SERVER_MS) return;
    p.lastFollowServer = address;
    p.lastFollowAt = now;
    const label = typeof serverName === "string" && serverName.trim() ? clip(serverName.trim(), 60) : address;
    for (const m of p.members) {
      if (m !== me) this.social.pushEvent(m, "party_follow", { party: p.id, leader: me, leaderName: this.name(me), server: address, serverName: label });
    }
  }

  // ---------------------------------------------------------------- live data

  setPosition(me: string, payload: Record<string, unknown> | null | undefined): void {
    this.social.rateLimit(me, "party-position", POSITION_LIMIT);
    this.requireParty(me);
    if (!payload) {
      this.positionsOf.delete(me);
      return;
    }
    this.positionsOf.set(me, {
      uuid: me, name: this.name(me), x: num(payload.x), y: num(payload.y), z: num(payload.z), yaw: num(payload.yaw),
      dimension: clip(payload.dimension, 60) ?? "minecraft:overworld", world: clip(payload.world, 120) ?? "", at: Date.now(),
    });
  }

  positions(me: string) {
    const p = this.requireParty(me);
    const now = Date.now();
    const out: Position[] = [];
    for (const m of p.members) {
      const pos = this.positionsOf.get(m);
      if (pos && m !== me && now - pos.at <= POSITION_FRESH_MS) out.push(pos);
    }
    return { positions: out };
  }

  setStorage(me: string, server: unknown, world: unknown, data: unknown): void {
    this.social.rateLimit(me, "party-storage", STORAGE_LIMIT);
    this.requireParty(me);
    if (typeof data !== "string" || typeof server !== "string") {
      this.storageOf.delete(me);
      return;
    }
    if (data.length > STORAGE_MAX_CHARS) throw new SocialError(413, "Storage too big to share");
    this.storageOf.set(me, { uuid: me, name: this.name(me), server: clip(server, 160)!, world: clip(world, 40) ?? "", at: Date.now(), data });
  }

  storage(me: string, since: number) {
    const p = this.requireParty(me);
    const now = Date.now();
    const shares: unknown[] = [];
    for (const m of p.members) {
      const s = this.storageOf.get(m);
      if (!s || m === me) continue;
      if (now - s.at > STORAGE_FRESH_MS) {
        this.storageOf.delete(m);
        continue;
      }
      shares.push({ uuid: s.uuid, name: s.name, server: s.server, world: s.world, at: s.at, data: s.at > since ? s.data : null });
    }
    return { now, shares };
  }

  chat(me: string, text: unknown) {
    this.social.rateLimit(me, "party-chat", CHAT_LIMIT);
    const p = this.requireParty(me);
    const clean = typeof text === "string" ? text.trim() : "";
    if (!clean) throw new SocialError(400, "Empty message");
    const line = this.add(p, { id: ++this.chatIds, from: me, name: this.name(me), text: clip(clean, 500)!, time: Date.now(), kind: "chat" });
    for (const m of p.members) this.social.pushEvent(m, "party_chat", line);
    return line;
  }

  share(me: string, kind: "ping" | "emote" | "waypoint", payload: Record<string, unknown> | null | undefined): void {
    this.social.rateLimit(me, "party-ping", PING_LIMIT);
    const p = this.requireParty(me);
    const clean: Record<string, unknown> = {};
    for (const key of ["name", "label", "x", "y", "z", "dimension", "world", "server", "color", "icon", "entity"]) {
      let v = payload?.[key];
      if (typeof v === "string" && v.length > 120) v = v.slice(0, 120);
      if (v !== undefined && v !== null) clean[key] = v;
    }
    clean.from = me;
    clean.fromName = this.name(me);
    clean.time = Date.now();
    if (kind === "waypoint") {
      this.add(p, { id: ++this.chatIds, from: me, name: this.name(me), text: "Shared a waypoint: " + (clean.name ?? "?"), time: Date.now(), kind: "waypoint" });
    }
    const type = kind === "waypoint" ? "party_waypoint" : kind === "emote" ? "party_emote" : "party_ping";
    for (const m of p.members) if (m !== me) this.social.pushEvent(m, type, clean);
    if (kind === "waypoint") this.broadcast(p);
  }

  /** Drops parties nobody is online in, and expired invites. */
  sweep(): void {
    this.expireInvites();
    for (const [id, p] of this.parties) {
      if (!p.members.some((m) => this.social.isLive(m))) {
        for (const m of p.members) {
          this.partyOf.delete(m);
          this.positionsOf.delete(m);
          this.storageOf.delete(m);
        }
        this.parties.delete(id);
      }
    }
  }
}
