package com.knutsolabs.comity;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/** Holds the one recording that can be running. Audio goes to a private file that is deleted once it's transcribed. */
final class Recorder {
    private static MediaRecorder recorder;
    private static File file;
    private static long startedAt, pausedAt, pausedTotal;
    private static boolean recording, paused;

    private Recorder() { }

    static File audioDir(Context c) {
        File dir = new File(c.getFilesDir(), "audio");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    static synchronized void begin(Context c) throws Exception {
        if (recording) return;
        file = new File(audioDir(c), "conversation-" + System.currentTimeMillis() + ".m4a");
        MediaRecorder r = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(c) : new MediaRecorder();
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioChannels(1);
            r.setAudioSamplingRate(44100);
            r.setAudioEncodingBitRate(48000);
            r.setOutputFile(file.getAbsolutePath());
            r.prepare();
            r.start();
        } catch (Exception e) {
            r.release();
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            file = null;
            throw e;
        }
        recorder = r;
        recording = true;
        paused = false;
        startedAt = SystemClock.elapsedRealtime();
        pausedTotal = 0;
    }

    static synchronized boolean isRecording() { return recording; }

    static synchronized boolean isPaused() { return paused; }

    static synchronized long elapsed() {
        if (!recording) return 0;
        long now = paused ? pausedAt : SystemClock.elapsedRealtime();
        return now - startedAt - pausedTotal;
    }

    static synchronized void pause() {
        if (!recording || paused) return;
        try { recorder.pause(); } catch (RuntimeException ignored) { return; }
        paused = true;
        pausedAt = SystemClock.elapsedRealtime();
    }

    static synchronized void resume() {
        if (!recording || !paused) return;
        try { recorder.resume(); } catch (RuntimeException ignored) { return; }
        pausedTotal += SystemClock.elapsedRealtime() - pausedAt;
        paused = false;
    }

    /** Stops and returns {path, durationMs} or {error}. */
    static synchronized String finish() {
        JSONObject o = new JSONObject();
        try {
            if (!recording) { o.put("error", "not recording"); return o.toString(); }
            long duration = elapsed();
            boolean ok = true;
            try { recorder.stop(); } catch (RuntimeException e) { ok = false; }
            recorder.release();
            recorder = null;
            recording = false;
            paused = false;
            if (ok && file != null && file.length() > 0) {
                o.put("path", file.getAbsolutePath());
                o.put("durationMs", duration);
            } else {
                if (file != null) //noinspection ResultOfMethodCallIgnored
                    file.delete();
                o.put("error", "the recording was empty");
            }
            file = null;
        } catch (JSONException ignored) { }
        return o.toString();
    }

    static synchronized String stateJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("recording", recording);
            o.put("paused", paused);
            o.put("elapsedMs", elapsed());
        } catch (JSONException ignored) { }
        return o.toString();
    }

    /** The file being recorded right now, so cleanup never deletes it. */
    static synchronized String currentPath() { return file == null ? "" : file.getAbsolutePath(); }
}
