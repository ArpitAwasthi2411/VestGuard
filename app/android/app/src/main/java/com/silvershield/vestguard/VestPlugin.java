package com.silvershield.vestguard;

import android.Manifest;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

/** Bridge between the web UI (www/) and the native vest service. JS name: VestNative */
@CapacitorPlugin(name = "VestNative")
public class VestPlugin extends Plugin {
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void load() {
        VestService.listener = (line, ts) -> main.post(() -> {
            JSObject o = new JSObject();
            o.put("line", line);
            o.put("ts", ts);
            notifyListeners("line", o);
        });
    }

    private void attachData() {
        VestService.dataListener = lines -> main.post(() -> {
            JSObject o = new JSObject();
            o.put("lines", lines);
            notifyListeners("data", o);
        });
    }

    @Override
    protected void handleOnResume() {
        main.post(() -> notifyListeners("resume", new JSObject()));
    }

    @Override
    protected void handleOnDestroy() {
        VestService.listener = null;
        VestService.dataListener = null;
        VestService.research = false;
    }

    private Context ctx() { return getContext(); }

    // ------------------------------------------------------------ service
    @PluginMethod
    public void start(PluginCall call) {
        VestService.start(ctx());
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Intent i = new Intent(ctx(), VestService.class).setAction(VestService.ACTION_STOP);
        try { ctx().startService(i); } catch (Exception ignored) { }
        call.resolve();
    }

    @PluginMethod
    public void setConfig(PluginCall call) {
        JSObject data = call.getData();
        VestService.prefs(ctx()).edit().putString("config", data.toString()).apply();
        VestService s = VestService.instance;
        if (s != null) {
            Intent i = new Intent(ctx(), VestService.class).setAction(VestService.ACTION_REFRESH);
            try { ContextCompat.startForegroundService(ctx(), i); } catch (Exception ignored) { }
        }
        call.resolve();
    }

    @PluginMethod
    public void send(PluginCall call) {
        String cmd = call.getString("cmd", "");
        VestService s = VestService.instance;
        JSObject r = new JSObject();
        if (s == null || s.vestAddr == null || cmd.isEmpty()) {
            r.put("sent", false);
        } else {
            s.sendAsync(cmd);
            r.put("sent", true);
        }
        call.resolve(r);
    }

    @PluginMethod
    public void drain(PluginCall call) {
        JSONArray arr = VestService.drainPending(ctx());
        JSArray out = new JSArray();
        for (int i = 0; i < arr.length(); i++) {
            Object o = arr.opt(i);
            if (o instanceof JSONObject) {
                try { out.put(JSObject.fromJSONObject((JSONObject) o)); } catch (Exception ignored) { }
            }
        }
        JSObject r = new JSObject();
        r.put("events", out);
        call.resolve(r);
    }

    @PluginMethod
    public void getState(PluginCall call) {
        VestService s = VestService.instance;
        JSObject r = new JSObject();
        if (s == null) {
            r.put("running", false);
            r.put("online", false);
        } else {
            try { r = JSObject.fromJSONObject(s.state()); } catch (Exception ignored) { }
        }
        call.resolve(r);
    }

    // ------------------------------------------------------------ alarm
    @PluginMethod
    public void raiseAlarm(PluginCall call) {
        String id = call.getString("id", "demo");
        int sev = call.getInt("sev", 3);
        boolean demo = Boolean.TRUE.equals(call.getBoolean("demo", true));
        if (Boolean.TRUE.equals(call.getBoolean("ringOnly", false))) {   // the alert already exists; just make noise
            Alarm.start(ctx(), 0);
            call.resolve();
            return;
        }
        VestService s = VestService.instance;
        if (s != null) s.raiseAlarm(id, sev, demo, System.currentTimeMillis(), null);
        else Alarm.start(ctx(), 0);
        call.resolve();
    }

    @PluginMethod
    public void ackAlarm(PluginCall call) {
        String id = call.getString("id");
        VestService s = VestService.instance;
        if (s != null) s.acknowledge(id, false);
        else Alarm.stop();
        call.resolve();
    }

    @PluginMethod
    public void clearAlarm(PluginCall call) {
        String id = call.getString("id");
        VestService s = VestService.instance;
        if (s != null) s.clearAlert(id);
        else Alarm.stop();
        call.resolve();
    }

