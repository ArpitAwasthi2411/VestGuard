package com.silvershield.vestguard;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

/**
 * Fall alarm on the ALARM audio stream (plays even when the ringer is on silent) plus a repeating vibration.
 *
 *  - sound: the VestGuard chime (default; a rising bell arpeggio, urgent but not harsh), or the phone's own
 *    alarm tone if the family prefers it (config "alarmSound": "phone")
 *  - volume: starts at 70 % and rises to full after 10 s, so the first second isn't a shock
 *  - duck(): lowers the tone while the phone speaks ("Kamla may have fallen…")
 *  - chime(): one soft two-note chime, used while the Home Hub is still asking the wearer
 * Stops when a caregiver responds, or after 5 minutes.
 */
final class Alarm {
    private static final String TAG = "VestGuardAlarm";
    private static final long AUTO_STOP_MS = 5 * 60 * 1000L;
    private static final long RAMP_MS = 10_000L;
    private static MediaPlayer player;
    private static Vibrator vibrator;
    private static int savedVolume = -1;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Runnable autoStop = Alarm::stopNow;
    private static final Runnable rampUp = Alarm::fullVolume;
    private static final Runnable unduck = () -> setPlayerVolume(1f);
    private static Context appCtx;
    private static boolean ringing = false;

    private Alarm() {}

    static synchronized boolean isRinging() { return ringing; }

    /** a short test ring; never shortens a real alarm that is already ringing */
    static void test(Context ctx) {
        appCtx = ctx.getApplicationContext();
        main.post(() -> { synchronized (Alarm.class) { if (ringing) return; } startNow(3000); });
    }

    static void start(Context ctx, long durationMs) {
        appCtx = ctx.getApplicationContext();
        main.post(() -> startNow(durationMs > 0 ? durationMs : AUTO_STOP_MS));
    }

    static void stop() { main.post(Alarm::stopNow); }

    /** quieter tone for a few seconds (while text-to-speech talks over it) */
    static void duck(long ms) {
        main.post(() -> {
            setPlayerVolume(0.15f);
            main.removeCallbacks(unduck);
            main.postDelayed(unduck, ms);
        });
    }

    /** one soft chime (no loop, no volume change) */
    static void chime(Context ctx) {
        Context c = ctx.getApplicationContext();
        main.post(() -> {
            synchronized (Alarm.class) { if (ringing) return; }
            MediaPlayer mp = tryPlay(c, null, R.raw.vg_chime, false);
            if (mp != null) mp.setOnCompletionListener(p -> { try { p.release(); } catch (Exception ignored) { } });
            vibrate(c, new long[]{0, 120, 120, 120}, -1);
        });
    }

    private static synchronized void setPlayerVolume(float v) {
        if (player != null) try { player.setVolume(v, v); } catch (Exception ignored) { }
    }

    private static int streamVolume(AudioManager am, float frac) {
        int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        return Math.max(1, (int) Math.ceil(max * frac));
    }

    private static synchronized void fullVolume() {
        if (!ringing || appCtx == null) return;
        try {
            AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int want = streamVolume(am, 1f);
                if (am.getStreamVolume(AudioManager.STREAM_ALARM) < want) {
                    if (savedVolume < 0) savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
                    am.setStreamVolume(AudioManager.STREAM_ALARM, want, 0);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "ramp", e);
        }
    }

    private static synchronized void startNow(long durationMs) {
        main.removeCallbacks(autoStop);
        main.postDelayed(autoStop, durationMs);
        if (ringing) return;
        Context ctx = appCtx;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int cur = am.getStreamVolume(AudioManager.STREAM_ALARM);
                int want = streamVolume(am, 0.7f);
                if (cur < want) {
                    savedVolume = cur;
                    am.setStreamVolume(AudioManager.STREAM_ALARM, want, 0);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "volume", e);
        }
        boolean phoneTone = "phone".equals(VestService.config(ctx).optString("alarmSound", "chime"));
        player = null;
        if (phoneTone) {
            Uri sys = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM);
            if (sys == null) sys = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE);
            if (sys != null) player = tryPlay(ctx, sys, 0, true);
        }
        if (player == null) player = tryPlay(ctx, null, R.raw.vg_alert, true);
        if (player == null) player = tryPlay(ctx, null, R.raw.vg_alarm, true);
        ringing = true;
        main.removeCallbacks(rampUp);
        main.postDelayed(rampUp, RAMP_MS);
        vibrate(ctx, new long[]{0, 500, 250, 500, 250, 900, 700}, 0);
    }

    private static void vibrate(Context ctx, long[] pattern, int repeat) {
        try {
            Vibrator v;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                v = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (v == null) return;
            if (repeat >= 0) vibrator = v;
            if (Build.VERSION.SDK_INT >= 26) {
                AudioAttributes aa = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build();
                v.vibrate(VibrationEffect.createWaveform(pattern, repeat), aa);
            } else {
                v.vibrate(pattern, repeat);
            }
        } catch (Exception e) {
            Log.w(TAG, "vibrate", e);
        }
    }

    private static MediaPlayer tryPlay(Context ctx, Uri uri, int rawRes, boolean loop) {
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            if (uri != null) {
                mp.setDataSource(ctx, uri);
            } else {
                android.content.res.AssetFileDescriptor fd = ctx.getResources().openRawResourceFd(rawRes);
                mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                fd.close();
            }
            mp.setLooping(loop);
            mp.prepare();
            mp.start();
            return mp;
        } catch (Exception e) {
            Log.w(TAG, "player " + uri, e);
            try { mp.release(); } catch (Exception ignored) { }
            return null;
        }
    }

    private static synchronized void stopNow() {
        main.removeCallbacks(autoStop);
        main.removeCallbacks(rampUp);
        main.removeCallbacks(unduck);
        ringing = false;
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        if (vibrator != null) {
            try { vibrator.cancel(); } catch (Exception ignored) { }
            vibrator = null;
        }
        if (savedVolume >= 0 && appCtx != null) {
            try {
                AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0);
            } catch (Exception ignored) { }
            savedVolume = -1;
        }
    }
}
