# MeshVoice 架构设计

本文描述当前实现的结构、状态机与协议；末尾列出设计与代码的差异（未实现部分）。

## 1. 目标与约束

| 约束 | 设计含义 |
|---|---|
| 3–5 人 | 完全图可行：最多 10 条链路，每台最多 4 条连接 |
| 单点掉线不影响他人 | 无中心、无链式中继、无 leader |
| 离线 | 纯 BLE，不使用 Wi-Fi / 蜂窝 |
| 音质接近聊天软件 | Opus 16 kHz / 24 kbps，抖动缓冲 + 丢包隐藏 |
| 耳机只做音频出入口 | 标准 HFP/SCO，不依赖厂商固件 |

## 2. 拓扑

```
A ─── B
│ ╲  ╱ │
│  ╳   │
│ ╱  ╲ │
C ─── D
```

- 每台设备与其余每台建立一条 GATT 连接。
- 5 台时共 10 条链路，每台 4 条。
- **没有 hub、没有 relay chain**：任何一条链路断开只影响该对节点。

### 角色分配

对任意一对 (A, B)：

- `A.sid < B.sid` → A 作为 GATT central 发起连接，B 作为 peripheral。
- 这样同一时刻只有一方 `connectGatt`，避免 Android 上双向连接冲突（status 133）。
- 若小 sid 一方连续失败，大 sid 一方在 10 s 后兜底发起；重复链路由服务端/客户端回调互相取消。

### 身份

```
sid = uint16(SHA-256(noise_static_pubkey)[0..1])
groupHash = SHA-256(groupName)[0..2]
```

- sid 首次启动随机生成并持久化（当前实现未使用公钥，直接随机 1..65534）。
- 组名哈希只用于过滤广播，不是安全边界。

## 3. 协议

### 3.1 包头（12 字节，明文）

```
0  version
1  type
2  flags
3  ttl
4..5  src (uint16)
6..7  dst (uint16, 0xFFFF = broadcast)
8..9  seq (uint16)
10..11 ts (uint16, ms)
```

明文头是刻意的：中继节点只需读 `dst` 即可转发，不需要解密载荷。

### 3.2 类型

| 值 | 名称 | 说明 |
|---|---|---|
| `0x01` | JOIN_REQ | 入组请求（预留） |
| `0x02` | JOIN_ACK | 成员列表（预留） |
| `0x03` | PEER_LIST | 周期性邻居列表，1 s |
| `0x04` | HEARTBEAT | 500 ms |
| `0x05` | LEAVE | 主动退出（预留） |
| `0x09` | QOS | 质量上报（预留） |
| `0x20` | MEDIA | Opus 帧 |
| `0x21` | MEDIA_RED | 冗余帧（预留） |

当前实际使用：`HEARTBEAT`、`PEER_LIST`、`MEDIA`、`JOIN_ACK`。

### 3.3 序号

- 媒体序号 `mediaSeq` 与信令序号 `ctrlSeq` **完全独立**。
- 若共用序号，500 ms 一次的心跳会在媒体流里制造空洞，抖动缓冲会周期性丢帧。

## 4. 连接状态机

### 4.1 节点

```
IDLE ──开始──▶ JOINING ──≥1 条链路──▶ ACTIVE
                    ▲                    │
                    │              0 条链路
                    └──── ISOLATED ◀─────┘
```

- `ACTIVE` 只要求至少一条链路，不要求全员在线。
- `ISOLATED` 仍持续广播与重连，不会解散。
- 没有全局「组状态」，每台设备只维护本地 peer 集合。

### 4.2 Peer

```
DISCOVERED ──connect──▶ CONNECTING ──▶ HANDSHAKE ──▶ ACTIVE
     ▲                       │              │            │
     │                    超时/失败      失败/超时     1.5 s 无包
     │                       ▼              ▼            ▼
     └──── BACKOFF ◀────── LOST ◀──────────┘         SUSPECT
              ▲                                          │
              └──────────── 3 s 无包 ───────────────────┘
```

