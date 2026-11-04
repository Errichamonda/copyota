import { DurableObject } from "cloudflare:workers";

const MAX_BYTES = 1 << 20; // the WebSocket message limit

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

// The pairing QR points here. Phone cameras open web links but not app links, so this page
// hands the room and key to the app. They sit in the #fragment, which is never sent to the server.
const PAIR_PAGE = `<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<title>Copyota</title><body style="font:18px system-ui;margin:2em"><h1>Pair Copyota</h1>
<p><a id=a>Open Copyota</a></p><p>Install the Copyota app first if this does nothing.</p>
<script>a.href="copyota://pair?url="+encodeURIComponent(location.origin)+"&"+location.hash.slice(1);location=a.href</script>`;

export default {
  fetch(request, env) {
    const path = new URL(request.url).pathname;
    if (path === "/pair") return new Response(PAIR_PAGE, { headers: { "content-type": "text/html;charset=utf-8" } });
    const room = path.slice(1);
    if (!/^[0-9a-f]{32}$/.test(room)) return new Response(null, { status: 404 });
    return env.ROOM.get(env.ROOM.idFromName(room)).fetch(request);
  },
};
