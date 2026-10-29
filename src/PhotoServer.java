import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/** Personal photo server: one password, one library, a web UI. No third-party libraries. */
public class PhotoServer {
    private static final long SESSION_MS = 30L * 24 * 3600 * 1000;
    private static final long THUMB_ALLOWANCE = 256 * 1024; // space kept free per upload for its thumbnail

    private final Library lib;
    private final byte[] passwordHash;
    private final Path webDir;
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();
    private final ArrayDeque<Long> failures = new ArrayDeque<>();

    PhotoServer(Library lib, String password, Path webDir) {
        this.lib = lib;
        this.passwordHash = sha256(password.getBytes(StandardCharsets.UTF_8));
        this.webDir = webDir;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path cfgPath = Path.of(args.length > 0 ? args[0] : "config.properties");
        if (!Files.exists(cfgPath)) fail("Cannot find " + cfgPath.toAbsolutePath() + ". Run from the MyPhotos folder.");
        Properties cfg = new Properties();
        try (var r = Files.newBufferedReader(cfgPath, StandardCharsets.UTF_8)) { cfg.load(r); }

        String password = cfg.getProperty("password", "").trim();
        if (password.isEmpty() || password.equals("CHANGE_ME")) {
            fail("Open config.properties and set a password first (it is still CHANGE_ME).");
        }
        int port = Integer.parseInt(cfg.getProperty("port", "8080").trim());
        String bind = cfg.getProperty("bind", "0.0.0.0").trim();
        double quotaGb = Double.parseDouble(cfg.getProperty("quotaGB", "50").trim());
        Path dataDir = Path.of(cfg.getProperty("dataDir", "data").trim());

        Library lib = new Library(dataDir, (long) (quotaGb * 1024L * 1024L * 1024L));
        PhotoServer app = new PhotoServer(lib, password, Path.of("web"));

        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/", app::handle);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();

        System.out.println("MyPhotos is running. Storing photos in: " + dataDir.toAbsolutePath());
        System.out.printf("Storage: %.2f GB used of %.2f GB allocated%n", lib.used() / (1024.0 * 1024 * 1024), quotaGb);
        System.out.println("Open on this computer:  http://localhost:" + port);
        for (String ip : localAddresses()) System.out.println("Open from other devices: http://" + ip + ":" + port);
        System.out.println("(Your Tailscale address starts with 100.) Press Ctrl+C to stop.");
    }

    private static void fail(String msg) {
        System.err.println("ERROR: " + msg);
        System.exit(1);
    }

    private static List<String> localAddresses() {
        List<String> out = new java.util.ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (var a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ---------------------------------------------------------------- routing

    private void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            Headers h = ex.getResponseHeaders();
            h.set("X-Content-Type-Options", "nosniff");
            h.set("Referrer-Policy", "no-referrer");
            h.set("X-Frame-Options", "DENY");

            // CSRF guard on top of SameSite cookies: browsers send Origin on cross-site writes.
            if (!method.equals("GET") && !method.equals("HEAD") && !sameOrigin(ex)) {
                json(ex, 403, "{\"error\":\"origin\"}");
                return;
            }

            if (path.equals("/") && method.equals("GET")) { index(ex); return; }
            if (path.equals("/api/login") && method.equals("POST")) { login(ex); return; }
            if (path.equals("/api/logout") && method.equals("POST")) { logout(ex); return; }
            if (!authed(ex)) { json(ex, 401, "{\"error\":\"auth\"}"); return; }

            if (path.equals("/api/library") && method.equals("GET")) { library(ex); return; }
            if (path.equals("/api/upload") && method.equals("POST")) { upload(ex); return; }
            if (path.startsWith("/api/thumb/") && method.equals("GET")) { file(ex, path.substring(11), true); return; }
            if (path.startsWith("/api/photo/")) {
                String id = path.substring(11);
                if (method.equals("GET")) { file(ex, id, false); return; }
                if (method.equals("DELETE")) { delete(ex, id); return; }
            }
            json(ex, 404, "{\"error\":\"not found\"}");
        } catch (Exception e) {
            e.printStackTrace();
            try { json(ex, 500, "{\"error\":\"server\"}"); } catch (Exception ignored) { }
        } finally {
            ex.close();
        }
    }

    private void index(HttpExchange ex) throws IOException {
        byte[] b = Files.readAllBytes(webDir.resolve("index.html"));
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "text/html; charset=utf-8");
        h.set("Cache-Control", "no-cache");
        h.set("Content-Security-Policy",
                "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; "
                + "script-src 'self' 'unsafe-inline'; frame-ancestors 'none'");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    // ---------------------------------------------------------------- auth

    private void login(HttpExchange ex) throws IOException {
        if (locked()) { json(ex, 429, "{\"error\":\"locked\"}"); return; }
        String body = new String(ex.getRequestBody().readNBytes(4096), StandardCharsets.UTF_8);
        String pw = form(body).getOrDefault("password", "");
        if (!MessageDigest.isEqual(sha256(pw.getBytes(StandardCharsets.UTF_8)), passwordHash)) {
            recordFailure();
            try { Thread.sleep(700); } catch (InterruptedException ignored) { }
            json(ex, 401, "{\"error\":\"password\"}");
            return;
        }
        long now = System.currentTimeMillis();
        sessions.values().removeIf(exp -> exp < now);
        byte[] raw = new byte[32];
        rng.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        sessions.put(token, now + SESSION_MS);
        ex.getResponseHeaders().add("Set-Cookie", "session=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age="
                + (SESSION_MS / 1000) + secureFlag(ex));
        json(ex, 200, "{\"ok\":true}");
    }

