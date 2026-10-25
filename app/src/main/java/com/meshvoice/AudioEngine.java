package com.meshvoice;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.AudioRecordingConfiguration;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.SystemClock;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Capture -> Opus -> sink, and network -> jitter buffer -> mix -> playout.
 * Bluetooth headset (HFP/SCO) is only used as microphone + speaker.
 */
public final class AudioEngine {
    public interface Sink {
        void onEncoded(byte[] opus);
    }

    private static final String TAG = "AUDIO";
    private static final int FRAME_BYTES = Protocol.FRAME_SAMPLES * 2;

    private final Context ctx;
    private final Sink sink;
    private final Map<Integer, Source> sources = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private AudioManager am;
    private AudioRecord record;
    private AudioTrack track;
    private OpusCodec encoder;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;
    private AudioFocusRequest focusRequest;
    private Thread captureThread;
    private Thread playThread;
    private volatile boolean codecOk;

    private static final class Source {
        final JitterBuffer jb = new JitterBuffer();
        OpusCodec decoder;
        short[] last;
        int misses;
    }

    public AudioEngine(Context ctx, Sink sink) {
        this.ctx = ctx.getApplicationContext();
        this.sink = sink;
    }

    public boolean isCodecOk() {
        return codecOk;
    }

    public void start() {
        if (running.getAndSet(true)) return;
        am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        routeBluetooth();
        requestFocus();

        try {
            encoder = OpusCodec.createEncoder();
        } catch (Throwable t) {
            LogBus.log(TAG, "Opus 编码器不可用: " + t);
        }
        codecOk = encoder != null;

        try {
            int min = AudioRecord.getMinBufferSize(Protocol.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int buf = Math.max(min, FRAME_BYTES * 8);
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    Protocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, buf);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                LogBus.log(TAG, "AudioRecord 初始化失败");
                record = null;
            }
        } catch (Throwable t) {
            LogBus.log(TAG, "AudioRecord 异常: " + t);
        }

