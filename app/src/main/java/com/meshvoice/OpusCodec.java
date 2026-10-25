package com.meshvoice;

import android.media.MediaCodec;
import android.media.MediaFormat;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Thin synchronous wrapper over the platform Opus codec (API 29+ ships
 * c2.android.opus.encoder / decoder). 20 ms @ 16 kHz mono.
 */
public final class OpusCodec {
    private static final String MIME = "audio/opus";

    private final MediaCodec codec;
    private final boolean encoder;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private boolean released;

    private OpusCodec(MediaCodec codec, boolean encoder) {
        this.codec = codec;
        this.encoder = encoder;
    }

    public static OpusCodec createEncoder() throws IOException {
        MediaFormat f = MediaFormat.createAudioFormat(MIME, Protocol.SAMPLE_RATE, 1);
        f.setInteger(MediaFormat.KEY_BIT_RATE, Protocol.BITRATE);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Protocol.FRAME_SAMPLES * 2);
        MediaCodec c = MediaCodec.createEncoderByType(MIME);
        c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        c.start();
        return new OpusCodec(c, true);
    }

    public static OpusCodec createDecoder() throws IOException {
        MediaFormat f = MediaFormat.createAudioFormat(MIME, Protocol.SAMPLE_RATE, 1);
        MediaCodec c = MediaCodec.createDecoderByType(MIME);
        c.configure(f, null, null, 0);
        c.start();
        return new OpusCodec(c, false);
    }

    /** Encodes exactly one 20 ms frame; returns null when no output is ready yet. */
    public synchronized byte[] encode(short[] pcm, long ptsUs) {
        if (released) return null;
        try {
            int in = codec.dequeueInputBuffer(4000);
            if (in >= 0) {
                ByteBuffer ib = codec.getInputBuffer(in);
                if (ib != null) {
                    ib.clear();
                    ib.asShortBuffer().put(pcm);
                    codec.queueInputBuffer(in, 0, pcm.length * 2, ptsUs, 0);
                }
            }
            for (int attempt = 0; attempt < 4; attempt++) {
                int out = codec.dequeueOutputBuffer(info, 2000);
                if (out >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(out);
                    byte[] b = new byte[info.size];
                    if (ob != null) {
                        ob.position(info.offset);
                        ob.get(b, 0, info.size);
                    }
                    codec.releaseOutputBuffer(out, false);
                    return b;
                }
                if (out == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                // INFO_OUTPUT_FORMAT_CHANGED / buffers: keep draining
            }
        } catch (Throwable t) {
            LogBus.log("OPUS", "encode: " + t);
        }
        return null;
    }

    /** Decodes one Opus packet; returns null when no PCM is ready. */
    public synchronized short[] decode(byte[] data, long ptsUs) {
        if (released) return null;
        try {
            int in = codec.dequeueInputBuffer(4000);
            if (in < 0) return null;
            ByteBuffer ib = codec.getInputBuffer(in);
            if (ib == null) return null;
            ib.clear();
            ib.put(data);
            codec.queueInputBuffer(in, 0, data.length, ptsUs, 0);

            for (int attempt = 0; attempt < 4; attempt++) {
                int out = codec.dequeueOutputBuffer(info, 2000);
                if (out >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(out);
                    short[] pcm = new short[info.size / 2];
                    if (ob != null) {
                        ob.position(info.offset);
                        ob.asShortBuffer().get(pcm);
                    }
                    codec.releaseOutputBuffer(out, false);
                    return pcm;
                }
                if (out == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            }
        } catch (Throwable t) {
            LogBus.log("OPUS", "decode: " + t);
        }
        return null;
    }

    public synchronized void release() {
        if (released) return;
        released = true;
        try {
            codec.stop();
        } catch (Throwable ignored) {
        }
        try {
            codec.release();
        } catch (Throwable ignored) {
        }
    }
}
