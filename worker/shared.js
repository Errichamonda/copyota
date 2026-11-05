// What the Cloudflare relay (index.js) and the self-hosted one (selfhost.js) have in common.
export const MAX_BYTES = 1 << 20; // the WebSocket message limit
export const ROOM = /^[0-9a-f]{32}$/;

// The pairing QR points here. Phone cameras open web links but not app links, so this page
// hands the room and key to the app. They sit in the #fragment, which is never sent to the server.
export const PAIR_PAGE = `<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<title>Copyota</title><body style="font:18px system-ui;margin:2em"><h1>Pair Copyota</h1>
<p><a id=a>Open Copyota</a></p><p>Install the Copyota app first if this does nothing.</p>
<script>a.href="copyota://pair?url="+encodeURIComponent(location.origin)+"&"+location.hash.slice(1);location=a.href</script>`;
