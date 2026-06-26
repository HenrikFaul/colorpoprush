package com.colorpop.rush;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Procedurally synthesised sound effects. All PCM is generated at runtime so
 * the APK ships no audio assets. Everything is wrapped defensively: if audio
 * hardware is unavailable the game simply stays silent rather than crashing.
 */
public class SoundManager {

    private static final int RATE = 22050;

    private static final int MAX_VOICES = 6;

    private HandlerThread thread;
    private Handler handler;
    private boolean enabled = true;
    private volatile boolean released = false;
    private final AtomicInteger active = new AtomicInteger(0);
    private final Set<AudioTrack> live = Collections.synchronizedSet(new HashSet<AudioTrack>());

    // Pre-rendered clips.
    private short[] click;
    private short[] win;
    private short[] fail;
    private short[] coin;
    private short[] swoosh;
    private short[] power;
    private short[][] pop; // indexed by combo step

    public SoundManager() {
        try {
            thread = new HandlerThread("cpr-audio");
            thread.start();
            handler = new Handler(thread.getLooper());
            render();
        } catch (Throwable t) {
            enabled = false;
        }
    }

    public void setEnabled(boolean on) {
        enabled = on;
    }

    private void render() {
        click = blip(900f, 0.05f, 0.30f);
        coin = sequence(new float[]{1320f, 1760f}, 0.07f, 0.28f);
        win = sequence(new float[]{660f, 880f, 1100f, 1320f}, 0.12f, 0.30f);
        fail = sequence(new float[]{440f, 330f, 247f}, 0.16f, 0.26f);
        swoosh = noiseSweep(0.18f, 0.18f);
        power = boom();
        pop = new short[10][];
        for (int i = 0; i < pop.length; i++) {
            float base = 520f * (float) Math.pow(1.0595f, i * 2); // rise ~ a tone per combo step
            pop[i] = popClip(base);
        }
    }

    // --- Public play methods ---------------------------------------------

    public void playPop(int combo) {
        if (pop == null) {
            return;
        }
        int idx = Math.max(0, Math.min(pop.length - 1, combo - 1));
        play(pop[idx]);
    }

    public void playClick() {
        play(click);
    }

    public void playWin() {
        play(win);
    }

    public void playFail() {
        play(fail);
    }

    public void playCoin() {
        play(coin);
    }

    public void playSwoosh() {
        play(swoosh);
    }

    public void playPower() {
        play(power);
    }

    // --- Playback ---------------------------------------------------------

