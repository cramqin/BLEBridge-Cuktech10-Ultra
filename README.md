# ⚡ BLEBridge — CUKTECH 10 Ultra GaN Charger (`AD1204`)

<p align="center">
  <b>Native Android BLE Controller, Live Telemetry Dashboard, Automation Hub &amp; TCP Gateway for the CUKTECH 10 Ultra 120W GaN Charger</b>
</p>

<p align="center">
  <a href="../../releases"><img src="https://img.shields.io/github/v/release/cramqin/BLEBridge-Cuktech10-Ultra?include_prereleases&amp;label=Release&amp;color=00E5FF" alt="Release" /></a>
  <img src="https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84?logo=android&amp;logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/Kotlin-2.2%2B-7F52FF?logo=kotlin&amp;logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Device-CUKTECH%2010%20Ultra%20(AD1204)-00E5FF" alt="Device" />
  <img src="https://img.shields.io/badge/100%25-Vibe%20Coded%20%E2%9C%A8-FF6900" alt="Vibe Coded" />
</p>

> [!NOTE]
> **✨ Vibe-Coded Disclaimer:** This entire native Android application was **vibe-coded** with AI pair-programming by combining and porting two awesome open-source repositories into a unified Kotlin + Jetpack Compose Android app. While it is tested daily on real **CUKTECH 10 Ultra (`AD1204`)** hardware, please review the code and use at your own discretion!

---

## 🙏 Acknowledgments & Heritage

This project brings full native Android control for the **CUKTECH 10 Ultra 120W GaN Charger (`njcuk.fitting.ad1204`, Product ID `0x660E`)** by combining and porting the work of two open-source repositories:

