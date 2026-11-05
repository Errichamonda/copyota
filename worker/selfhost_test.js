// Run with `deno test --allow-net worker/`.
import { handle } from "./selfhost.js";

const equal = (a, b) => { if (a !== b) throw new Error(`expected ${b}, got ${a}`); };

Deno.test("relays a POST to the room's sockets and rejects bad requests", async () => {
  const server = Deno.serve({ port: 0, onListen() {} }, handle);
  const base = `http://localhost:${server.addr.port}`;
  const room = "0".repeat(32);
  const status = async (path, init) => {
    const response = await fetch(base + path, init);
    await response.body?.cancel();
    return response.status;
  };

  const socket = new WebSocket(`${base.replace("http", "ws")}/${room}`);
  await new Promise((open) => socket.onopen = open);
  const received = new Promise((message) => socket.onmessage = (e) => message(e.data));
  await new Promise((settle) => setTimeout(settle, 50)); // the server's onopen runs after the client's

  equal(await status(`/${room}`, { method: "POST", body: "sealed" }), 204);
  equal(await received, "sealed");
  equal(await status(`/${room}`, { method: "POST", body: "x".repeat((1 << 20) + 1) }), 413);
  equal(await status(`/${room}`, { method: "POST", body: new Blob(["x"]).stream() }), 411);
  equal(await status(`/${room}`), 405);
  equal(await status("/nope", { method: "POST", body: "x" }), 404);
  equal(await status("/pair"), 200);

  const closed = new Promise((done) => socket.onclose = done);
  socket.close();
  await closed;
  await server.shutdown();
});
