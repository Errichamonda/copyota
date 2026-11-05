// The relay without Cloudflare, with the same routes as index.js. Stores nothing.
// Run it with `deno run --allow-net worker/selfhost.js` behind an HTTPS proxy.
import { MAX_BYTES, PAIR_PAGE, ROOM } from "./shared.js";

const rooms = new Map(); // room id to the sockets listening on it

function listen(request, room) {
  const { socket, response } = Deno.upgradeWebSocket(request);
  socket.onopen = () => rooms.set(room, (rooms.get(room) ?? new Set()).add(socket));
  socket.onclose = () => {
    rooms.get(room)?.delete(socket);
    if (!rooms.get(room)?.size) rooms.delete(room);
  };
  return response;
}

export async function handle(request) {
  const path = new URL(request.url).pathname;
  if (path === "/pair") return new Response(PAIR_PAGE, { headers: { "content-type": "text/html;charset=utf-8" } });
  const room = path.slice(1);
  if (!ROOM.test(room)) return new Response(null, { status: 404 });
  if (request.headers.get("upgrade")?.toLowerCase() === "websocket") return listen(request, room);
  if (request.method !== "POST") return new Response(null, { status: 405 });
  // Checked before reading, so a stranger can't stream an endless body into memory.
  const size = Number(request.headers.get("content-length"));
  if (!size) return new Response(null, { status: 411 });
  if (size > MAX_BYTES) return new Response(null, { status: 413 });
  const body = await request.text();
  for (const socket of rooms.get(room) ?? []) socket.send(body);
  return new Response(null, { status: 204 });
}

if (import.meta.main) Deno.serve({ port: 8080 }, handle);
