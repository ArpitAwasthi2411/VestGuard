package com.silvershield.vestguard;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the phone connected to the vest while the app is closed or the screen is off.
 *
 *  - listens on UDP 4210 for the vest (the vest sends to the phone because the phone is its hotspot gateway)
 *  - sends "PH" keep-alives so the vest knows this phone (and stops sending raw data to it)
 *  - on a fall: acknowledges it to the vest, rings a loud alarm, shows a full-screen notification,
 *    optionally texts the emergency contacts with the phone's location
 *  - stores fall/vest events until the app opens and collects them (drain)
 */
public class VestService extends Service {
    private static final String TAG = "VestService";
    static final String CH_ALERT = "vg_falls";
    static final String CH_STATUS = "vg_status";
    static final String CH_INFO = "vg_info";
    static final int NID_STATUS = 1, NID_ALERT = 2, NID_INFO = 3;

    static final String ACTION_START = "com.silvershield.vestguard.START";
    static final String ACTION_STOP = "com.silvershield.vestguard.STOP";
    static final String ACTION_ACK = "com.silvershield.vestguard.ACK";
    static final String ACTION_REFRESH = "com.silvershield.vestguard.REFRESH";

    static final int DATA_PORT = 4210, CMD_PORT = 4211;
    static final long ONLINE_MS = 5000, LOST_MS = 20000, ESCALATE_MS = 60000;

    interface Listener { void onLine(String line, long ts); }
    interface DataListener { void onData(String lines); }

    static volatile VestService instance;
    static volatile Listener listener;
    static volatile DataListener dataListener;     // Research mode: raw D lines, batched per packet
    static volatile boolean research = false;

    private DatagramSocket sock;
    private Thread rxThread;
    private ScheduledExecutorService exec;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.MulticastLock mcLock;

    volatile InetAddress vestAddr;
    volatile long lastRx = 0, onlineSince = 0;
    private boolean wasOnline = false, lostShown = false;
    private String lastAct = "-";
    private String statusShown = "";
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();

    // the alert that is currently ringing
    private String alertId = null;
    private int alertSev = 0;
    private long alertTs = 0;
    private boolean alertDemo = false, alertAcked = false, alertEscalated = false;

    // ------------------------------------------------------------------ lifecycle
    static void start(Context ctx) {
        Intent i = new Intent(ctx, VestService.class).setAction(ACTION_START);
        ContextCompat.startForegroundService(ctx, i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null && intent.getAction() != null ? intent.getAction() : ACTION_START;
        if (!foregroundStarted || ACTION_START.equals(action)) {
            if (!startInForeground()) {
                showPaused();
                stopSelf();
                return START_NOT_STICKY;
            }
            foregroundStarted = true;
        }
        switch (action) {
            case ACTION_STOP:
                prefs(this).edit().putBoolean("enabled", false).apply();
                stopSelf();
                return START_NOT_STICKY;
            case ACTION_ACK:
                acknowledge(intent.getStringExtra("id"), true);
                break;
            case ACTION_REFRESH:
                updateStatus(true);
                break;
            default:
                prefs(this).edit().putBoolean("enabled", true).apply();
                break;
        }
        openSocket();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm != null && locListener != null) lm.removeUpdates(locListener);
        } catch (Exception ignored) { }
        if (exec != null) exec.shutdownNow();
        if (sock != null) sock.close();
        sock = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (mcLock != null && mcLock.isHeld()) mcLock.release();
        Alarm.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** @return false if Android refused to run us in the foreground (then we stop and say so) */
    private boolean startInForeground() {
        Notification n = statusNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            // with the "location" type we may read the location while the app is closed (fall location in alerts / SMS)
            if (hasLocationPermission()) {
                try {
                    startForeground(NID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
                    startLocationUpdates();
                    return true;
                } catch (Exception e) {
                    Log.w(TAG, "foreground with location refused, trying without", e);
                }
            }
            try {
                startForeground(NID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "startForeground failed", e);
                return false;
            }
        }
        try {
            startForeground(NID_STATUS, n);
            startLocationUpdates();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
            return false;
        }
    }

