package com.meshvoice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Random;

/** Owns the mesh transport + audio engine and keeps them alive in the background. */
public class MeshService extends Service implements BleManager.Callback {
    public static final String ACTION_START = "com.meshvoice.action.START";
    public static final String ACTION_STOP = "com.meshvoice.action.STOP";
    public static final String EXTRA_GROUP = "group";

    public static final String BC_LOG = "com.meshvoice.event.LOG";
    public static final String BC_STATUS = "com.meshvoice.event.STATUS";
    public static final String EXTRA_LINE = "line";
    public static final String EXTRA_PEERS = "peers";
    public static final String EXTRA_SID = "sid";
    public static final String EXTRA_CODEC = "codec";

    private static final String CHANNEL_ID = "meshvoice";
    private static final int NOTIF_ID = 1001;
    private static final String PREFS = "meshvoice";

    private BleManager ble;
    private AudioEngine audio;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;
    private LogBus.Listener logListener;

    private int mySid;
    private byte[] groupHash;
    private int mediaSeq;
    private int ctrlSeq;
    private String groupName = "mesh";
    private volatile boolean running;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        logListener = line -> {
            Intent i = new Intent(BC_LOG);
            i.setPackage(getPackageName());
            i.putExtra(EXTRA_LINE, line);
            sendBroadcast(i);
        };
        LogBus.add(logListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String group = intent != null ? intent.getStringExtra(EXTRA_GROUP) : null;
        if (group == null || group.trim().isEmpty()) group = "mesh";
        groupName = group.trim();

        startForegroundCompat();
        if (!running) startMesh();
        return START_NOT_STICKY;
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "MeshVoice", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("MeshVoice 全互联对讲")
                .setContentText("组网中…")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void startMesh() {
        running = true;
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        mySid = sp.getInt("sid", 0);
        if (mySid <= 0 || mySid >= 0xFFFF) {
            mySid = 1 + new Random().nextInt(0xFFFE);
            sp.edit().putInt("sid", mySid).apply();
        }
        groupHash = sha256(groupName);
        LogBus.log("SVC", "启动 sid=" + mySid + " 组=" + groupName);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeshVoice:mesh");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();

        ble = new BleManager(this, mySid, groupHash, this);
        audio = new AudioEngine(this, this::sendMedia);
        ble.start();
        audio.start();
        handler.postDelayed(hbTask, 500);
        handler.postDelayed(plTask, 1000);
        publishStatus();
    }

    private final Runnable hbTask = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            Packet p = new Packet();
            p.type = Protocol.TYPE_HEARTBEAT;
            p.ttl = 1;
            p.src = mySid;
            p.dst = Protocol.BROADCAST;
            p.seq = ctrlSeq++;
            p.ts = (int) (SystemClock.elapsedRealtime() & 0xFFFF);
            ble.broadcast(p.encode());
            handler.postDelayed(this, 500);
        }
    };

    private final Runnable plTask = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            List<Integer> sids = ble.peerSids();
            Packet p = new Packet();
            p.type = Protocol.TYPE_PEER_LIST;
            p.ttl = 1;
            p.src = mySid;
            p.dst = Protocol.BROADCAST;
            p.seq = ctrlSeq++;
            p.payload = new byte[sids.size() * 2];
            for (int i = 0; i < sids.size(); i++) {
                p.payload[i * 2] = (byte) (sids.get(i) >> 8);
                p.payload[i * 2 + 1] = (byte) (int) sids.get(i);
            }
            ble.broadcast(p.encode());
            handler.postDelayed(this, 1000);
        }
    };

    private void sendMedia(byte[] opus) {
        if (!running || opus == null || ble == null) return;
        Packet p = new Packet();
        p.type = Protocol.TYPE_MEDIA;
        p.ttl = 1;
        p.src = mySid;
        p.dst = Protocol.BROADCAST;
        p.seq = mediaSeq++;
        p.ts = (int) (SystemClock.elapsedRealtime() & 0xFFFF);
        p.payload = opus;
        ble.broadcast(p.encode());
    }

    @Override
    public void onPacket(Packet p) {
        if (p == null) return;
        // Relay path: a packet addressed to somebody else, TTL left, one hop max.
        if (p.dst != Protocol.BROADCAST && p.dst != mySid) {
            if (p.ttl > 1) {
                p.ttl--;
                p.flags |= Protocol.FLAG_RELAYED;
                ble.send(p.dst, p.encode());
            }
            return;
        }
        switch (p.type) {
            case Protocol.TYPE_MEDIA:
                if (audio != null) audio.onMedia(p.src, p.seq, p.payload);
                break;
            case Protocol.TYPE_JOIN_REQ: {
                Packet a = new Packet();
                a.type = Protocol.TYPE_JOIN_ACK;
                a.ttl = 1;
                a.src = mySid;
                a.dst = p.src;
                a.seq = ctrlSeq++;
                List<Integer> sids = ble.peerSids();
                a.payload = new byte[sids.size() * 2];
                for (int i = 0; i < sids.size(); i++) {
                    a.payload[i * 2] = (byte) (sids.get(i) >> 8);
                    a.payload[i * 2 + 1] = (byte) (int) sids.get(i);
                }
                ble.send(p.src, a.encode());
                break;
            }
            case Protocol.TYPE_LEAVE:
                LogBus.log("SVC", "节点离开 sid=" + p.src);
                break;
            default:
                break;
        }
    }

    @Override
    public void onPeerUp(int sid) {
        LogBus.log("SVC", "上线 sid=" + sid);
        if (audio != null) audio.ensureSource(sid);
        publishStatus();
    }

    @Override
    public void onPeerDown(int sid) {
        LogBus.log("SVC", "下线 sid=" + sid);
        if (audio != null) audio.removeSource(sid);
        publishStatus();
    }

    private void publishStatus() {
        int peers = ble != null ? ble.peerCount() : 0;
        boolean codec = audio != null && audio.isCodecOk();
        Intent i = new Intent(BC_STATUS);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_PEERS, peers);
        i.putExtra(EXTRA_SID, mySid);
        i.putExtra(EXTRA_CODEC, codec);
        sendBroadcast(i);
        updateNotification(peers);
    }

    private void updateNotification(int peers) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("MeshVoice 全互联对讲")
                .setContentText("组 \"" + groupName + "\" · 已连接 " + peers + " 人")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
        nm.notify(NOTIF_ID, n);
    }

    private static byte[] sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return new byte[]{h[0], h[1], h[2]};
        } catch (Throwable t) {
            return new byte[]{1, 2, 3};
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (handler != null) {
                handler.removeCallbacksAndMessages(null);
            }
            if (ble != null) ble.stop();
            if (audio != null) audio.stop();
        } catch (Throwable t) {
            LogBus.log("SVC", "停止异常: " + t);
        }
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
        LogBus.remove(logListener);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