    private void play(final short[] data) {
        if (!enabled || released || handler == null || data == null || data.length == 0) {
            return;
        }
        // Cap simultaneous voices so rapid combos can't exhaust native AudioTrack
        // resources (the original per-sound allocation could leak/exhaust on some OEMs).
        if (active.get() >= MAX_VOICES) {
            return;
        }
        try {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    final AudioTrack track;
                    try {
                        int bytes = data.length * 2;
                        track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE,
                                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                                bytes, AudioTrack.MODE_STATIC);
                    } catch (Throwable t) {
                        return;
                    }
                    try {
                        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                            track.release();
                            return;
                        }
                        active.incrementAndGet();
                        live.add(track);
                        track.write(data, 0, data.length);
                        track.play();
                        // Guaranteed teardown: never rely on the marker callback (it may
                        // never fire on some devices, leaking the native track).
                        long ms = (long) (data.length * 1000L / RATE) + 90L;
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                live.remove(track);
                                try { track.stop(); } catch (Throwable ignored) {}
                                try { track.release(); } catch (Throwable ignored) {}
                                active.decrementAndGet();
                            }
                        }, ms);
                    } catch (Throwable t) {
                        try { track.release(); } catch (Throwable ignored) {}
                        active.decrementAndGet();
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public void release() {
        released = true;
        // Release any still-playing tracks now; postDelayed teardowns won't run
        // once the looper quits, so drain explicitly to avoid leaking native tracks.
        synchronized (live) {
            for (AudioTrack t : live) {
                try { t.stop(); } catch (Throwable ignored) {}
                try { t.release(); } catch (Throwable ignored) {}
            }
            live.clear();
        }
        try {
            if (thread != null) {
                thread.quitSafely();
            }
        } catch (Throwable ignored) {
        }
    }

    // --- Synthesis helpers ------------------------------------------------

    /** A short sine "blip" with quick attack and exponential decay. */
    private static short[] blip(float freq, float seconds, float amp) {
        int n = (int) (RATE * seconds);
        short[] out = new short[n];
        double phase = 0, dp = 2 * Math.PI * freq / RATE;
        for (int i = 0; i < n; i++) {
            float env = envelope(i, n);
            out[i] = (short) (Math.sin(phase) * env * amp * 32767);
            phase += dp;
        }
        return out;
    }

    /** Pop sound: a blip whose pitch sweeps slightly downward for a "plop". */
    private static short[] popClip(float freq) {
        float seconds = 0.13f;
        int n = (int) (RATE * seconds);
        short[] out = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            float t = (float) i / n;
            float f = freq * (1.25f - 0.5f * t);      // downward sweep
            double dp = 2 * Math.PI * f / RATE;
            float env = (float) Math.pow(1f - t, 2.2f); // fast decay
            float wobble = (float) (0.85 + 0.15 * Math.sin(phase * 2));
            out[i] = (short) (Math.sin(phase) * env * wobble * 0.32f * 32767);
            phase += dp;
        }
        return out;
    }

    /** Play a series of notes back to back. */
    private static short[] sequence(float[] freqs, float each, float amp) {
        int per = (int) (RATE * each);
        short[] out = new short[per * freqs.length];
        int o = 0;
        for (float f : freqs) {
            double phase = 0, dp = 2 * Math.PI * f / RATE;
            for (int i = 0; i < per; i++) {
                float env = envelope(i, per);
                out[o++] = (short) (Math.sin(phase) * env * amp * 32767);
                phase += dp;
            }
        }
        return out;
    }

    /** A low, punchy boom for power-tile detonations. */
    private static short[] boom() {
        float seconds = 0.32f;
        int n = (int) (RATE * seconds);
        short[] out = new short[n];
        double phase = 0;
        java.util.Random rnd = new java.util.Random(3);
        for (int i = 0; i < n; i++) {
            float t = (float) i / n;
            float f = 180f * (1.0f - 0.6f * t);
            double dp = 2 * Math.PI * f / RATE;
            float env = (float) Math.pow(1f - t, 1.8f);
            float noise = (rnd.nextFloat() * 2 - 1) * (1f - t) * 0.4f;
            out[i] = (short) ((Math.sin(phase) * 0.6f + noise) * env * 0.5f * 32767);
            phase += dp;
        }
        return out;
    }

    /** Filtered-noise downward sweep for a soft "swoosh". */
    private static short[] noiseSweep(float seconds, float amp) {
        int n = (int) (RATE * seconds);
        short[] out = new short[n];
        java.util.Random rnd = new java.util.Random(7);
        float prev = 0;
        for (int i = 0; i < n; i++) {
            float t = (float) i / n;
            float white = rnd.nextFloat() * 2 - 1;
            float cutoff = 0.05f + 0.4f * (1 - t);     // low-pass that closes over time
            prev = prev + cutoff * (white - prev);
            float env = (float) (Math.sin(Math.PI * t));
            out[i] = (short) (prev * env * amp * 32767);
        }
        return out;
    }

    /** Linear attack / exponential-ish release envelope. */
    private static float envelope(int i, int n) {
        float attack = n * 0.08f;
        if (i < attack) {
            return i / attack;
        }
        float t = (i - attack) / (n - attack);
        return (float) Math.pow(1f - t, 1.6f);
    }
}
