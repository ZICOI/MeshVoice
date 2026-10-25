package com.meshvoice;

import java.util.TreeMap;

/** Per-sender playout buffer. Holds decoded 20 ms PCM frames indexed by 16-bit seq. */
public final class JitterBuffer {
    private static final int MAX_FRAMES = 10;
    private static final int MAX_SKIP = 4;

    private final TreeMap<Integer, short[]> frames = new TreeMap<>();
    private int expected = -1;
    private int gapTicks;

    public synchronized void put(int seq, short[] pcm) {
        if (pcm == null) return;
        int s = seq & 0xFFFF;
        if (expected < 0) expected = s;
        // Late frames that are already behind the playout point are useless.
        if (((s - expected) & 0xFFFF) > 0x8000) return;
        frames.put(s, pcm);
        while (frames.size() > MAX_FRAMES) {
            frames.pollFirstEntry();
        }
    }

    /** Returns the next frame, or null when it is missing (caller does PLC). */
    public synchronized short[] take() {
        if (expected < 0) return null;
        short[] d = frames.remove(expected);
        if (d != null) {
            expected = (expected + 1) & 0xFFFF;
            gapTicks = 0;
            return d;
        }
        if (frames.isEmpty()) {
            expected = (expected + 1) & 0xFFFF;
            gapTicks = 0;
            return null;
        }
        int first = frames.firstKey();
        int diff = (first - expected) & 0xFFFF;
        if (diff > MAX_SKIP) {
            // Stream restarted or a long burst was lost: jump to what we have.
            expected = (first + 1) & 0xFFFF;
            gapTicks = 0;
            return frames.remove(first);
        }
        // Tolerate one tick of reordering before skipping the missing frame.
        if (++gapTicks >= 2) {
            expected = (first + 1) & 0xFFFF;
            gapTicks = 0;
            return frames.remove(first);
        }
        return null;
    }

    public synchronized int depth() {
        return frames.size();
    }

    public synchronized void reset() {
        frames.clear();
        expected = -1;
        gapTicks = 0;
    }
}
