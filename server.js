// Divine Tiers - Render web service.
// Serves the website and forwards /api/* to the DuelsCore plugin on the Minecraft server,
// so the https site never has to talk to an http address directly.
const http = require("http");
const fs = require("fs");
const path = require("path");

const PORT = process.env.PORT || 10000;
// Where the DuelsCore website/API is running (your MC server IP + website.port).
const DUELS_API = (process.env.DUELS_API || "http://play.divinesmp.org:8080").replace(/\/+$/, "");
const CACHE_MS = Number(process.env.CACHE_MS || 5000);

// index.html can sit next to this file or in a "public" folder
const INDEX_PATH = [path.join(__dirname, "index.html"), path.join(__dirname, "public", "index.html")].find(p => fs.existsSync(p));
if (!INDEX_PATH) { console.error("index.html not found - upload it next to server.js"); process.exit(1); }
const INDEX = fs.readFileSync(INDEX_PATH);
const cache = new Map(); // url -> { at, status, body }

async function proxy(req, res) {
  const url = req.url;
  const hit = cache.get(url);
  if (hit && Date.now() - hit.at < CACHE_MS) return send(res, hit.status, hit.body, "application/json; charset=utf-8");
  try {
    const r = await fetch(DUELS_API + url, { signal: AbortSignal.timeout(6000) });
    const body = Buffer.from(await r.arrayBuffer());
    if (r.status === 200 || r.status === 404) cache.set(url, { at: Date.now(), status: r.status, body });
    if (cache.size > 2000) cache.clear();
    send(res, r.status, body, "application/json; charset=utf-8");
  } catch (e) {
    // Minecraft server offline / port closed: serve the last good copy if we have one.
    if (hit) return send(res, hit.status, hit.body, "application/json; charset=utf-8");
    send(res, 502, JSON.stringify({ error: "The DivineSMP server is offline right now." }), "application/json; charset=utf-8");
  }
}

function send(res, status, body, type) {
  res.writeHead(status, {
    "Content-Type": type,
    "Cache-Control": type.startsWith("text/html") ? "no-cache" : "public, max-age=5",
    "Access-Control-Allow-Origin": "*",
  });
  res.end(body);
}

http.createServer((req, res) => {
  if (req.method !== "GET" && req.method !== "HEAD") return send(res, 405, "Method not allowed", "text/plain");
  if (req.url.startsWith("/api/")) return proxy(req, res);
  if (req.url === "/healthz") return send(res, 200, "ok", "text/plain");
  // every other path shows the site (it uses #links, so there's only one page)
  send(res, 200, INDEX, "text/html; charset=utf-8");
}).listen(PORT, () => console.log(`Divine Tiers on :${PORT} -> ${DUELS_API}`));
