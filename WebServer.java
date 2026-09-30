package me.mxz.duelscore.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.mxz.duelscore.DuelsCore;
import me.mxz.duelscore.kit.Kit;
import me.mxz.duelscore.rank.Icons;
import me.mxz.duelscore.rank.KitStats;
import me.mxz.duelscore.rank.PlayerStats;
import me.mxz.duelscore.rank.Tiers;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Tiers website + JSON API, served straight from the plugin.
 *   /                      the website (plugins/DuelsCore/web/index.html - edit freely)
 *   /api/overview          kits, tier ladder, totals, top 100 overall
 *   /api/leaderboard?kit=  top 100 for "overall" or a kit id
 *   /api/player/<name>     full profile
 *   /api/search?q=         name suggestions
 * Stats are copied into an immutable snapshot on the main thread every few seconds, so the web
 * threads never touch live game data.
 */
public final class WebServer {

    private record Snapshot(String overview, Map<String, String> boards, Map<String, String> players,
                            List<String[]> names) {}

    private final DuelsCore plugin;
    private final Gson gson = new Gson();
    private HttpServer server;
    private volatile Snapshot snap = new Snapshot("{}", Map.of(), Map.of(), List.of());
    private int task = -1;

    public WebServer(DuelsCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("website.enabled", true)) return;
        // Keep a copy on disk you can edit; it's only used when website.custom-html is true.
        File index = new File(plugin.getDataFolder(), "web/index.html");
        if (!index.exists()) plugin.saveResource("web/index.html", false);

