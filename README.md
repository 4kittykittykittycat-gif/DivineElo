# Divine Tiers - website

Live tiers + ELO leaderboards for DivineSMP Duels, powered by the DuelsCore plugin.

## Folders
| Folder | What's in it |
|---|---|
| `website/index.html` | The whole website in ONE file (HTML + CSS + JS + icons). This is what you host. |
| `source/template.html` | Same page without the icons baked in - edit this, then run `source/build_index.py`. |
| `source/build_index.py` | Rebuilds `website/index.html` from the template + `icons/`. |
| `icons/` | The 16x16 gamemode icons (same as the resource pack). |
| `plugin-api/WebServer.java` | The plugin code that serves the site + API (already inside DuelsCore 2.2.1). |

## Option 1 - let the plugin host it (easiest)
DuelsCore already serves this page. Open TCP port **8080** on your server host and go to
`http://play.divinesmp.org:8080/`. Config is in `plugins/DuelsCore/config.yml` under `website:`.
To use your own edited copy: put it at `plugins/DuelsCore/web/index.html` and set `website.custom-html: true`.

## Option 2 - host it on your own site (Vercel, Netlify, GitHub Pages, any web host)
1. Open `website/index.html` and near the top of the `<script>` set:
   `const API_BASE = "http://play.divinesmp.org:8080/";`
   (the plugin's address - it must end with `/`).
2. Upload `index.html`. Done.
   Note: if your site is on **https**, browsers block calls to an **http** API. Put the plugin behind
   Cloudflare / a reverse proxy with https (e.g. `https://api.divinesmp.org/` -> `localhost:8080`) and use that.

If the API can't be reached, the page shows sample players with a "PREVIEW" banner instead of breaking.

## API (JSON, CORS enabled)
| Endpoint | Returns |
|---|---|
| `GET /api/overview` | server name/ip, tier ladder, gamemodes, totals, top 100 overall |
| `GET /api/leaderboard?kit=overall` | top 100 by overall ELO (`kit=<kit id>` for one gamemode) |
| `GET /api/player/<name>` | full profile: overall ELO + rank, best tier, every gamemode's stats |
| `GET /api/search?q=<text>` | up to 8 matching players |

Overall ELO = average ELO across every gamemode the player is placed in. Only /queue matches count.
Data refreshes from the game every `website.refresh-seconds` (default 10).

## Links inside the site
- `#top` - overall top 10
- `#k-<kit id>` - a gamemode's top 10, e.g. `#k-sword`
- `#p-<name>` - a player's profile, e.g. `#p-maz_35`
