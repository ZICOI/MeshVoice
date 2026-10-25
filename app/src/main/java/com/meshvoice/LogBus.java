package com.meshvoice;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** Tiny in-process log bus: service -> UI and logcat. */
public final class LogBus {
    public interface Listener {
        void onLine(String line);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private LogBus() {
    }

    public static void add(Listener l) {
        if (l != null && !LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void remove(Listener l) {
        LISTENERS.remove(l);
    }

    public static void log(String tag, String msg) {
        String line;
        synchronized (FMT) {
            line = FMT.format(new Date()) + " [" + tag + "] " + msg;
        }
        android.util.Log.i("MeshVoice", line);
        for (Listener l : LISTENERS) {
            try {
                l.onLine(line);
            } catch (Throwable ignored) {
            }
        }
    }
}