1. **[`kairui1108/cuktech-ble-server`](https://github.com/kairui1108/cuktech-ble-server)** *(and [`kairui1108/cuktech-ble-ha`](https://github.com/kairui1108/cuktech-ble-ha))* — Complete reverse-engineered Xiaomi MiOT BLE v5 + CUKTECH Extended v2 protocol implementation, 2-phase ECDH (`SECP256R1`) + `HKDF-SHA256` + `AES-CCM` authentication handshake, 8-byte port telemetry decoding, adaptive energy integration (`Wh`), and hardware SIID/PIID register specifications.
2. **[`woldphone/BLEBridge`](https://github.com/woldphone/BLEBridge)** — Resilient Android Foreground Service BLE-to-TCP network bridge architecture, sequential GATT coroutine task queue, automatic MTU 512 negotiation, and GATT cache recovery.

Huge thanks to the authors of both repositories for making this native Android port possible!

---

## ⚡ Performance, Battery & Polling Architecture

Unlike typical smart home bridges that hammer Bluetooth channels or drain your phone battery, BLEBridge was engineered specifically around the AD1204U hardware's exact BLE characteristics:

- **0.0% CPU & Near-Zero Battery Drain on Idle:**
  - When no device is drawing power, the background service uses negligible CPU (`< 0.1%`) and draws virtually no extra battery over an open BLE connection.
  - **Silent Idle Telemetry Filter:** When a poll returns unchanged `0.0V 0.0A (Idle)` values, the app **silently drops redundant state emissions**. It does not re-compose UI components, trigger launcher widget redraws, fire Home Assistant webhooks, or flood the console log.
- **Smart Push + Adaptive Polling (Hardware-Aware):**
  - **USB-C1 & USB-C2:** The charger hardware automatically broadcasts spontaneous BLE push notifications (`b4 = 0x04`) the instant a cable is plugged in or wattage shifts. BLEBridge listens passively for C1/C2—only running a gentle `3s` backup poll when actively charging to catch unplugs, and **0 polls while idle**.
  - **USB-C3 & USB-A:** The charger firmware never broadcasts push notifications on C3/A. BLEBridge polls C3 and USB-A adaptively: every `5s` when all ports are idle (ensuring fast plug-in detection) and ramps up to `3s` whenever any port is active for smooth `Wh` energy integration.
- **Sequential Mutex Hardware Queue:**
  - Prevents Telink BLE controller deadlocks by funneling all GATT writes through a sequential, non-blocking coroutine queue with automatic 1.8s command timeouts.
- **Widget Battery Optimization:**
  - The Android Home Screen Widgets declare `updatePeriodMillis="0"` (zero alarm-based wakeups). They update reactively only when live state actually changes and are automatically paused by Android when your screen is off.

---

## ⚠️ Prerequisites & Xiaomi Mi Home Requirements

Before using **BLEBridge – CUKTECH 10 Ultra**, please note the following hardware and account requirements:

1. **Initial Device Pairing (Mi Home App Required Once):**
   - Your CUKTECH 10 Ultra **must first be added/bound to your Xiaomi account using the official Xiaomi Mi Home (米家) app**.
   - During initial binding, Xiaomi generates the unique **12-byte MiOT BLE Token** required for Phase B encrypted BLE authentication.
2. **Mainland China (`CN`) Region Required:**
   - The CUKTECH 10 Ultra (`njcuk.fitting.ad1204`) is registered under Xiaomi's **Mainland China (`cn`)** cloud server.
   - In the Xiaomi Mi Home app, your region **must be set to Mainland China (中国大陆)** when adding the charger.
3. **Exclusive BLE Connection (One App at a Time):**
   - The charger's Bluetooth controller only accepts **one active GATT connection at a time**. Do not keep the Mi Home app open in the foreground while BLEBridge is connected (and vice versa).
4. **Firmware Updates:**
   - Official OTA firmware updates for the charger must be performed through the **Xiaomi Mi Home** app. BLEBridge includes a 1-tap **"Open in Xiaomi Mi Home"** button that cleanly releases the Bluetooth handle and launches Mi Home for firmware checks.
5. **Background Keepalive & OEM Battery Optimization ([dontkillmyapp.com](https://dontkillmyapp.com/)):**
   - To keep live BLE telemetry, widgets, and background automations running reliably when your screen is off, tap **"Ignore Battery Optimization"** inside the app's **Background Keepalive & System Integration** card.
   - Aggressive Android OEMs (Xiaomi HyperOS / MIUI, Samsung OneUI, OPPO ColorOS, Vivo OriginOS, Huawei, etc.) often kill background services even with standard Android battery whitelist enabled. Tap the **"OEM Background Guide (dontkillmyapp.com)"** button in the app or visit **[https://dontkillmyapp.com/](https://dontkillmyapp.com/)** to configure Autostart and set Battery Saver to *No Restrictions*.
6. **Invisible in Standard Android System Bluetooth Settings (Normal Behavior):**
   - **You will NOT see the CUKTECH 10 Ultra in your phone's standard Android Bluetooth settings menu**, nor can you pair to it from Android's system Bluetooth scanner.
   - This is because the charger broadcasts nameless, encrypted Xiaomi MiOT `0xFE95` BLE service advertisements (`Product ID 0x660E`) rather than classic Bluetooth or standard HID/audio profiles.
   - **BLEBridge's built-in scanner specifically decodes `0xFE95` advertisements** and matches the embedded MAC/Product ID against your synced Xiaomi Cloud credentials—so even though it's invisible in your phone's Bluetooth settings, **it will appear and connect right inside BLEBridge!**

---

## 📊 Comparison: BLEBridge vs. Official Xiaomi Mi Home App

| Feature / Capability | **BLEBridge (This App)** | **Xiaomi Mi Home App** |
| :--- | :---: | :---: |
| **Initial Charger Binding & Cloud Registration** | ❌ *(Requires Mi Home once)* | ✅ **Yes** |
| **Official Charger OTA Firmware Updates** | ❌ *(Hands off to Mi Home)* | ✅ **Yes** |
| **100% Local Offline BLE Control (After Token Sync)** | ✅ **Yes (Zero cloud dependency)** | ❌ *(Requires internet & cloud)* |
| **Connection & Startup Speed** | ⚡ **Instant (~1.5s native GATT)** | 🐢 Slow (Heavy plugin loading) |
| **Persistent Background Keepalive & Live Notification** | ✅ **Yes (24/7 foreground service)** | ❌ Disconnects when screen locks |
| **Interactive Home Screen Widgets (`4x2` & `2x2`)** | ✅ **Yes (Soft Glass & Solid Dark)** | ❌ None |
| **Hardware Visual Panel (Interactive Port Jump)** | ✅ **Yes (Tap port graphic → jumps to port settings)** | ⚠️ Non-interactive graphic |
| **Per-Port Live Telemetry (`V`, `A`, `W`, Fast-Charge Protocol)** | ✅ **Yes (Real-time graphs & cards)** | ✅ Yes |
| **1-Tap Individual Port Power Toggle (`C1`, `C2`, `C3`, `USB-A`)** | ✅ **Yes (App, Widget, Tasker, ADB)** | ✅ Yes |
| **Power Allocation Scenarios (`AI`, `Hybrid`, `Single`, `Dual`)** | ✅ **Yes (Dialog, Widget, Tasker)** | ✅ Yes |
| **Per-Port Fast-Charge Protocol Switches (`PD`, `PPS`, `UFCS`, `SCP`)** | ✅ **Yes (Individual port control)** | ✅ Yes |
| **Port Auto-Off Countdown Timer (`30m` – `10h`) with Live Ticker** | ✅ **Yes (Second-by-second countdown)** | ⚠️ Basic timer only |
| **Per-Port Energy Cutoff Limit (`Wh` Auto-Shutoff: Once / Always)** | ✅ **Yes (Automatic port cutoff)** | ❌ None |
| **Charging Session History, Peak Wattage & `Wh` Energy Integrator** | ✅ **Yes (With CSV export & charts)** | ⚠️ Limited cloud chart |
| **Tasker / Locale / MacroDroid Native Plugin** | ✅ **Yes (Full UI action plugin)** | ❌ None |
| **ADB / Termux Broadcast Intent API** | ✅ **Yes** | ❌ None |
| **Home Assistant HTTP Webhook Push Integration** | ✅ **Yes** | ❌ None |
| **Raw TCP-to-BLE Socket Gateway (`port 8080`)** | ✅ **Yes** | ❌ None |
| **Built-in Console Log with Filtering & Live Copy** | ✅ **Yes (1,000-message rolling buffer)** | ❌ None |
| **Manual Bluetooth Stack & MiOT Handshake Diagnostics** | ✅ **Yes (On-demand stack audit)** | ❌ None |

---

## ✨ Key Features & UX Highlights

### 1. 🎨 Interactive Mi Home Style Charger Representation & Navigation
- **Hardware Visual Panel (`CuktechChargerVisual`):** A custom-drawn, dark obsidian graphic of the physical CUKTECH 10 Ultra charger right on the Live tab.
- **Deep-Linked Interactive Ports:** Tapping any port on the visual graphic (`USB-C1`, `USB-C2`, `USB-C3`, or `USB-A`) automatically animates swiping over to the **Ports tab**, scrolls smoothly to bring that specific port card into focus, and briefly highlights it with a glowing border so you can instantly toggle its power or fast-charge protocols.

### 2. 🔐 Native Xiaomi Cloud Sync & MiOT v5 + CUKTECH v2 BLE Engine
- **Xiaomi Cloud Login (QR Code & Password):** Sign in once to automatically fetch your charger's MAC address, device name, and **12-byte MiOT Token** from Xiaomi Cloud (`cn` region prioritized, with multi-region fallback).
- **Full 2-Phase Cryptographic Handshake:**
  - **Phase A (`0xA4`):** Exchanges capabilities on `CHAR_AUTH_CTRL` (`00000010`) and `CHAR_AUTH_DATA` (`00000005`).
  - **Phase B (`0x1500`):** Performs ephemeral `SECP256R1` ECDH key agreement, derives session keys via `HKDF-SHA256` bound to your 12-byte MiOT Token, and verifies mutual `AES-CCM` challenges.

### 3. 📱 Live Telemetry Dashboard (`Live`, `Ports`, `History`, `Settings`)
- **Live Tab:**
  - Total power hero card with Peak Session Wattage, accumulated Energy (`Wh`), RSSI signal quality, and an expandable **Power Allocation Scenarios** dialog (`AI Dynamic`, `Hybrid`, `Single Device Priority`, `Dual High-Power`).
  - Real-time multi-line **Power Curve Chart** (`Total`, `USB-C1`, `USB-C2`, `USB-C3`, `USB-A`).
  - **4 Individual Port Cards:** Live Voltage (`V`), Current (`A`), Power (`W`), negotiated protocol badge (`PD3.0`, `PPS`, `MiPPS`, `UFCS`, `SCP`, `QC3.0`), 1-tap port ON/OFF switch, **Energy Limit (`Wh`)**, and **Closure Countdown Timer** with a live second-by-second countdown badge.
- **Ports Tab:**
  - **Per-Port Fast-Charge Protocol Switches:** Independently toggle **PD (up to 100W)**, **PPS (Programmable Power Supply)**, **UFCS (Fusion Fast Charging)**, and **Huawei SCP (22.5W)** per port (`PIID 20–23`).
  - **USB-A Low Current Mode (4h):** Keep USB-A awake for low-draw wearables, earbuds, and fitness bands (`PIID 18`).
- **History Tab:**
  - Tracks active and completed charging sessions per port with duration, average/peak wattage, and integrated `Wh` energy.
  - Export session history to **CSV** (`24h`, `7d`, `30d`, `All Time`) or clear history while keeping active sessions intact.
- **Settings Tab:**
  - **Charger Hardware Controls:** Screen Brightness (`Low 20%`, `Medium 60%`, `High 100%`), Screen Timeout (`Always On`, `1 min`, `5 min`, `10 min`, `30 min`), **Idle Screen-Off** (`PIID 19`), Physical Screen Language (`Chinese` / `English`), and **Force Soft Port Reset** (`PIID 25`) to re-negotiate USB-PD contracts.
  - **6 Dark Theme Accent Palettes:** `CUKTECH Cyan`, `HyperCharge Blue`, `Xiaomi Orange`, `Emerald Matrix`, `Royal Amethyst`, and `Monochrome Slate`.

### 4. 🪟 Home Screen Widgets (HyperOS Soft Glass & Solid Dark)
Includes **4 native home screen widgets** right in your Android Widget Picker:
- **`CUKTECH 10 · 4x2 Soft Glass`** & **`CUKTECH 10 · 4x2 Solid Dark`**:
  - Full control center showing connection status, total wattage, scenario pill (tap to cycle `AI Mode` → `Hybrid` → `Single` → `Dual`), and 4 interactive port capsules (tap any port capsule to toggle power ON/OFF).
- **`CUKTECH 10 · 2x2 Soft Glass`** & **`CUKTECH 10 · 2x2 Solid Dark`**:
  - Compact square tile with total wattage, a **live glowing cyan sparkline graph** (automatically appears when charging `> 0.2W` and hides cleanly when idle), and an uncompressed 2×2 port wattage grid.

### 5. 🖥️ Console Log & Bluetooth Stack Diagnostics
- **Dual-Tab Terminal Card on Gateway Screen:**
  - **Tab 0 (Console Log):** 1,000-message scroll-persisted rolling buffer with 3 verbosity filters (`LEVEL 1: Errors`, `LEVEL 2: Connections & Status`, `LEVEL 3: Full Raw Telemetry`), 1-tap Copy to Clipboard, and Manual Clear.
  - **Tab 1 (BLE Diagnostics):** Fully manual, on-demand hardware diagnostics that audit phone Bluetooth capabilities, BLE advertising/scanning status, MiOT service UUID checks, active connection parameters, and battery whitelist keepalive.

### 6. 🤖 Automation: Tasker Plugin, ADB Intents & Home Assistant Webhook
- **Native Tasker / Locale / MacroDroid Plugin (`TaskerEditActivity` & `TaskerFireReceiver`):**
  - Configure automated actions directly inside Tasker's UI with zero scripting:
    1. Connect to Charger
    2. Disconnect Charger
    3. Toggle Port Power (`USB-C1`, `USB-C2`, `USB-C3`, `USB-A` ON/OFF)
    4. Set Power Allocation Scenario (`AI Mode`, `Hybrid`, `Single`, `Dual`)
    5. Set Charger Screen Timeout (`Always On`, `1m`, `5m`, `10m`, `30m`)
    6. Set Idle Screen-Off (Sleep LCD when all ports are idle)
    7. Set USB-A Low Current Mode (4-hour wearable charge)
    8. Set Port Delay Power-Off Countdown Timer (`0` to `600` minutes)
    9. Set Port Energy Cutoff Limit (`Wh`)
    10. Force Soft Port Reset (Re-negotiate all ports)
- **Home Assistant HTTP Webhook:** Push real-time JSON telemetry (`total_watts`, per-port `V`/`A`/`W`/`protocol`, `rssi`) to any Home Assistant webhook endpoint (`http://<ha-ip>:8123/api/webhook/<id>`).
- **ADB / Termux Broadcast Intents (`com.example.BleCommandReceiver`):**
  ```bash
  # Start server & connect to charger
  am broadcast -a com.cramqin.blecuktech10.CONNECT_CHARGER -n com.cramqin.blecuktech10/com.example.BleCommandReceiver

  # Turn USB-C1 OFF (port=1, enabled=false)
  am broadcast -a com.cramqin.blecuktech10.SET_PORT_STATE --ei port 1 --ez enabled false -n com.cramqin.blecuktech10/com.example.BleCommandReceiver

  # Switch to Hybrid Scenario (mode=2)
  am broadcast -a com.cramqin.blecuktech10.SET_SCENE_MODE --ei mode 2 -n com.cramqin.blecuktech10/com.example.BleCommandReceiver
  ```

---

## 📦 Installation & Building

### Download Pre-built Signed APK
Grab the latest signed `.apk` directly from the [**Releases**](../../releases) page and install it on any Android 8.0+ (`API 26+`) device.

### Build from Source
```bash
git clone https://github.com/cramqin/BLEBridge-Cuktech10-Ultra.git
cd BLEBridge-Cuktech10-Ultra
./gradlew assembleDebug
```

---

## 📄 License & Disclaimer

This project is an independent open-source community tool and is **not** affiliated with, endorsed by, or sponsored by CUKTECH or Xiaomi Corporation. Use at your own risk.