        enableDsp();

        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Protocol.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(FRAME_BYTES * 6)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();
        } catch (Throwable t) {
            LogBus.log(TAG, "AudioTrack 异常: " + t);
            track = null;
        }

        captureThread = new Thread(this::captureLoop, "audio-capture");
        playThread = new Thread(this::playLoop, "audio-play");
        captureThread.start();
        playThread.start();
        // SCO often comes up a moment after the call audio mode is set.
        new Thread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                return;
            }
            if (running.get()) routeBluetooth();
        }, "audio-route-retry").start();
        LogBus.log(TAG, "音频引擎启动, opus=" + codecOk);
    }

    private void routeBluetooth() {
        try {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            boolean routed = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        routed = am.setCommunicationDevice(d);
                        break;
                    }
                }
            } else {
                am.startBluetoothSco();
                am.setBluetoothScoOn(true);
                routed = true;
            }
            if (!routed) {
                am.setSpeakerphoneOn(true);
                LogBus.log(TAG, "未发现蓝牙 SCO 设备，改用手机扬声器");
            } else {
                LogBus.log(TAG, "音频路由: 蓝牙 SCO");
            }
        } catch (Throwable t) {
            LogBus.log(TAG, "音频路由失败: " + t);
        }
    }

    private void requestFocus() {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .build();
            am.requestAudioFocus(focusRequest);
        } catch (Throwable ignored) {
        }
    }

    private void enableDsp() {
        if (record == null) return;
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(record.getAudioSessionId());
                if (aec != null) aec.setEnabled(true);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(record.getAudioSessionId());
                if (ns != null) ns.setEnabled(true);
            }
        } catch (Throwable ignored) {
        }
    }

    private void captureLoop() {
        if (record == null) return;
        short[] buf = new short[Protocol.FRAME_SAMPLES];
        long pts = 0;
        try {
            record.startRecording();
        } catch (Throwable t) {
            LogBus.log(TAG, "startRecording 失败: " + t);
            return;
        }
        LogBus.log(TAG, "录音开始");
        while (running.get()) {
            int n;
            try {
                n = record.read(buf, 0, buf.length, AudioRecord.READ_BLOCKING);
            } catch (Throwable t) {
                break;
            }
            if (n != buf.length) {
                if (n < 0) break;
                continue;
            }
            if (encoder != null) {
                byte[] op = encoder.encode(buf, pts);
                if (op != null && op.length > 0) sink.onEncoded(op);
            }
            pts += 20_000L;
        }
    }

    private void playLoop() {
        if (track == null) return;
        short[] mix = new short[Protocol.FRAME_SAMPLES];
        int[] acc = new int[Protocol.FRAME_SAMPLES];
        long next = System.nanoTime();
        while (running.get()) {
            next += 20_000_000L;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                LockSupport.parkNanos(sleep);
            } else {
                next = System.nanoTime();
            }
            Arrays.fill(acc, 0);
            boolean any = false;
            for (Source s : sources.values()) {
                short[] pcm = s.jb.take();
                if (pcm == null) {
                    s.misses++;
                    if (s.last != null && s.misses <= 3) {
                        pcm = s.last;
                        short[] faded = new short[pcm.length];
                        for (int i = 0; i < pcm.length; i++) faded[i] = (short) (pcm[i] * 0.6f);
                        s.last = faded;
                        pcm = faded;
                    }
                } else {
                    s.misses = 0;
                    s.last = pcm;
                }
                if (pcm != null) {
                    any = true;
                    int n = Math.min(pcm.length, acc.length);
                    for (int i = 0; i < n; i++) acc[i] += pcm[i];
                }
            }
            if (!any) {
                Arrays.fill(mix, (short) 0);
            } else {
                for (int i = 0; i < acc.length; i++) {
                    int v = acc[i];
                    if (v > 32767) v = 32767;
                    else if (v < -32768) v = -32768;
                    mix[i] = (short) v;
                }
            }
            try {
                track.write(mix, 0, mix.length);
            } catch (Throwable t) {
                break;
            }
        }
    }

    public void ensureSource(int sid) {
        sources.computeIfAbsent(sid, k -> {
            Source s = new Source();
            try {
                s.decoder = OpusCodec.createDecoder();
            } catch (Throwable t) {
                LogBus.log(TAG, "Opus 解码器不可用: " + t);
            }
            return s;
        });
    }

    public void removeSource(int sid) {
        Source s = sources.remove(sid);
        if (s != null && s.decoder != null) s.decoder.release();
    }

    /** Called on the BLE thread for every received media packet. */
    public void onMedia(int sid, int seq, byte[] payload) {
        Source s = sources.get(sid);
        if (s == null) {
            ensureSource(sid);
            s = sources.get(sid);
            if (s == null) return;
        }
        if (s.decoder == null) return;
        short[] pcm = s.decoder.decode(payload, SystemClock.elapsedRealtime() * 1000L);
        if (pcm != null) s.jb.put(seq, pcm);
    }

    public void stop() {
        if (!running.getAndSet(false)) return;
        try {
            if (record != null) record.stop();
        } catch (Throwable ignored) {
        }
        if (captureThread != null) {
            try {
                captureThread.join(500);
            } catch (InterruptedException ignored) {
            }
        }
        if (playThread != null) {
            try {
                playThread.join(500);
            } catch (InterruptedException ignored) {
            }
        }
        try {
            if (aec != null) aec.release();
        } catch (Throwable ignored) {
        }
        try {
            if (ns != null) ns.release();
        } catch (Throwable ignored) {
        }
        if (encoder != null) encoder.release();
        for (Source s : sources.values()) {
            if (s.decoder != null) s.decoder.release();
        }
        sources.clear();
        try {
            if (record != null) record.release();
        } catch (Throwable ignored) {
        }
        try {
            if (track != null) {
                track.stop();
                track.release();
            }
        } catch (Throwable ignored) {
        }
        record = null;
        track = null;
        try {
            if (am != null) {
                am.setSpeakerphoneOn(false);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.clearCommunicationDevice();
                } else {
                    am.stopBluetoothSco();
                    am.setBluetoothScoOn(false);
                }
                am.setMode(AudioManager.MODE_NORMAL);
                if (focusRequest != null) am.abandonAudioFocusRequest(focusRequest);
            }
        } catch (Throwable ignored) {
        }
        LogBus.log(TAG, "音频引擎停止");
    }
}
