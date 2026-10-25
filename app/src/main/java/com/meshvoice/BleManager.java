package com.meshvoice;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full-mesh BLE transport.
 *
 * Every node advertises the same group hash, scans, and keeps a GATT link to
 * every other in-range member. Role per pair is deterministic: the smaller sid
 * is the GATT central, the larger one is the peripheral, so no two nodes dial
 * each other at the same time.
 */
public final class BleManager {
    public interface Callback {
        void onPacket(Packet p);

        void onPeerUp(int sid);

        void onPeerDown(int sid);
    }

    private static final String TAG = "BLE";
    private static final long PEER_TIMEOUT_MS = 3500;
    private static final long CONNECT_TIMEOUT_MS = 8000;
    private static final long WRITE_TIMEOUT_MS = 200;
    private static final long FALLBACK_CONNECT_MS = 10000;

    private final Context ctx;
    private final int mySid;
    private final byte[] groupHash;
    private final Callback cb;
    private final BluetoothManager bm;
    private final BluetoothAdapter adapter;

    private final HandlerThread ht = new HandlerThread("ble-loop");
    private volatile Handler h;
    private volatile boolean running;

    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private BluetoothGattServer server;
    private BluetoothGattCharacteristic serverTx;

    private final Map<Integer, Peer> peers = new ConcurrentHashMap<>();
    private final Map<String, Integer> addrToSid = new ConcurrentHashMap<>();
    private final Map<String, Peer> serverLinks = new ConcurrentHashMap<>();

