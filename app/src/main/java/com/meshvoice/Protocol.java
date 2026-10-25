package com.meshvoice;

import java.util.UUID;

/** Wire protocol constants shared by every layer. */
public final class Protocol {
    // Custom 128-bit GATT service. Data is carried by RX (write) / TX (notify).
    public static final UUID SERVICE_UUID = UUID.fromString("6d657368-766f-6963-6500-000000000001");
    public static final UUID RX_UUID      = UUID.fromString("6d657368-766f-6963-6500-000000000002");
    public static final UUID TX_UUID      = UUID.fromString("6d657368-766f-6963-6500-000000000003");
    public static final UUID CCCD_UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final int VERSION = 1;
    public static final int HEADER_LEN = 12;

    // Control plane
    public static final int TYPE_JOIN_REQ  = 0x01;
    public static final int TYPE_JOIN_ACK  = 0x02;
    public static final int TYPE_PEER_LIST = 0x03;
    public static final int TYPE_HEARTBEAT = 0x04;
    public static final int TYPE_LEAVE     = 0x05;
    public static final int TYPE_QOS       = 0x09;

    // Media plane
    public static final int TYPE_MEDIA     = 0x20;
    public static final int TYPE_MEDIA_RED = 0x21;

    public static final int FLAG_RELAYED = 0x01;
    public static final int FLAG_FEC     = 0x02;
    public static final int FLAG_DTX     = 0x04;

    public static final int BROADCAST = 0xFFFF;
    public static final int MAX_PEERS = 4;

    // Audio
    public static final int SAMPLE_RATE   = 16000;
    public static final int FRAME_SAMPLES = 320;   // 20 ms
    public static final int BITRATE       = 24000;

    // Advertising
    public static final int COMPANY_ID = 0xFFFF;   // reserved-for-testing id, local demo only
    public static final int ADV_FLAG_JOINABLE = 0x01;

    private Protocol() {
    }
}