    @PluginMethod
    public void stopAlarm(PluginCall call) {
        Alarm.stop();
        call.resolve();
    }

    @PluginMethod
    public void testAlarm(PluginCall call) {
        Alarm.start(ctx(), 3000);
        call.resolve();
    }

    // ------------------------------------------------------------ permissions
    private boolean granted(String p) {
        return ContextCompat.checkSelfPermission(ctx(), p) == PackageManager.PERMISSION_GRANTED;
    }

    @PluginMethod
    public void permissions(PluginCall call) {
        JSObject r = new JSObject();
        r.put("notifications", NotificationManagerCompat.from(ctx()).areNotificationsEnabled());
        r.put("location", granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION));
        r.put("sms", granted(Manifest.permission.SEND_SMS));
        boolean fsi = true;
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = (NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE);
            fsi = nm != null && nm.canUseFullScreenIntent();
        }
        r.put("fullScreen", fsi);
        boolean battery = true;
        PowerManager pm = (PowerManager) ctx().getSystemService(Context.POWER_SERVICE);
        if (pm != null) battery = pm.isIgnoringBatteryOptimizations(ctx().getPackageName());
        r.put("battery", battery);
        r.put("sdk", Build.VERSION.SDK_INT);
        call.resolve(r);
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        String name = call.getString("name", "");
        String pkg = ctx().getPackageName();
        try {
            switch (name) {
                case "notifications":
                    if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                        ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 701);
                    } else {
                        openSettings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg));
                    }
                    break;
                case "location":
                    ActivityCompat.requestPermissions(getActivity(), new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 702);
                    break;
                case "sms":
                    ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.SEND_SMS}, 703);
                    break;
                case "fullScreen":
                    if (Build.VERSION.SDK_INT >= 34) {
                        openSettings(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + pkg)));
                    }
                    break;
                case "battery":
                    openSettings(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + pkg)));
                    break;
                case "app":
                    openSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
                    break;
                case "hotspot":
                    openSettings(new Intent(Intent.ACTION_MAIN).setClassName("com.android.settings", "com.android.settings.TetherSettings"),
                            new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            call.reject(e.getMessage());
            return;
        }
        call.resolve();
    }

    private void openSettings(Intent... options) {
        for (Intent i : options) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getActivity().startActivity(i);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) { }
        }
        Intent fallback = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { getActivity().startActivity(fallback); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ research mode
    @PluginMethod
    public void setResearch(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        VestService.research = on;
        if (on) attachData(); else VestService.dataListener = null;
        VestService s = VestService.instance;
        if (s != null) s.sendAsync(on ? "P" : "PH");
        call.resolve();
    }

    /** Save a text file (CSV / JSON) to Downloads/VestGuard, optionally open the share sheet. */
    @PluginMethod
    public void saveFile(PluginCall call) {
        String name = call.getString("name", "vestguard.csv").replaceAll("[^A-Za-z0-9._-]", "_");
        String text = call.getString("text", "");
        String mime = call.getString("mime", "text/csv");
        boolean share = Boolean.TRUE.equals(call.getBoolean("share", false));
        try {
            Uri uri;
            String where;
            byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/VestGuard");
                uri = ctx().getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new java.io.IOException("could not create file");
                try (java.io.OutputStream os = ctx().getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new java.io.IOException("could not open file");
                    os.write(bytes);
                }
                where = "Downloads/VestGuard/" + name;
            } else {
                java.io.File dir = new java.io.File(ctx().getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "VestGuard");
                if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("could not create folder");
                java.io.File f = new java.io.File(dir, name);
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) { os.write(bytes); }
                uri = androidx.core.content.FileProvider.getUriForFile(ctx(), ctx().getPackageName() + ".fileprovider", f);
                where = f.getAbsolutePath();
            }
            if (share) {
                Intent send = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(send, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getActivity().startActivity(chooser);
            }
            JSObject r = new JSObject();
            r.put("path", where);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("Save failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ links (tel:, maps)
    @PluginMethod
    public void openUrl(PluginCall call) {
        String url = call.getString("url", "");
        try {
            Intent i = new Intent(url.startsWith("tel:") ? Intent.ACTION_DIAL : Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getActivity().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("Cannot open " + url);
        }
    }
}