    private long lastTimeoutCheck;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                onTick();
            } catch (Throwable t) {
                LogBus.log(TAG, "tick: " + t);
            }
            Handler handler = h;
            if (handler != null) handler.postDelayed(this, 500);
        }
    };

    private static final class Peer {
        int sid = -1;
        String addr;
        BluetoothDevice dev;
        BluetoothGatt gatt;
        BluetoothGattCharacteristic rx;
        BluetoothGattCharacteristic tx;
        BluetoothDevice serverDev;
        volatile boolean ready;
        volatile boolean subscribed;
        boolean connecting;
        long lastSeen;
        long nextAttempt;
        long connectAt;
        long fallbackAt;
        int failures;
        boolean writeBusy;
        long writeAt;
        int mtu = 247;
        boolean discovered;
        long lastSizeLog;
    }

    public BleManager(Context ctx, int mySid, byte[] groupHash, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.mySid = mySid;
        this.groupHash = groupHash;
        this.cb = cb;
        this.bm = (BluetoothManager) this.ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm != null ? bm.getAdapter() : null;
    }

    public void start() {
        if (adapter == null) {
            LogBus.log(TAG, "本机没有蓝牙适配器");
            return;
        }
        try {
            if (!adapter.isEnabled()) {
                LogBus.log(TAG, "蓝牙未开启，请先打开蓝牙");
                return;
            }
        } catch (Throwable t) {
            LogBus.log(TAG, "读取蓝牙状态失败: " + t);
            return;
        }
        ht.start();
        h = new Handler(ht.getLooper());
        running = true;
        h.post(() -> {
            startServer();
            startAdvertising();
            startScan();
            h.postDelayed(ticker, 500);
        });
    }

    private byte[] advertisePayload() {
        byte[] d = new byte[7];
        d[0] = (byte) Protocol.VERSION;
        d[1] = groupHash[0];
        d[2] = groupHash[1];
        d[3] = groupHash[2];
        d[4] = (byte) (mySid >> 8);
        d[5] = (byte) mySid;
        d[6] = (byte) Protocol.ADV_FLAG_JOINABLE;
        return d;
    }

    private void startAdvertising() {
        try {
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                LogBus.log(TAG, "设备不支持 BLE 广播");
                return;
            }
            AdvertiseData data = new AdvertiseData.Builder()
                    .addManufacturerData(Protocol.COMPANY_ID, advertisePayload())
                    .setIncludeDeviceName(false)
                    .build();
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build();
            advertiser.startAdvertising(settings, data, advCallback);
        } catch (Throwable t) {
            LogBus.log(TAG, "启动广播失败: " + t);
        }
    }

    private void startScan() {
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                LogBus.log(TAG, "设备不支持 BLE 扫描");
                return;
            }
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(null, settings, scanCallback);
            LogBus.log(TAG, "开始扫描");
        } catch (Throwable t) {
            LogBus.log(TAG, "启动扫描失败: " + t);
        }
    }

    private void startServer() {
        try {
            server = bm.openGattServer(ctx, serverCallback);
            if (server == null) {
                LogBus.log(TAG, "GATT server 打开失败");
                return;
            }
            BluetoothGattService svc = new BluetoothGattService(
                    Protocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            BluetoothGattCharacteristic rx = new BluetoothGattCharacteristic(
                    Protocol.RX_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE);
            BluetoothGattCharacteristic tx = new BluetoothGattCharacteristic(
                    Protocol.TX_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0);
            tx.addDescriptor(new BluetoothGattDescriptor(
                    Protocol.CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ
                            | BluetoothGattDescriptor.PERMISSION_WRITE));
            svc.addCharacteristic(rx);
            svc.addCharacteristic(tx);
            server.addService(svc);
            serverTx = tx;
        } catch (Throwable t) {
            LogBus.log(TAG, "GATT server 初始化失败: " + t);
        }
    }

    private final AdvertiseCallback advCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            LogBus.log(TAG, "广播已启动, sid=" + mySid);
        }

        @Override
        public void onStartFailure(int errorCode) {
            LogBus.log(TAG, "广播失败, code=" + errorCode);
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            Handler handler = h;
            if (handler == null) return;
            handler.post(() -> handleScan(result));
        }

        @Override
        public void onScanFailed(int errorCode) {
            LogBus.log(TAG, "扫描失败, code=" + errorCode);
        }
    };

    private void handleScan(ScanResult result) {
        if (!running || result == null) return;
        BluetoothDevice dev = result.getDevice();
        ScanRecord rec = result.getScanRecord();
        if (dev == null || rec == null) return;
        byte[] md = rec.getManufacturerSpecificData(Protocol.COMPANY_ID);
        if (md == null || md.length < 7) return;
        if (md[0] != (byte) Protocol.VERSION) return;
        if (md[1] != groupHash[0] || md[2] != groupHash[1] || md[3] != groupHash[2]) return;
        int sid = ((md[4] & 0xFF) << 8) | (md[5] & 0xFF);
        if (sid == mySid) return;

        String addr = dev.getAddress();
        addrToSid.put(addr, sid);

        Peer p = peers.get(sid);
        if (p == null) {
            if (peers.size() >= Protocol.MAX_PEERS) return;
            p = new Peer();
            p.sid = sid;
            p.fallbackAt = SystemClock.elapsedRealtime() + FALLBACK_CONNECT_MS;
            p.dev = dev;
            p.addr = addr;
            peers.put(sid, p);
            LogBus.log(TAG, "发现节点 sid=" + sid + " " + addr);
        }
        if (p.dev == null) p.dev = dev;
        if (p.addr == null) p.addr = addr;

        if (p.gatt == null && p.serverDev == null && !p.connecting
                && SystemClock.elapsedRealtime() > p.nextAttempt) {
            boolean smaller = mySid < sid;
            boolean fallback = SystemClock.elapsedRealtime() > p.fallbackAt;
            if (smaller || fallback) connectPeer(p);
        }
    }

    private void connectPeer(Peer p) {
        if (p.dev == null || p.connecting || !running) return;
        try {
            p.connecting = true;
            p.connectAt = SystemClock.elapsedRealtime();
            BluetoothGatt g = p.dev.connectGatt(ctx, false, clientCallback,
                    BluetoothDevice.TRANSPORT_LE);
            if (g == null) {
                p.connecting = false;
                p.nextAttempt = SystemClock.elapsedRealtime() + 2000;
                return;
            }
            p.gatt = g;
            LogBus.log(TAG, "连接 sid=" + p.sid + " ...");
        } catch (Throwable t) {
            p.connecting = false;
            p.nextAttempt = SystemClock.elapsedRealtime() + 2000;
            LogBus.log(TAG, "connectGatt 失败: " + t);
        }
    }

    private final BluetoothGattCallback clientCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Handler handler = h;
            if (handler == null) return;
            handler.post(() -> handleClientState(gatt, status, newState));
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Handler handler = h;
            if (handler == null) return;
            handler.post(() -> {
                Peer p = findPeer(gatt);
                if (p != null) {
                    p.mtu = mtu;
                    LogBus.log(TAG, "MTU=" + mtu + " sid=" + p.sid);
                    discover(p, gatt);
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Handler handler = h;
            if (handler == null) return;
            handler.post(() -> handleServices(gatt, status));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor d, int status) {
            Handler handler = h;
            if (handler == null) return;
            handler.post(() -> {
                Peer p = findPeer(gatt);
                if (p == null) return;
                if (Protocol.CCCD_UUID.equals(d.getUuid())
                        && status == BluetoothGatt.GATT_SUCCESS) {
                    p.ready = true;
                    p.connecting = false;
                    p.failures = 0;
                    p.lastSeen = SystemClock.elapsedRealtime();
                    LogBus.log(TAG, "节点上线 sid=" + p.sid);
                    cb.onPeerUp(p.sid);
                }
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic ch) {
            byte[] v = ch.getValue();
            if (v == null) return;
            Handler handler = h;
            if (handler == null) return;
            handler.post(() -> handleValue(gatt.getDevice(), v, false));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic ch, int status) {
            Handler handler = h;
            if (handler == null) return;
            handler.post(() -> {
                Peer p = findPeer(gatt);
                if (p != null) p.writeBusy = false;
            });
        }
    };

    private Peer findPeer(BluetoothGatt gatt) {
        for (Peer p : peers.values()) {
            if (p.gatt == gatt) return p;
        }
        return null;
    }

    private void handleClientState(BluetoothGatt gatt, int status, int newState) {
        Peer p = findPeer(gatt);
        if (p == null) {
            try {
                gatt.close();
            } catch (Throwable ignored) {
            }
            return;
        }
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            if (p.serverDev != null) {
                // Already linked as peripheral; drop the duplicate.
                try {
                    gatt.disconnect();
                } catch (Throwable ignored) {
                }
                return;
            }
            p.lastSeen = SystemClock.elapsedRealtime();
            try {
                gatt.requestMtu(517);
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
            } catch (Throwable ignored) {
            }
            // Some stacks never deliver onMtuChanged; make sure discovery still happens.
            try {
                h.postDelayed(() -> {
                    Peer pp = findPeer(gatt);
                    if (pp != null) discover(pp, gatt);
                }, 1200);
            } catch (Throwable ignored) {
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    gatt.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK,
                            BluetoothDevice.PHY_LE_2M_MASK,
                            BluetoothDevice.PHY_OPTION_NO_PREFERRED);
                }
            } catch (Throwable ignored) {
            }
            LogBus.log(TAG, "已连接 sid=" + p.sid);
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            boolean wasReady = p.ready;
            cleanupClient(p);
            p.failures++;
            long backoff = Math.min(8000L, 1000L << Math.min(3, p.failures - 1));
            p.nextAttempt = SystemClock.elapsedRealtime() + backoff;
            LogBus.log(TAG, "断开 sid=" + p.sid + " status=" + status + " 退避 " + backoff + "ms");
            if (wasReady) cb.onPeerDown(p.sid);
        }
    }

    private void discover(Peer p, BluetoothGatt gatt) {
        if (p == null || p.discovered) return;
        p.discovered = true;
        try {
            if (!gatt.discoverServices()) {
                p.discovered = false;
            }
        } catch (Throwable t) {
            p.discovered = false;
        }
    }

    private void handleServices(BluetoothGatt gatt, int status) {
        Peer p = findPeer(gatt);
        if (p == null) return;
        p.discovered = true;
        try {
            BluetoothGattService svc = gatt.getService(Protocol.SERVICE_UUID);
            if (svc == null) {
                LogBus.log(TAG, "未找到服务 sid=" + p.sid);
                gatt.disconnect();
                return;
            }
            p.tx = svc.getCharacteristic(Protocol.TX_UUID);
            p.rx = svc.getCharacteristic(Protocol.RX_UUID);
            if (p.tx == null || p.rx == null) {
                LogBus.log(TAG, "特征缺失 sid=" + p.sid);
                gatt.disconnect();
                return;
            }
            gatt.setCharacteristicNotification(p.tx, true);
            BluetoothGattDescriptor cccd = p.tx.getDescriptor(Protocol.CCCD_UUID);
            if (cccd == null) {
                LogBus.log(TAG, "CCCD 缺失 sid=" + p.sid);
                gatt.disconnect();
                return;
            }
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(cccd);
        } catch (Throwable t) {
            LogBus.log(TAG, "发现服务失败: " + t);
        }
    }

    private void cleanupClient(Peer p) {
        BluetoothGatt g = p.gatt;
        p.gatt = null;
        p.rx = null;
        p.tx = null;
        p.ready = false;
        p.connecting = false;
        p.writeBusy = false;
        p.discovered = false;
        if (g != null) {
            try {
                g.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private final BluetoothGattServerCallback serverCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            Handler handler = h;
            if (handler == null) return;
            handler.post(() -> {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Integer sid = addrToSid.get(device.getAddress());
                    if (sid != null) {
                        Peer p = peers.get(sid);
                        if (p != null && p.gatt != null) {
                            try {
                                server.cancelConnection(device);
                            } catch (Throwable ignored) {
                            }
                            return;
                        }
                        if (p != null) {
                            p.serverDev = device;
                            p.addr = device.getAddress();
                        }
                    }
                    LogBus.log(TAG, "被动连接 " + device.getAddress());
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Peer p = serverLinks.remove(device.getAddress());
                    if (p != null) {
                        boolean wasReady = p.ready;
                        p.serverDev = null;
                        p.subscribed = false;
                        p.ready = false;
                        if (wasReady) cb.onPeerDown(p.sid);
                    }
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            LogBus.log(TAG, "server MTU=" + mtu + " " + device.getAddress());
            Integer sid = addrToSid.get(device.getAddress());
            if (sid != null) {
                Peer p = peers.get(sid);
                if (p != null) p.mtu = mtu;
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
            Handler handler = h;
            if (handler == null) return;
            handler.post(() -> {
                if (server != null && responseNeeded) {
                    try {
                        server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                    } catch (Throwable ignored) {
                    }
                }
                if (Protocol.CCCD_UUID.equals(descriptor.getUuid())) {
                    Integer sid = addrToSid.get(device.getAddress());
                    if (sid != null) {
                        Peer p = peers.get(sid);
                        if (p != null) {
                            p.serverDev = device;
                            p.subscribed = true;
                            p.lastSeen = SystemClock.elapsedRealtime();
                            if (!p.ready) {
                                p.ready = true;
                                LogBus.log(TAG, "节点上线(被动) sid=" + sid);
                                cb.onPeerUp(sid);
                            }
                            serverLinks.put(device.getAddress(), p);
                        }
                    }
                }
            });
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            if (server != null && responseNeeded) {
                try {
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                } catch (Throwable ignored) {
                }
            }
            if (!Protocol.RX_UUID.equals(characteristic.getUuid()) || value == null) return;
            Handler handler = h;
            if (handler == null) return;
            handler.post(() -> handleValue(device, value, true));
        }
    };

    /** Common inbound path for both link roles. */
    private void handleValue(BluetoothDevice device, byte[] value, boolean fromServer) {
        Packet p = Packet.parse(value);
        if (p == null) return;
        Peer peer = peers.get(p.src);
        if (peer == null) {
            if (peers.size() >= Protocol.MAX_PEERS) return;
            peer = new Peer();
            peer.sid = p.src;
            peer.addr = device.getAddress();
            peer.dev = device;
            peer.fallbackAt = SystemClock.elapsedRealtime() + FALLBACK_CONNECT_MS;
            peers.put(p.src, peer);
        }
        if (fromServer) {
            peer.serverDev = device;
            peer.addr = device.getAddress();
            addrToSid.put(device.getAddress(), p.src);
            serverLinks.put(device.getAddress(), peer);
            if (!peer.ready) {
                peer.subscribed = true;
                peer.ready = true;
                LogBus.log(TAG, "节点上线(数据) sid=" + p.src);
                cb.onPeerUp(p.src);
            }
        }
        peer.lastSeen = SystemClock.elapsedRealtime();
        cb.onPacket(p);
    }

    private void onTick() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastTimeoutCheck < 250) return;
        lastTimeoutCheck = now;
        for (Peer p : new ArrayList<>(peers.values())) {
            if (p.ready && now - p.lastSeen > PEER_TIMEOUT_MS) {
                LogBus.log(TAG, "心跳超时 sid=" + p.sid);
                boolean wasReady = p.ready;
                if (p.gatt != null) {
                    try {
                        p.gatt.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
                if (p.serverDev != null) {
                    try {
                        if (server != null) server.cancelConnection(p.serverDev);
                    } catch (Throwable ignored) {
                    }
                    serverLinks.remove(p.serverDev.getAddress());
                    p.serverDev = null;
                }
                p.ready = false;
                p.subscribed = false;
                p.failures++;
                p.nextAttempt = now + 2000;
                if (wasReady) cb.onPeerDown(p.sid);
            }
            if (p.connecting && p.gatt == null && now - p.connectAt > CONNECT_TIMEOUT_MS) {
                p.connecting = false;
                p.nextAttempt = now + 2000;
            }
            if (p.gatt == null && p.serverDev == null && !p.connecting
                    && p.dev != null && now > p.nextAttempt) {
                if (mySid < p.sid) {
                    connectPeer(p);
                }
            }
            if (p.writeBusy && now - p.writeAt > WRITE_TIMEOUT_MS) {
                p.writeBusy = false;
            }
        }
    }

    /** Fire-and-forget send. Media frames are dropped rather than queued. */
    public boolean send(int dstSid, byte[] data) {
        Peer p = peers.get(dstSid);
        if (p == null) return false;
        return sendToPeer(p, data);
    }

    public void broadcast(byte[] data) {
        for (Peer p : peers.values()) {
            if (p.ready) sendToPeer(p, data);
        }
    }

    private boolean sendToPeer(Peer p, byte[] data) {
        Handler handler = h;
        if (!running || handler == null) return false;
        if (data.length + 3 > p.mtu) {
            long now = SystemClock.elapsedRealtime();
            if (now - p.lastSizeLog > 5000) {
                p.lastSizeLog = now;
                LogBus.log(TAG, "包过大 " + data.length + " > mtu " + p.mtu + " sid=" + p.sid);
            }
            return false;
        }
        handler.post(() -> {
            if (!p.ready) return;
            try {
                if (p.gatt != null && p.rx != null) {
                    if (p.writeBusy) return;
                    p.writeBusy = true;
                    p.writeAt = SystemClock.elapsedRealtime();
                    p.rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                    p.rx.setValue(data);
                    if (!p.gatt.writeCharacteristic(p.rx)) p.writeBusy = false;
                } else if (p.serverDev != null && p.subscribed && server != null && serverTx != null) {
                    server.notifyCharacteristicChanged(p.serverDev, serverTx, false, data);
                }
            } catch (Throwable t) {
                p.writeBusy = false;
            }
        });
        return true;
    }

    public int peerCount() {
        int n = 0;
        for (Peer p : peers.values()) if (p.ready) n++;
        return n;
    }

    public List<Integer> peerSids() {
        List<Integer> out = new ArrayList<>();
        for (Peer p : peers.values()) if (p.ready) out.add(p.sid);
        return out;
    }

    public void stop() {
        if (!running) return;
        running = false;
        Handler handler = h;
        if (handler != null) {
            handler.post(() -> {
                try {
                    if (scanner != null) scanner.stopScan(scanCallback);
                } catch (Throwable ignored) {
                }
                try {
                    if (advertiser != null) advertiser.stopAdvertising(advCallback);
                } catch (Throwable ignored) {
                }
                for (Peer p : peers.values()) {
                    if (p.gatt != null) {
                        try {
                            p.gatt.disconnect();
                            p.gatt.close();
                        } catch (Throwable ignored) {
                        }
                    }
                    if (p.serverDev != null && server != null) {
                        try {
                            server.cancelConnection(p.serverDev);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                try {
                    if (server != null) server.close();
                } catch (Throwable ignored) {
                }
                peers.clear();
                ht.quitSafely();
            });
        }
    }
}