| 定时器 | 值 |
|---|---|
| 连接超时 | 8 s |
| 心跳 | 500 ms |
| SUSPECT | 1.5 s |
| LOST | 3.5 s |
| 重连退避 | 1 / 2 / 4 / 8 s |

### 4.3 连接建立

1. 扫描到同组广播 → 记录 `addr → sid`，创建 Peer。
2. 小 sid 方 `connectGatt` → `requestMtu(517)` → `discoverServices`。
3. 找到 RX/TX 特征，订阅 TX 的 CCCD。
4. CCCD 写成功 → `ready=true`，通知上层 `onPeerUp`。
5. 对端服务端在 CCCD 回调或首个数据包到达时绑定 peer，标记 `subscribed`。

兜底：部分协议栈不回调 `onMtuChanged`，连接后 1.2 s 强制 `discoverServices`。

## 5. 断线场景

| 场景 | 检测 | 行为 | 他人影响 |
|---|---|---|---|
| 主动退出 | LEAVE / 断开 | 拆链、移出混音 | 少一路 |
| 关机 / 掉电 | 3.5 s 超时 | 拆链、退避重连 | 少一路 |
| 走出范围 | 超时 | 拆链、重连 | 仅该对 |
| 中继节点离开 | 不适用（未启用中继） | — | — |
| 重新靠近 | 广播 | 自动重连 | 自动恢复 |

核心保证：任意 1 台消失，其余 N−1 台之间的直连全部保留。

## 6. 音频管线

```
麦克风 ──▶ AudioRecord(16 kHz, mono, VOICE_COMMUNICATION)
        ──▶ Opus 编码（24 kbps, 20 ms）
        ──▶ 逐链路发送 MEDIA

MEDIA ──▶ 每源 Opus 解码 ──▶ JitterBuffer（2–4 帧）
      ──▶ 混音 + 削峰 ──▶ AudioTrack（USAGE_VOICE_COMMUNICATION）
```

- 采集/播放各一个线程；播放线程以 20 ms 为周期。
- 丢帧用上一帧衰减顶替（PLC），不重传。
- 耳机路由：`MODE_IN_COMMUNICATION` + `setCommunicationDevice(SCO)`；
  启动 1.5 s 后重试一次，兼容 SCO 延迟建立。
- 回声消除依赖耳机通话态自带的 AEC，App 侧再挂 `AcousticEchoCanceler` 兜底。

## 7. 为什么不用星形 / 链式

| 拓扑 | 一人掉线 | 时延 | 结论 |
|---|---|---|---|
| 星形（一人做 hub） | hub 走全组哑 | 1 跳 | 不满足要求 |
| 链式（逐跳转发） | 断一节全断 | 随跳数累加 | 不满足要求 |
| 完全图 | 其余人照常 | 1 跳 | 采用 |

3–5 人的规模完全图代价可接受：每台 4 条连接、约 230 kbps 空口（4 发 4 收）。

## 8. 设计预留但未实现

| 项 | 现状 | 说明 |
|---|---|---|
| 1 跳中继 | 转发路径已写，未启用 | 需要 RELAY_OFFER/ACK 与租约选举 |
| 加密 | 无 | 头明文，载荷可整体 AEAD |
| JOIN 握手 | 仅回 ACK | 未做入组认证 |
| 自适应码率 | 固定 24 kbps | 依赖 QOS_REPORT |
| PTT / 闪避 | 全双工固定混音 | 可按能量做 ducking |

## 9. 已知风险

1. SCO 与 4 条 BLE 连接共用控制器，不同 SoC 抖动差异大。
2. Android GATT 吞吐波动，弱机型可能掉到 50 kbps 以下。
3. 2.4 GHz 拥塞时先丢媒体而不是断链（有意为之）。
4. 厂商后台限制会直接杀死前台服务，需要用户白名单。
