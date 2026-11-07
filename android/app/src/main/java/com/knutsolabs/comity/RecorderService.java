package com.knutsolabs.comity;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

/**
 * Keeps Comity listening while the phone is locked or another app is open.
 * Android requires a visible notification for this; tapping it opens Comity.
 */
public class RecorderService extends Service {
    static final String ACTION_START = "com.knutsolabs.comity.START";
    static final String ACTION_UPDATE = "com.knutsolabs.comity.UPDATE";
    private static final String CHANNEL = "listening";
    private static final int NOTE_ID = 1;

    private PowerManager.WakeLock wakeLock;

    static void start(Context c) {
        ContextCompat.startForegroundService(c, new Intent(c, RecorderService.class).setAction(ACTION_START));
    }

    static void refresh(Context c) {
        if (Recorder.isRecording()) c.startService(new Intent(c, RecorderService.class).setAction(ACTION_UPDATE));
    }

    /** Called by the app after it has stopped the recording itself. */
    static void stop(Context c) {
        c.stopService(new Intent(c, RecorderService.class));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_UPDATE.equals(action)) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && Recorder.isRecording()) nm.notify(NOTE_ID, buildNote());
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) { stopSelf(); return START_NOT_STICKY; }

        createChannel();
        try {
            ServiceCompat.startForeground(this, NOTE_ID, buildNote(),
                    Build.VERSION.SDK_INT >= 30 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0);
        } catch (Exception e) {
            MainActivity.sendToPage("window.onRecError && window.onRecError('the phone blocked background listening')");
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            Recorder.begin(this);
        } catch (Exception e) {
            String why = e.getMessage() == null ? "the microphone is busy" : e.getMessage().replace("'", " ");
            MainActivity.sendToPage("window.onRecError && window.onRecError('" + why + "')");
            stopSelf();
            return START_NOT_STICKY;
        }
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "comity:listening");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(6 * 60 * 60 * 1000L);
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTE_ID, buildNote());
        MainActivity.sendToPage("window.onRecStarted && window.onRecStarted()");
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        // If Android ends the service before the app stops it, keep what was recorded so it can be finished later.
        if (Recorder.isRecording()) {
            String result = Recorder.finish();
            getSharedPreferences("comity", MODE_PRIVATE).edit().putString("orphan", result).apply();
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.channel_desc));
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNote() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean paused = Recorder.isPaused();
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle(paused ? "Comity is paused" : "Comity is listening")
                .setContentText("Tap to open Comity and stop when you're done.")
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        if (!paused) {
            b.setUsesChronometer(true).setShowWhen(true).setWhen(System.currentTimeMillis() - Recorder.elapsed());
        }
        return b.build();
    }
}