    private void logout(HttpExchange ex) throws IOException {
        String token = cookie(ex, "session");
        if (token != null) sessions.remove(token);
        ex.getResponseHeaders().add("Set-Cookie", "session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0" + secureFlag(ex));
        json(ex, 200, "{\"ok\":true}");
    }

    private boolean authed(HttpExchange ex) {
        String token = cookie(ex, "session");
        if (token == null) return false;
        Long exp = sessions.get(token);
        if (exp == null) return false;
        if (exp < System.currentTimeMillis()) { sessions.remove(token); return false; }
        return true;
    }

    // 10 wrong passwords within 10 minutes locks logins for the rest of that window.
    private synchronized boolean locked() {
        long now = System.currentTimeMillis();
        while (!failures.isEmpty() && now - failures.peekFirst() > 10 * 60_000L) failures.pollFirst();
        return failures.size() >= 10;
    }

    private synchronized void recordFailure() { failures.addLast(System.currentTimeMillis()); }

    private static String secureFlag(HttpExchange ex) {
        String proto = ex.getRequestHeaders().getFirst("X-Forwarded-Proto");
        return "https".equalsIgnoreCase(proto) ? "; Secure" : "";
    }

    private static boolean sameOrigin(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return true;
        String host = ex.getRequestHeaders().getFirst("Host");
        return host != null && origin.endsWith("//" + host);
    }

    private static String cookie(HttpExchange ex, String name) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String hv : headers) {
            for (String part : hv.split(";")) {
                String p = part.trim();
                if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- API

    private void library(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder("{\"used\":").append(lib.used())
                .append(",\"limit\":").append(lib.limit()).append(",\"photos\":[");
        boolean first = true;
        for (Library.Item it : lib.list()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":\"").append(it.id()).append("\",\"name\":").append(quote(it.name()))
              .append(",\"taken\":").append(it.taken()).append(",\"size\":").append(it.size()).append('}');
        }
        sb.append("]}");
        json(ex, 200, sb.toString());
    }

    private void upload(HttpExchange ex) throws IOException {
        Map<String, String> q = form(ex.getRequestURI().getRawQuery());
        String name = cleanName(q.getOrDefault("name", "photo"));
        String ext = extOf(name);
        if (!Library.EXTS.contains(ext)) { json(ex, 415, "{\"error\":\"type\"}"); return; }
        long taken = 0;
        try { taken = Long.parseLong(q.getOrDefault("taken", "0")); } catch (NumberFormatException ignored) { }

        long len;
        try { len = Long.parseLong(ex.getRequestHeaders().getFirst("Content-Length")); }
        catch (Exception e) { len = -1; }
        if (len <= 0) { json(ex, 411, "{\"error\":\"length\"}"); return; }

        long reservation = len + THUMB_ALLOWANCE;
        if (!lib.reserve(reservation)) { json(ex, 507, "{\"error\":\"quota\"}"); return; }

        Path tmp = Files.createTempFile(lib.tmpDir(), "up", ".part");
        try {
            MessageDigest md = newSha256();
            long total = 0;
            try (InputStream in = ex.getRequestBody(); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            if (total != len) { json(ex, 400, "{\"error\":\"incomplete\"}"); return; }
            String id = hex(md.digest()).substring(0, 32);
            boolean duplicate = lib.contains(id);
            Library.Item it = lib.add(tmp, id, name, ext, taken, total);
            json(ex, 200, "{\"id\":\"" + it.id() + "\",\"duplicate\":" + duplicate + "}");
        } finally {
            lib.release(reservation);
            Files.deleteIfExists(tmp);
        }
    }

    private void file(HttpExchange ex, String id, boolean thumb) throws IOException {
        Library.Item it = lib.get(id); // only ids from our own index ever become file paths
        if (it == null) { json(ex, 404, "{\"error\":\"not found\"}"); return; }
        Path p;
        String type;
        if (thumb && it.thumbSize() > 0) { p = lib.thumbPath(it); type = "image/jpeg"; }
        else { p = lib.originalPath(it); type = Library.mime(it.ext()); }
        if (!Files.exists(p)) { json(ex, 404, "{\"error\":\"missing\"}"); return; }

        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Cache-Control", "private, max-age=31536000, immutable"); // id = content hash, so it never changes
        String rawQuery = ex.getRequestURI().getRawQuery();
        if (!thumb && rawQuery != null && rawQuery.contains("download")) {
            h.set("Content-Disposition", "attachment; filename*=UTF-8''"
                    + URLEncoder.encode(it.name(), StandardCharsets.UTF_8).replace("+", "%20"));
        }
        ex.sendResponseHeaders(200, Files.size(p));
        try (OutputStream o = ex.getResponseBody()) { Files.copy(p, o); }
    }

    private void delete(HttpExchange ex, String id) throws IOException {
        boolean ok = lib.delete(id);
        json(ex, ok ? 200 : 404, ok ? "{\"ok\":true}" : "{\"error\":\"not found\"}");
    }

    // ---------------------------------------------------------------- helpers

    private static void json(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    private static Map<String, String> form(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String kv : s.split("&")) {
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i);
            String v = i < 0 ? "" : kv.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String cleanName(String n) {
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        n = n.replaceAll("[\\p{Cntrl}\\t]", " ").trim();
        if (n.length() > 200) n = n.substring(n.length() - 200);
        return n.isEmpty() ? "photo" : n;
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static MessageDigest newSha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static byte[] sha256(byte[] b) { return newSha256().digest(b); }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
