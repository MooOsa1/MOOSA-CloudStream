import test from "node:test";
import assert from "node:assert/strict";
import { startServer, metadataRoute } from "../server.mjs";
test("metadata demo is available without a key", async () => {
  const s = await metadataRoute("/api/status", new URLSearchParams());
  assert.deepEqual(s.player, ["mp4","hls","webvtt","srt"]);
  if (!s.configured) {
    const home = await metadataRoute("/api/home", new URLSearchParams());
    assert.equal(home.sections[0].items[0].id, "demo-bunny");
    const details = await metadataRoute("/api/details", new URLSearchParams("type=movie&id=demo-bunny"));
    assert.equal(details.sources[0].type, "mp4");
    const listed = await metadataRoute("/api/sources", new URLSearchParams("key=movie:demo-bunny"));
    assert.equal(listed.sources.length, 1);
    assert.ok(listed.sources[0].url.startsWith("https://"));
    const unauthorized = await metadataRoute("/api/sources", new URLSearchParams("key=movie:12345"));
    assert.equal(unauthorized.sources.length, 0);
  }
});
test("Node server serves static app and rejects unknown routes", async t => {
  const server = startServer(0);
  await new Promise(resolve => server.once("listening", resolve));
  t.after(() => server.close());
  const host = "http://127.0.0.1:" + server.address().port;
  const index = await fetch(host + "/");
  assert.equal(index.status, 200);
  assert.match(await index.text(), /MOOSA Web/);
  assert.match(index.headers.get("content-security-policy"), /script-src 'self'/);
  assert.equal((await fetch(host + "/app.js")).status, 200);
  assert.equal((await fetch(host + "/api/status")).status, 200);
  assert.equal((await fetch(host + "/api/does-not-exist")).status, 404);
  assert.equal((await fetch(host + "/private.txt")).status, 404);
  assert.equal((await fetch(host + "/api/status", { method: "POST" })).status, 405);
  const detail = await fetch(host + "/api/details?type=movie&id=abc");
  assert.equal(detail.status, 400);
});
