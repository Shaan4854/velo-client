// Velo fork backend: stateless router in front of one Durable Object (RegistryDO).
// Free-plan note: every client request = 1 Worker request + 1 DO request, so keep it lean.
export { RegistryDO } from "./registry";

export interface Env {
  REGISTRY: DurableObjectNamespace;
}

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
    const stub = env.REGISTRY.get(env.REGISTRY.idFromName("global"));
    const ip = request.headers.get("cf-connecting-ip") ?? "";
    const headers = new Headers(request.headers);
    headers.set("x-client-ip", ip);
    const res = await stub.fetch(new Request(request.url, { method: request.method, headers, body: request.body }));
    const out = new Response(res.body, res);
    for (const [k, v] of Object.entries(CORS)) out.headers.set(k, v);
    return out;
  },
};
