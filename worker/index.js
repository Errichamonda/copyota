import { DurableObject } from "cloudflare:workers";
import { MAX_BYTES, PAIR_PAGE, ROOM } from "./shared.js";

// Relays ciphertext from the phone to the Mac's socket. Stores nothing.
export class Room extends DurableObject {
  async fetch(request) {
    if (request.headers.get("Upgrade") === "websocket") {
      const { 0: client, 1: server } = new WebSocketPair();
      this.ctx.acceptWebSocket(server);
      return new Response(null, { status: 101, webSocket: client });
    }
    if (request.method !== "POST") return new Response(null, { status: 405 });
    const body = await request.text();
    if (body.length > MAX_BYTES) return new Response(null, { status: 413 });
    for (const socket of this.ctx.getWebSockets()) socket.send(body);
    return new Response(null, { status: 204 });
  }
}

export default {
  fetch(request, env) {
    const path = new URL(request.url).pathname;
    if (path === "/pair") return new Response(PAIR_PAGE, { headers: { "content-type": "text/html;charset=utf-8" } });
    const room = path.slice(1);
    if (!ROOM.test(room)) return new Response(null, { status: 404 });
    return env.ROOM.get(env.ROOM.idFromName(room)).fetch(request);
  },
};
