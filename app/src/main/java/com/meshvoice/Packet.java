package com.meshvoice;

/**
 * Fixed 12-byte header:
 *   0 version | 1 type | 2 flags | 3 ttl
 *   4..5 src  | 6..7 dst | 8..9 seq | 10..11 ts(ms)
 * Header is plaintext so a relay can route on dst without decrypting.
 */
public final class Packet {
    public int type;
    public int flags;
    public int ttl;
    public int src;
    public int dst;
    public int seq;
    public int ts;
    public byte[] payload = new byte[0];

    public static Packet parse(byte[] d) {
        if (d == null || d.length < Protocol.HEADER_LEN) return null;
        if ((d[0] & 0xFF) != Protocol.VERSION) return null;
        Packet p = new Packet();
        p.type = d[1] & 0xFF;
        p.flags = d[2] & 0xFF;
        p.ttl = d[3] & 0xFF;
        p.src = ((d[4] & 0xFF) << 8) | (d[5] & 0xFF);
        p.dst = ((d[6] & 0xFF) << 8) | (d[7] & 0xFF);
        p.seq = ((d[8] & 0xFF) << 8) | (d[9] & 0xFF);
        p.ts = ((d[10] & 0xFF) << 8) | (d[11] & 0xFF);
        int n = d.length - Protocol.HEADER_LEN;
        p.payload = new byte[n];
        System.arraycopy(d, Protocol.HEADER_LEN, p.payload, 0, n);
        return p;
    }

    public byte[] encode() {
        byte[] out = new byte[Protocol.HEADER_LEN + payload.length];
        out[0] = (byte) Protocol.VERSION;
        out[1] = (byte) type;
        out[2] = (byte) flags;
        out[3] = (byte) ttl;
        out[4] = (byte) (src >> 8);
        out[5] = (byte) src;
        out[6] = (byte) (dst >> 8);
        out[7] = (byte) dst;
        out[8] = (byte) (seq >> 8);
        out[9] = (byte) seq;
        out[10] = (byte) (ts >> 8);
        out[11] = (byte) ts;
        System.arraycopy(payload, 0, out, Protocol.HEADER_LEN, payload.length);
        return out;
    }
}
