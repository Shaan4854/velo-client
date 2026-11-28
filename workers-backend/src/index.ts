// Velo fork backend: stateless router in front of one Durable Object (RegistryDO).
// Free-plan note: every client request = 1 Worker request + 1 DO request, so keep it lean.
export { RegistryDO } from "./registry";

export interface Env {
  REGISTRY: DurableObjectNamespace;
  /** Public read-only content (store catalog, art, news) is fetched from here. Empty disables the proxy. */
  UPSTREAM_BASE?: string;
}

// Routes the Durable Object handles. Everything else is not ours: public GETs are proxied (cached),
// anything else answers 404 so the client treats the feature as unavailable.
const OWN = /^\/v1\/(health|ws$|session\/|heartbeat$|online$|stats\/live$|cosmetics\/of$|cape\/|social\/|party\/)/;

const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "content-type, authorization",
  "access-control-allow-methods": "GET, POST, OPTIONS",
};

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    const url = new URL(request.url);
    if (!url.pathname.startsWith("/v1/")) return new Response("Not found", { status: 404, headers: CORS });
    if (!OWN.test(url.pathname)) return passthrough(request, url, env);
    const stub = env.REGISTRY.get(env.REGISTRY.idFromName("global"));
    if (request.headers.get("upgrade")?.toLowerCase() === "websocket") return stub.fetch(request);
    const ip = request.headers.get("cf-connecting-ip") ?? "";
    const headers = new Headers(request.headers);
    headers.set("x-client-ip", ip);
    const res = await stub.fetch(new Request(request.url, { method: request.method, headers, body: request.body }));
    const out = new Response(res.body, res);
    for (const [k, v] of Object.entries(CORS)) out.headers.set(k, v);
    return out;
  },
};

// The client adds its session token to catalog/item/file reads. These are public, and we never forward the header
// upstream (the upstream request below is anonymous), so the token is simply ignored for them.
const PUBLIC_WITH_AUTH = /^\/v1\/cosmetics\/(catalog|item|file\/)/;

async function passthrough(request: Request, url: URL, env: Env): Promise<Response> {
  const notHere = () => new Response(JSON.stringify({ error: "Not available on this server" }), { status: 404, headers: { "content-type": "application/json", ...CORS } });
  const base = (env.UPSTREAM_BASE ?? "").replace(/\/$/, "");
  if (!base || request.method !== "GET" || (request.headers.has("authorization") && !PUBLIC_WITH_AUTH.test(url.pathname)) || /^\/v1\/(admin|staff|staffpanel|tester)\//.test(url.pathname)) return notHere();
  // Only anonymous public reads: no auth header is forwarded, results are cached for 10 minutes.
  const cache = caches.default;
  const key = new Request(base + url.pathname + url.search);
  const hit = await cache.match(key);
  if (hit) return withCors(hit);
  let res: Response;
  try {
    res = await fetch(key, { headers: { accept: request.headers.get("accept") ?? "*/*" } });
  } catch {
    return notHere();
  }
  if (res.status !== 200) return withCors(new Response(res.body, { status: res.status === 404 ? 404 : 502, headers: { "content-type": "application/json" } }));
  const out = new Response(res.body, res);
  out.headers.set("cache-control", "public, max-age=600");
  await cache.put(key, out.clone());
  return withCors(out);
}

function withCors(res: Response): Response {
  const out = new Response(res.body, res);
  for (const [k, v] of Object.entries(CORS)) out.headers.set(k, v);
  return out;
}
