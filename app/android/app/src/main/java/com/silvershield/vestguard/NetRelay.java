package com.silvershield.vestguard;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Internet relay for the family / health-centre messages (the same CS / CG lines that already travel on
 * the local Wi-Fi). Uses a public ntfy server: one topic per family, named from a hash of the family code.
 *
 *  - publish: every CS line (falls, responses, ...) at once; presence (CG) at most every 90 s per family,
 *    to stay inside the public server's request limits
 *  - subscribe: one long-lived streaming GET for all topics this phone cares about (its own family, or for
 *    the health desk every enrolled family); lines arrive in VestService.onNetLine() and are handled exactly
 *    like lines from the Wi-Fi (same de-duplication, same "ignore my own echo")
 *
 * Prototype only: the topic name is the secret. A deployment would use its own server and real keys.
 */
final class NetRelay {
    private static final String TAG = "VestGuardNet";
    static final String BASE = "https://ntfy.sh/";
    private static final long PRESENCE_MS = 90_000;

    private final VestService svc;
    private final ExecutorService out = Executors.newSingleThreadExecutor();
    private final Map<String, Long> lastPresence = new ConcurrentHashMap<>();
    private volatile boolean running = false;
    private volatile HttpURLConnection conn;
    private volatile String subscribed = "";
    volatile long lastRx = 0, lastOk = 0;

    NetRelay(VestService svc) { this.svc = svc; }

    static String topic(String family) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(("vestguard-v1:" + family.trim().toUpperCase()).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("vg");
            for (int i = 0; i < 13; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return "vg" + family.trim().toUpperCase();
        }
    }

    /** a CS / CG line that just went out on the Wi-Fi: send it over the internet too */
    void publish(String line) {
        if (!running || line == null) return;
        String[] p = line.split(",", 4);
        if (p.length < 4 || p[1].isEmpty()) return;
        if (p[0].equals("CG")) {
            if (svc.isPhc()) return;                                   // the desk does not announce itself to every household
            long now = System.currentTimeMillis();
            Long last = lastPresence.get(p[1]);
            if (last != null && now - last < PRESENCE_MS) return;
            lastPresence.put(p[1], now);
        } else if (!p[0].equals("CS")) return;
        final String t = topic(p[1]);
        out.execute(() -> post(t, line));
    }

    private void post(String t, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(BASE + t).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) { os.write(b); }
            int code = c.getResponseCode();
            if (code >= 300) Log.w(TAG, "publish " + code);
        } catch (Exception e) {
            Log.w(TAG, "publish", e);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    synchronized void start() {
        if (running) return;
        running = true;
        new Thread(this::loop, "net-rx").start();
    }

    synchronized void stop() {
        running = false;
        HttpURLConnection c = conn;
        if (c != null) try { c.disconnect(); } catch (Exception ignored) { }
    }

    boolean connected() { return running && System.currentTimeMillis() - lastOk < 120_000; }

    /** called every second: if the set of families changed, drop the stream so the loop reconnects with the new set */
    void check() {
        if (!running) return;
        String want = topicsFor(svc.netFamilies());
        if (!want.equals(subscribed)) {
            HttpURLConnection c = conn;
            if (c != null) try { c.disconnect(); } catch (Exception ignored) { }
        }
    }

    private static String topicsFor(Set<String> fams) {
        TreeSet<String> ts = new TreeSet<>();
        for (String f : fams) if (f != null && !f.trim().isEmpty()) ts.add(topic(f));
        return String.join(",", ts);
    }

    private void loop() {
        long backoff = 3000;
        while (running) {
            String topics = topicsFor(svc.netFamilies());
            if (topics.isEmpty()) { sleep(3000); continue; }
            // resume from the last message seen (by time: works across several topics); duplicates are dropped
            // by the usual de-duplication. First connection, or after a long gap: only the last 2 minutes.
            long lt = VestService.prefs(svc).getLong("net_last_time", 0), nowS = System.currentTimeMillis() / 1000;
            String since = lt > 0 && nowS - lt < 600 ? String.valueOf(lt) : "120s";
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(BASE + topics + "/json?since=" + since).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(100_000);                                  // the server sends a keepalive every 45 s
                conn = c;
                subscribed = topics;
                int code = c.getResponseCode();
                if (code != 200) throw new java.io.IOException("HTTP " + code);
                lastOk = System.currentTimeMillis();
                backoff = 3000;
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                String l;
                while (running && (l = r.readLine()) != null) {
                    lastOk = System.currentTimeMillis();
                    if (l.isEmpty()) continue;
                    JSONObject m;
                    try { m = new JSONObject(l); } catch (Exception e) { continue; }
                    if (!"message".equals(m.optString("event"))) continue;
                    long mt = m.optLong("time", 0);
                    if (mt > 0) VestService.prefs(svc).edit().putLong("net_last_time", mt).apply();
                    String line = m.optString("message", "");
                    if (line.startsWith("CS,") || line.startsWith("CG,")) {
                        lastRx = System.currentTimeMillis();
                        svc.onNetLine(line.trim());
                    }
                }
            } catch (Exception e) {
                if (running) Log.w(TAG, "stream: " + e.getMessage());
            } finally {
                conn = null;
                if (c != null) try { c.disconnect(); } catch (Exception ignored) { }
            }
            if (!running) break;
            // a changed topic list reconnects at once; a failure waits a little longer each time (max 60 s)
            if (topicsFor(svc.netFamilies()).equals(topics)) { sleep(backoff); backoff = Math.min(60_000, backoff * 2); }
        }
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }
}