    private void showPaused() {
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 3, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        notifySafe(NID_INFO, new NotificationCompat.Builder(this, CH_INFO)
                .setSmallIcon(R.drawable.ic_stat_vest)
                .setColor(Color.rgb(0xD2, 0x26, 0x3B))
                .setContentTitle(Strings.t(lang(), "paused_title"))
                .setContentText(Strings.t(lang(), "paused_body"))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build());
    }

    // ------------------------------------------------------------------ location (kept fresh while running)
    private volatile Location freshLoc;
    private boolean foregroundStarted = false;
    private android.location.LocationListener locListener;

    private void startLocationUpdates() {
        if (locListener != null || !hasLocationPermission()) return;
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        locListener = new android.location.LocationListener() {      // all four methods: older Android calls them
            @Override public void onLocationChanged(Location loc) { freshLoc = loc; }
            @Override public void onStatusChanged(String p, int st, android.os.Bundle b) { }
            @Override public void onProviderEnabled(String p) { }
            @Override public void onProviderDisabled(String p) { }
        };
        String[] providers = Build.VERSION.SDK_INT >= 31
                ? new String[]{"fused", LocationManager.NETWORK_PROVIDER}
                : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER};
        for (String prov : providers) {
            try {
                if (lm.getAllProviders().contains(prov)) {
                    lm.requestLocationUpdates(prov, 120000L, 25f, locListener, android.os.Looper.getMainLooper());
                    break;
                }
            } catch (Exception e) {
                Log.w(TAG, "location updates " + prov, e);
            }
        }
    }

    // ------------------------------------------------------------------ networking
    private synchronized void openSocket() {
        if (sock != null && !sock.isClosed()) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VestGuard:link");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && mcLock == null) {
                mcLock = wm.createMulticastLock("VestGuard:udp");
                mcLock.setReferenceCounted(false);
                mcLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "locks", e);
        }
        try {
            DatagramSocket s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.setBroadcast(true);
            s.bind(new InetSocketAddress(DATA_PORT));
            sock = s;
        } catch (Exception e) {
            Log.e(TAG, "bind failed", e);
            return;
        }
        rxThread = new Thread(this::receiveLoop, "vest-rx");
        rxThread.start();
        exec = Executors.newSingleThreadScheduledExecutor();
        exec.scheduleWithFixedDelay(this::everySecond, 1, 1, TimeUnit.SECONDS);
    }

    private void receiveLoop() {
        byte[] buf = new byte[2048];
        DatagramSocket s = sock;
        while (s != null && !s.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                String text = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                InetAddress from = p.getAddress();
                StringBuilder raw = null;
                for (String line : text.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    if (line.startsWith("D,")) {
                        if (research && dataListener != null) {
                            if (raw == null) raw = new StringBuilder();
                            raw.append(line).append('\n');
                        }
                        lastRx = System.currentTimeMillis();
                        continue;
                    }
                    handleLine(line, from);
                }
                DataListener dl = dataListener;
                if (raw != null && dl != null) dl.onData(raw.toString());
            } catch (Exception e) {
                if (s.isClosed()) break;
                Log.w(TAG, "rx", e);
            }
        }
    }

    void sendToVest(String cmd) {
        InetAddress to = vestAddr;
        DatagramSocket s = sock;
        if (to == null || s == null || s.isClosed()) return;
        try {
            byte[] b = (cmd + "\n").getBytes(StandardCharsets.UTF_8);
            s.send(new DatagramPacket(b, b.length, to, CMD_PORT));
        } catch (Exception e) {
            Log.w(TAG, "send " + cmd, e);
        }
    }

    /** send from any thread (network is not allowed on the main thread) */
    void sendAsync(String cmd) {
        ScheduledExecutorService e = exec;
        if (e != null && !e.isShutdown()) e.execute(() -> sendToVest(cmd));
    }

    private void handleLine(String line, InetAddress from) {
        long now = System.currentTimeMillis();
        boolean first = vestAddr == null || !from.equals(vestAddr);
        vestAddr = from;
        lastRx = now;
        if (first) sendToVest(research ? "P" : "PH");

        if (line.startsWith("F,") || line.startsWith("E,")) {
            String[] p = line.split(",");
            if (p.length < 3) return;
            String id = p[1];
            sendToVest("K" + id);                 // acknowledge every copy so the vest stops repeating
            synchronized (seen) {
                if (seen.contains(id)) return;
                seen.add(id);
                while (seen.size() > 300) seen.remove(seen.iterator().next());
            }
            JSONObject ev = new JSONObject();
            try {
                ev.put("line", line);
                ev.put("ts", now);
                if (line.startsWith("F,")) {
                    Location loc = lastLocation();
                    if (loc != null) {
                        ev.put("lat", loc.getLatitude());
                        ev.put("lon", loc.getLongitude());
                        ev.put("acc", loc.getAccuracy());
                        ev.put("loc_age_s", (now - loc.getTime()) / 1000);
                    }
                    int sev = 2;
                    try { sev = Integer.parseInt(p[2]); } catch (Exception ignored) { }
                    raiseAlarm(id, sev, false, now, loc);
                }
            } catch (Exception e) {
                Log.w(TAG, "event", e);
            }
            if (!line.contains(",ACT,")) storePending(ev);
        } else if (line.startsWith("S,")) {
            String[] p = line.split(",");
            if (p.length > 3) lastAct = p[3];
        }
        Listener l = listener;
        if (l != null) l.onLine(line, now);
    }

    private void everySecond() {
        try {
            tick();
        } catch (Throwable t) {
            Log.w(TAG, "tick", t);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        sendToVest(research ? "P" : "PH");
        boolean online = lastRx > 0 && now - lastRx < ONLINE_MS;
        if (online && !wasOnline) {
            onlineSince = now;
            if (lostShown) {
                lostShown = false;
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(NID_INFO);
            }
        }
        if (!online && lastRx > 0 && now - lastRx > LOST_MS && !lostShown) {
            lostShown = true;
            info(Strings.t(lang(), "lost_title"), Strings.t(lang(), "lost_body", "name", wearerFirst()));
        }
        wasOnline = online;
        updateStatus(false);

        synchronized (this) {
            if (alertId != null && !alertAcked && !alertEscalated && now - alertTs > ESCALATE_MS) {
                alertEscalated = true;
                postAlert();
                Alarm.start(this, 0);
            }
        }
    }

    // ------------------------------------------------------------------ alarm
    synchronized void raiseAlarm(String id, int sev, boolean demo, long ts, Location loc) {
        alertId = id;
        alertSev = sev;
        alertTs = ts;
        alertDemo = demo;
        alertAcked = false;
        alertEscalated = false;
        postAlert();
        Alarm.start(this, 0);
        if (!demo) sendSms(sev, ts, loc);
    }

    synchronized void acknowledge(String id, boolean fromNotification) {
        Alarm.stop();
        if (id != null && id.equals(alertId)) {
            alertAcked = true;
            postAlert();
        }
        if (fromNotification && id != null) {
            JSONObject ev = new JSONObject();
            try {
                ev.put("line", "ACK," + id);
                ev.put("ts", System.currentTimeMillis());
            } catch (Exception ignored) { }
            storePending(ev);
            Listener l = listener;
            if (l != null) l.onLine("ACK," + id, System.currentTimeMillis());
        }
    }

    synchronized void clearAlert(String id) {
        if (id == null || id.equals(alertId)) {
            Alarm.stop();
            alertId = null;
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NID_ALERT);
        }
    }

    private void postAlert() {
        if (alertId == null) return;
        String lang = lang();
        String sev = Strings.t(lang, "sev_" + alertSev);
        String title = Strings.t(lang, alertDemo ? "fall_title_demo" : "fall_title", "name", wearerFirst());
        String body = alertAcked ? Strings.t(lang, "fall_ack")
                : alertEscalated ? Strings.t(lang, "fall_esc", "sev", sev)
                : Strings.t(lang, "fall_body", "sev", sev);

        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("vg_alert", alertId);
        PendingIntent openPi = PendingIntent.getActivity(this, 10, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent ack = new Intent(this, VestService.class).setAction(ACTION_ACK).putExtra("id", alertId);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent ackPi = Build.VERSION.SDK_INT >= 26
                ? PendingIntent.getForegroundService(this, 11, ack, fl)
                : PendingIntent.getService(this, 11, ack, fl);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_vest)
                .setColor(Color.rgb(0xD2, 0x26, 0x3B))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setWhen(alertTs)
                .setShowWhen(true)
                .setOngoing(!alertAcked)
                .setAutoCancel(false)
                .setOnlyAlertOnce(alertAcked)
                .setContentIntent(openPi);
        if (!alertAcked) {
            b.setFullScreenIntent(openPi, true);
            b.addAction(0, Strings.t(lang, "responding"), ackPi);
        }
        b.addAction(0, Strings.t(lang, "open"), openPi);
        notifySafe(NID_ALERT, b.build());
    }

    // ------------------------------------------------------------------ SMS + location
    private void sendSms(int sev, long ts, Location loc) {
        try {
            JSONObject cfg = config(this);
            if (!cfg.optBoolean("autoSms", false)) return;
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return;
            JSONArray contacts = cfg.optJSONArray("contacts");
            if (contacts == null || contacts.length() == 0) return;
            String lang = lang();
            double lat, lon;
            if (loc != null) { lat = loc.getLatitude(); lon = loc.getLongitude(); }
            else { lat = cfg.optDouble("lat", 0); lon = cfg.optDouble("lon", 0); }
            String map = (lat == 0 && lon == 0) ? cfg.optString("homeLabel", "") : String.format(Locale.US, "https://maps.google.com/?q=%.5f,%.5f", lat, lon);
            if (loc != null) {
                long ageMin = (System.currentTimeMillis() - loc.getTime()) / 60000;
                if (ageMin >= 10) map = map + " " + Strings.t(lang(), "loc_old", "n", String.valueOf(ageMin));
            } else if (lat != 0 || lon != 0) {
                map = map + " " + Strings.t(lang(), "loc_home");
            }
            String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ts));
            String msg = Strings.t(lang, "sms", "name", cfg.optString("wearer", Strings.t(lang, "wearer")),
                    "time", time, "sev", Strings.t(lang, "sev_" + sev), "map", map);
            SmsManager sms = smsManager();
            if (sms == null) return;
            ArrayList<String> parts = sms.divideMessage(msg);
            for (int i = 0; i < contacts.length(); i++) {
                String phone = contacts.optJSONObject(i) != null ? contacts.optJSONObject(i).optString("phone", "") : "";
                phone = phone.replaceAll("[^0-9+]", "");
                if (phone.length() < 6) continue;
                try {
                    sms.sendMultipartTextMessage(phone, null, parts, null, null);
                } catch (Exception e) {
                    Log.w(TAG, "sms to " + phone, e);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "sms", e);
        }
    }

    /** Dual-SIM phones set to "ask every time" have no default SIM; then use the first active one. */
    private SmsManager smsManager() {
        int sub = SmsManager.getDefaultSmsSubscriptionId();
        if (sub == android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                android.telephony.SubscriptionManager sm = (android.telephony.SubscriptionManager) getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                java.util.List<android.telephony.SubscriptionInfo> subs = sm != null ? sm.getActiveSubscriptionInfoList() : null;
                if (subs != null && !subs.isEmpty()) sub = subs.get(0).getSubscriptionId();
            } catch (SecurityException e) {
                Log.w(TAG, "subscriptions", e);
            }
        }
        if (sub != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            if (Build.VERSION.SDK_INT >= 31) {
                SmsManager base = getSystemService(SmsManager.class);
                if (base != null) return base.createForSubscriptionId(sub);
            }
            return SmsManager.getSmsManagerForSubscriptionId(sub);
        }
        return Build.VERSION.SDK_INT >= 31 ? getSystemService(SmsManager.class) : SmsManager.getDefault();
    }

    Location lastLocation() {
        boolean fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) return null;
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = freshLoc;
        for (String prov : lm.getProviders(true)) {
            try {
                Location l = lm.getLastKnownLocation(prov);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException ignored) { }
        }
        return best;
    }

    // ------------------------------------------------------------------ notifications
    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        String lang = lang();
        NotificationChannel alert = new NotificationChannel(CH_ALERT, Strings.t(lang, "ch_alert"), NotificationManager.IMPORTANCE_HIGH);
        alert.setSound(null, null);            // the app plays its own loud alarm
        alert.enableVibration(false);          // and its own vibration
        alert.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        alert.setBypassDnd(true);
        nm.createNotificationChannel(alert);
        NotificationChannel status = new NotificationChannel(CH_STATUS, Strings.t(lang, "ch_status"), NotificationManager.IMPORTANCE_LOW);
        status.setShowBadge(false);
        nm.createNotificationChannel(status);
        NotificationChannel info = new NotificationChannel(CH_INFO, Strings.t(lang, "ch_info"), NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(info);
    }

    private Notification statusNotification() {
        String lang = lang();
        boolean online = lastRx > 0 && System.currentTimeMillis() - lastRx < ONLINE_MS;
        String text = online ? Strings.t(lang, "status_on", "act", Strings.t(lang, "act_" + lastAct))
                : Strings.t(lang, "status_off");
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        statusShown = text;
        return new NotificationCompat.Builder(this, CH_STATUS)
                .setSmallIcon(R.drawable.ic_stat_vest)
                .setColor(Color.rgb(0x1D, 0x7A, 0x5F))
                .setContentTitle(Strings.t(lang, "status_title", "name", wearerFirst()))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .build();
    }

    private void updateStatus(boolean force) {
        String lang = lang();
        boolean online = lastRx > 0 && System.currentTimeMillis() - lastRx < ONLINE_MS;
        String text = online ? Strings.t(lang, "status_on", "act", Strings.t(lang, "act_" + lastAct)) : Strings.t(lang, "status_off");
        if (!force && text.equals(statusShown)) return;
        notifySafe(NID_STATUS, statusNotification());
    }

    private void info(String title, String body) {
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        notifySafe(NID_INFO, new NotificationCompat.Builder(this, CH_INFO)
                .setSmallIcon(R.drawable.ic_stat_vest)
                .setColor(Color.rgb(0xD9, 0x8E, 0x04))
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build());
    }

    private void notifySafe(int id, Notification n) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(id, n);
        } catch (Exception e) {
            Log.w(TAG, "notify", e);
        }
    }

    // ------------------------------------------------------------------ storage
    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("vestguard", Context.MODE_PRIVATE);
    }

    static JSONObject config(Context c) {
        try {
            return new JSONObject(prefs(c).getString("config", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private String lang() { return config(this).optString("lang", "en"); }

    private String wearerFirst() {
        String w = config(this).optString("wearer", "").trim();
        if (w.isEmpty()) return Strings.t(lang(), "wearer");
        int sp = w.indexOf(' ');
        return sp > 0 ? w.substring(0, sp) : w;
    }

    private void storePending(JSONObject ev) {
        synchronized (VestService.class) {
            SharedPreferences sp = prefs(this);
            JSONArray arr;
            try { arr = new JSONArray(sp.getString("pending", "[]")); } catch (Exception e) { arr = new JSONArray(); }
            arr.put(ev);
            for (int i = 0; arr.length() > 300 && i < arr.length(); ) {       // trim oldest, but keep falls and acks
                String l = arr.optJSONObject(i) != null ? arr.optJSONObject(i).optString("line", "") : "";
                if (l.startsWith("F,") || l.startsWith("ACK,")) i++; else arr.remove(i);
            }
            sp.edit().putString("pending", arr.toString()).apply();
        }
    }

    static JSONArray drainPending(Context c) {
        synchronized (VestService.class) {
            SharedPreferences sp = prefs(c);
            JSONArray arr;
            try { arr = new JSONArray(sp.getString("pending", "[]")); } catch (Exception e) { arr = new JSONArray(); }
            sp.edit().putString("pending", "[]").apply();
            return arr;
        }
    }

    JSONObject state() {
        JSONObject o = new JSONObject();
        try {
            long now = System.currentTimeMillis();
            o.put("running", true);
            o.put("online", lastRx > 0 && now - lastRx < ONLINE_MS);
            o.put("lastRx", lastRx);
            o.put("vestIp", vestAddr != null ? vestAddr.getHostAddress() : JSONObject.NULL);
            o.put("ringing", Alarm.isRinging());
            o.put("alertId", alertId != null ? alertId : JSONObject.NULL);
        } catch (Exception ignored) { }
        return o;
    }
}
