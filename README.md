# MeshVoice



Offline, full-mesh voice intercom on Android: 3–5 phones connect directly to each other over BLE—no Wi‑Fi, cellular, or server needed. The Bluetooth headset is only used as the mic and speaker; the app handles all the networking and voice relaying.



## Screenshots
<img width="270" height="585" alt="screenshot" src="https://github.com/user-attachments/assets/0cfa17e5-a475-4ea5-bf79-beb8eceeda76" />


## Trait
- Full-mesh networking: every phone connects directly to every other phone—no central node, no group owner.
- One phone dropping doesn’t affect the rest: if any phone leaves, powers off, or goes out of range, voice keeps flowing between the remaining devices.
- Works fully offline: pure BLE—no internet, no account, no server.
- Full-duplex voice: 16 kHz mono, Opus at 24 kbps / 20 ms, about 150–250 ms end-to-end.
- Headsets just work: standard HFP/SCO headsets—no vendor app or custom firmware needed.
- Auto-reconnect: 500 ms heartbeat, 3.5 s to mark a device offline, then reconnect with 1/2/4/8 s backoff.
- Foreground service: keeps running on the lock screen, with a persistent notification showing how many people are currently connected.



## Build
```bash
git clone <your-repo-url>
cd MeshVoice

# Debug APK
./gradlew assembleDebug
# Output：app/build/outputs/apk/debug/app-debug.apk
```

On Windows, just use `gradlew.bat`instead.



## Other
- This project was built by AI—the original idea was to see if a Bluetooth headset + phone could replace a riding headset (like a motorcycle helmet headset). If you run into any problems, please open an issue.

- 哇哇哇，AI太好用了喵，冰冰冰