        int port = plugin.getConfig().getInt("website.port", 8080);
        long every = Math.max(2, plugin.getConfig().getLong("website.refresh-seconds", 10)) * 20L;
        rebuild();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::rebuild, every, every).getTaskId();
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.setExecutor(Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "DuelsCore-Web");
                t.setDaemon(true);
                return t;
            }));
            server.createContext("/", this::handle);
            server.start();
            plugin.getLogger().info("Tiers website running on port " + port + " (http://<your-ip>:" + port + "/)");
        } catch (IOException e) {
            plugin.getLogger().warning("Could not start the tiers website on port " + port + ": " + e.getMessage());
        }
    }

    public void stop() {
        if (task != -1) Bukkit.getScheduler().cancelTask(task);
        if (server != null) server.stop(0);
        server = null;
    }

    // ------------------------------------------------------------------ snapshot (main thread)

    /** Overall ELO = average ELO of every gamemode the player is placed in. */
    public static int overallElo(PlayerStats ps) {
        int sum = 0, n = 0;
        for (KitStats k : ps.kits.values()) {
            if (!k.placed()) continue;
            sum += k.elo;
            n++;
        }
        return n == 0 ? -1 : Math.round(sum / (float) n);
    }

    private void rebuild() {
        List<Kit> kits = new ArrayList<>(plugin.kits().all());
        List<PlayerStats> all = new ArrayList<>(plugin.stats().all());

        // overall board: placed somewhere, by average elo
        List<PlayerStats> overall = new ArrayList<>();
        for (PlayerStats ps : all) if (overallElo(ps) >= 0) overall.add(ps);
        overall.sort(Comparator.<PlayerStats>comparingInt(WebServer::overallElo).reversed()
                .thenComparing(p -> p.name.toLowerCase(Locale.ROOT)));
        Map<java.util.UUID, Integer> overallPos = new HashMap<>();
        for (int i = 0; i < overall.size(); i++) overallPos.put(overall.get(i).uuid, i + 1);

        Map<String, List<PlayerStats>> kitBoards = new HashMap<>();
        Map<String, Map<java.util.UUID, Integer>> kitPos = new HashMap<>();
        for (Kit k : kits) {
            List<PlayerStats> lb = plugin.stats().leaderboard(k.id());
            kitBoards.put(k.id(), lb);
            Map<java.util.UUID, Integer> pos = new HashMap<>();
            for (int i = 0; i < lb.size(); i++) pos.put(lb.get(i).uuid, i + 1);
            kitPos.put(k.id(), pos);
        }

        // boards
        Map<String, String> boards = new HashMap<>();
        boards.put("overall", gson.toJson(board("overall", overall, null, kits)));
        for (Kit k : kits) boards.put(k.id(), gson.toJson(board(k.id(), kitBoards.get(k.id()), k.id(), kits)));

        // players
        Map<String, String> players = new HashMap<>();
        List<String[]> names = new ArrayList<>();
        int matches = 0;
        for (PlayerStats ps : all) {
            if (ps.name == null) continue;
            matches += ps.totalPlayed();
            JsonObject o = playerRow(ps, overallPos.getOrDefault(ps.uuid, -1), kits);
            JsonArray kk = new JsonArray();
            for (Kit k : kits) {
                KitStats s = ps.peek(k.id());
                JsonObject ko = new JsonObject();
                ko.addProperty("id", k.id());
                ko.addProperty("name", k.name());
                ko.addProperty("icon", Icons.normalize(k.name()));
                if (s == null || s.played == 0) {
                    ko.addProperty("played", 0);
                } else {
                    ko.addProperty("elo", s.elo);
                    ko.addProperty("tier", s.tier);
                    ko.addProperty("peak", s.peak);
                    ko.addProperty("played", s.played);
                    ko.addProperty("wins", s.wins);
                    ko.addProperty("losses", s.losses);
                    ko.addProperty("winrate", s.winRate());
                    ko.addProperty("streak", s.streak);
                    ko.addProperty("bestStreak", s.bestStreak);
                    ko.addProperty("progress", s.progress());
                    ko.addProperty("placements", Math.min(s.played, Tiers.placementMatches()));
                    ko.addProperty("position", kitPos.get(k.id()).getOrDefault(ps.uuid, -1));
                }
                kk.add(ko);
            }
            o.add("kits", kk);
            o.addProperty("online", Bukkit.getPlayer(ps.uuid) != null);
            players.put(ps.name.toLowerCase(Locale.ROOT), gson.toJson(o));
            names.add(new String[]{ps.name, ps.uuid.toString(), String.valueOf(ps.bestTier()),
                    String.valueOf(overallElo(ps))});
        }
        names.sort(Comparator.comparing(a -> a[0].toLowerCase(Locale.ROOT)));

        // overview
        JsonObject ov = new JsonObject();
        ov.addProperty("server", plugin.getConfig().getString("website.server-name", "DivineSMP"));
        ov.addProperty("ip", plugin.getConfig().getString("website.server-ip", "play.divinesmp.org"));
        ov.addProperty("updated", System.currentTimeMillis());
        ov.addProperty("placementMatches", Tiers.placementMatches());
        JsonArray tiers = new JsonArray();
        for (int t = 0; t < Tiers.NAMES.length; t++) {
            JsonObject to = new JsonObject();
            to.addProperty("name", Tiers.NAMES[t]);
            to.addProperty("color", Tiers.COLORS[t]);
            to.addProperty("elo", Tiers.threshold(t));
            tiers.add(to);
        }
        ov.add("tiers", tiers);
        JsonArray ka = new JsonArray();
        for (Kit k : kits) {
            JsonObject ko = new JsonObject();
            ko.addProperty("id", k.id());
            ko.addProperty("name", k.name());
            ko.addProperty("icon", Icons.normalize(k.name()));
            ko.addProperty("ranked", kitBoards.get(k.id()).size());
            ka.add(ko);
        }
        ov.add("kits", ka);
        JsonObject totals = new JsonObject();
        totals.addProperty("players", all.size());
        totals.addProperty("ranked", overall.size());
        totals.addProperty("matches", matches / 2);
        totals.addProperty("online", Bukkit.getOnlinePlayers().size());
        ov.add("totals", totals);
        ov.add("top", board("overall", overall, null, kits).get("players"));

        snap = new Snapshot(gson.toJson(ov), boards, players, Collections.unmodifiableList(names));
    }

    private JsonObject playerRow(PlayerStats ps, int overallPosition, List<Kit> kits) {
        JsonObject o = new JsonObject();
        o.addProperty("name", ps.name);
        o.addProperty("uuid", ps.uuid.toString());
        o.addProperty("overallElo", overallElo(ps));
        o.addProperty("overallPosition", overallPosition);
        String best = ps.bestKit();
        o.addProperty("bestTier", ps.bestTier());
        if (best != null) {
            Kit bk = plugin.kits().get(best);
            o.addProperty("bestKit", bk != null ? bk.name() : best);
            o.addProperty("bestIcon", Icons.normalize(bk != null ? bk.name() : best));
        }
        o.addProperty("wins", ps.totalWins());
        o.addProperty("played", ps.totalPlayed());
        // compact per-kit tiers for leaderboard rows
        JsonObject mini = new JsonObject();
        for (Kit k : kits) {
            KitStats s = ps.peek(k.id());
            if (s != null && s.placed()) mini.addProperty(Icons.normalize(k.name()), s.tier);
        }
        o.add("tiers", mini);
        return o;
    }

    private JsonObject board(String id, List<PlayerStats> list, String kitId, List<Kit> kits) {
        JsonObject b = new JsonObject();
        b.addProperty("kit", id);
        JsonArray arr = new JsonArray();
        for (int i = 0; i < list.size() && i < 100; i++) {
            PlayerStats ps = list.get(i);
            JsonObject row = playerRow(ps, i + 1, kits);
            row.addProperty("rank", i + 1);
            if (kitId != null) {
                KitStats s = ps.peek(kitId);
                row.addProperty("elo", s.elo);
                row.addProperty("tier", s.tier);
                row.addProperty("kitWins", s.wins);
                row.addProperty("kitLosses", s.losses);
                row.addProperty("winrate", s.winRate());
            } else {
                row.addProperty("elo", overallElo(ps));
            }
            arr.add(row);
        }
        b.add("players", arr);
        return b;
    }

    // ------------------------------------------------------------------ http (web threads)

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            Snapshot s = snap;
            if (path.equals("/") || path.equals("/index.html")) {
                File f = new File(plugin.getDataFolder(), "web/index.html");
                byte[] body;
                if (plugin.getConfig().getBoolean("website.custom-html", false) && f.exists()) body = Files.readAllBytes(f.toPath());
                else try (InputStream in = plugin.getResource("web/index.html")) {
                    body = in == null ? "Missing index.html".getBytes() : in.readAllBytes();
                }
                send(ex, 200, "text/html; charset=utf-8", body);
            } else if (path.equals("/api/overview")) {
                json(ex, 200, s.overview());
            } else if (path.equals("/api/leaderboard")) {
                String kit = param(ex, "kit");
                String body = s.boards().get(kit == null ? "overall" : kit.toLowerCase(Locale.ROOT));
                json(ex, body == null ? 404 : 200, body == null ? "{\"error\":\"unknown kit\"}" : body);
            } else if (path.startsWith("/api/player/")) {
                String name = URLDecoder.decode(path.substring("/api/player/".length()), StandardCharsets.UTF_8);
                String body = s.players().get(name.toLowerCase(Locale.ROOT));
                json(ex, body == null ? 404 : 200, body == null ? "{\"error\":\"player not found\"}" : body);
            } else if (path.equals("/api/search")) {
                String q = param(ex, "q");
                JsonArray out = new JsonArray();
                if (q != null && !q.isBlank()) {
                    String lq = q.toLowerCase(Locale.ROOT);
                    List<String[]> starts = new ArrayList<>(), contains = new ArrayList<>();
                    for (String[] n : s.names()) {
                        String ln = n[0].toLowerCase(Locale.ROOT);
                        if (ln.startsWith(lq)) starts.add(n);
                        else if (ln.contains(lq)) contains.add(n);
                    }
                    starts.addAll(contains);
                    for (int i = 0; i < starts.size() && i < 8; i++) {
                        JsonObject o = new JsonObject();
                        o.addProperty("name", starts.get(i)[0]);
                        o.addProperty("uuid", starts.get(i)[1]);
                        o.addProperty("bestTier", Integer.parseInt(starts.get(i)[2]));
                        o.addProperty("overallElo", Integer.parseInt(starts.get(i)[3]));
                        out.add(o);
                    }
                }
                json(ex, 200, gson.toJson(out));
            } else {
                send(ex, 404, "text/plain", "Not found".getBytes());
            }
        } catch (Exception e) {
            send(ex, 500, "text/plain", "Error".getBytes());
        } finally {
            ex.close();
        }
    }

    private static String param(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return null;
        for (String part : q.split("&")) {
            int i = part.indexOf('=');
            if (i > 0 && part.substring(0, i).equals(key))
                return URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static void json(HttpExchange ex, int code, String body) throws IOException {
        send(ex, code, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
