# ⚡ Complete Features & Hardware Capabilities

Comprehensive reference of all features, hardware registers, protocols, and automation capabilities in **BLEBridge – CUKTECH 10 Ultra (`AD1204`)**.

---

## 📑 Table of Contents
- [1. Hardware Visual Representation & Deep Linking](#1-hardware-visual-representation--deep-linking)
- [2. Multi-Line Real-Time Power Curves & Graphs](#2-multi-line-real-time-power-curves--graphs)
- [3. Soft Port Reset (USB-PD Contract Re-negotiation)](#3-soft-port-reset-usb-pd-contract-re-negotiation)
- [4. Authentication & Xiaomi Cloud Sync Engine](#4-authentication--xiaomi-cloud-sync-engine)
- [5. Performance, Battery & Polling Architecture](#5-performance-battery--polling-architecture)
- [6. Live Telemetry Dashboard](#6-live-telemetry-dashboard)
- [7. Ports Control & Protocol Toggles](#7-ports-control--protocol-toggles)
- [8. Session Tracking, Wh Integrators & CSV Export](#8-session-tracking-wh-integrators--csv-export)
- [9. Charger Hardware Settings & Display Control](#9-charger-hardware-settings--display-control)
- [10. Home Screen Widgets (HyperOS 4 & Solid Dark)](#10-home-screen-widgets-hyperos-4--solid-dark)
- [11. Dual-Tab Terminal: Console Log & BLE Diagnostics](#11-dual-tab-terminal-console-log--ble-diagnostics)
- [12. Automation Hub: Tasker Plugin, Webhooks & ADB](#12-automation-hub-tasker-plugin-webhooks--adb)

---

## 1. Hardware Visual Representation & Deep Linking
- **Physical Charger Faceplate Graphic (`CuktechChargerVisual`):** A custom-drawn, dark obsidian panel on the **Live tab** mirroring the physical port arrangement of the CUKTECH 10 Ultra (`USB-C1`, `USB-C2`, `USB-C3`, `USB-A`).
- **Interactive Deep-Linked Jump:**
  - Tapping any port capsule on the graphic triggers an animated scroll to the **Ports tab**.
  - Automatically scrolls the target port into the viewport using `bringIntoViewRequester`.
  - Highlights the selected port card with a temporary glowing cyan border for 2 seconds.

---

## 2. Multi-Line Real-Time Power Curves & Graphs
- **Real-Time Rolling Canvas Graph:**
  - 60-second rolling history plotted via custom Canvas drawing.
  - Multi-line color-coded telemetry:
    - **Total Power:** High-visibility Cyan / White curve
    - **USB-C1:** Sky Blue
    - **USB-C2:** Purple / Indigo
    - **USB-C3:** Orange / Amber
    - **USB-A:** Emerald Green
  - Dynamic Y-axis auto-scaling up to 120W (or 100W on 100V–130V AC inputs).
  - Smooth Bezier cubic interpolation with translucent area fill under the curves.
- **Home Screen Widget Sparkline:**
  - The `2x2` compact widget features an embedded sparkline graph (`renderSparklineBitmap`) plotted from live power history.
  - Only renders when actively charging (`> 0.2W`); automatically hides when idle to eliminate flat lines.

---

## 3. Soft Port Reset (USB-PD Contract Re-negotiation)
- **Non-Destructive Protocol Power-Cycle (`PIID 25`):**
  - Sends a momentary power-switch cycle (`0x00 -> 0x0F`) to the physical DC-DC buck-boost converters.
  - Forces connected laptops, phones, and power banks to renegotiate fresh USB-PD, PPS, or UFCS contracts without physically unplugging cables or pulling the charger from the wall AC outlet.
  - Available via:
    - **Settings Tab:** "Re-negotiate Ports (Soft Reset)" button with safety confirmation dialog.
    - **Tasker Action:** `Force Soft Port Reset`.
    - **ADB Broadcast Intent:** `com.cramqin.blecuktech10.FORCE_SOFT_RESET`.

---

## 4. Authentication & Xiaomi Cloud Sync Engine
- **4 Flexible Login Modes:**
  1. **Google / Web OAuth:** In-app secure WebView for Google-linked Xiaomi accounts.
  2. **QR Code Scan:** Generate a Xiaomi Cloud QR session; scan with the official Mi Home app on another phone to sign in with zero typing.
  3. **Password Login with 2FA Challenge:** Native login supporting SMS OTP and Email two-factor verification codes.
  4. **Manual / JSON Token Import:** Directly paste MAC address, 12-byte MiOT Token, and 16-byte BLE Key offline without connecting to Xiaomi servers.
- **Multi-Device Credential Storage & Switcher:**
  - Encrypted local storage supporting multiple CUKTECH chargers. Switch active chargers with 1 tap or use auto-pair scanning.
- **Cryptographic 2-Phase Handshake:**
  - **Phase A (`0xA4`):** Exchanges capabilities on `CHAR_AUTH_CTRL` (`00000010`) and `CHAR_AUTH_DATA` (`00000005`).
  - **Phase B (`0x1500`):** Ephemeral `SECP256R1` ECDH key agreement, `HKDF-SHA256` session key derivation bound to the 12-byte MiOT Token, and mutual `AES-CCM` challenge verification.

---

## 5. Performance, Battery & Polling Architecture
- **0.0% CPU & Near-Zero Battery Drain on Idle:**
  - Uses `< 0.1%` CPU when idle; virtually zero battery impact over open BLE.
  - **Silent Idle Telemetry Filter:** Unchanged `0.0V 0.0A (Idle)` poll responses are silently dropped—preventing redundant UI recompositions, widget redraws, Tasker broadcasts, and console spam.
- **Hardware-Aware Hybrid Telemetry:**
  - **USB-C1 & USB-C2:** Hardware pushes live notifications (`b4 = 0x04`) spontaneously on plug-in or load change. BLEBridge runs **0 polls when idle**, only using a light `3s` backup poll during active charging to catch unplugs.
  - **USB-C3 & USB-A:** Hardware lacks push frames; BLEBridge polls them every `5s` when idle (instant plug-in detection) and speeds up to `3s` when charging for smooth energy integration.
- **Sequential Hardware Mutex Queue:**
  - Funnels all GATT writes through a sequential, non-blocking coroutine queue with automatic 1.8s timeout deadman switches to prevent Telink controller saturation.

---

## 6. Live Telemetry Dashboard
- **Total Power Hero Card:**
  - Real-time wattage (`0.0W` to `120.0W`).
  - Peak Session Wattage badge.
  - Accumulated Energy (`Wh`).
  - Bluetooth RSSI signal quality meter with color-coded reception indicator.
- **Power Allocation Scenarios Dialog:**
  - Official AD1204 hardware modes:
    1. **AI Mode (`1`):** Intelligent dynamic power allocation across all 4 ports.
    2. **Hybrid (`2`):** Balanced allocation between laptops and mobile accessories.
    3. **Single Device Priority (`3`):** Dedicates maximum power ceiling to USB-C1.
    4. **Dual High-Power (`4`):** Splits power evenly between USB-C1 and USB-C2.
- **4 Individual Port Cards:**
  - Live Voltage (`V`), Current (`A`), and Power (`W`).
  - Negotiated protocol badge (`PD3.0`, `PPS`, `MiPPS`, `UFCS`, `SCP`, `QC3.0`).
  - 1-tap port power switch.
  - **Energy Limit (`Wh`) Cutoff:** Auto-shutoff when target energy is reached (`Once` or `Always`) with presets (`10Wh`, `25Wh`, `50Wh`, `80Wh`, `100Wh`).
  - **Closure Countdown Timer:** Precision hour/minute wheels + presets (`Always On`, `15m`, `30m`, `1h`, `2h`, `4h`) + second-by-second live countdown ticker badge on the card.

---

## 7. Ports Control & Protocol Toggles
- **Per-Port Fast-Charge Protocol Switches (`PIID 20–23`):**
  - **PD (up to 100W):** Standard USB Power Delivery contract.
  - **PPS (Programmable Power Supply):** Fine-grained voltage/current stepping for Samsung Super Fast Charging and Xiaomi HyperCharge.
  - **UFCS (Universal Fast Charging Specification):** China's unified fast-charging protocol (Huawei, OPPO, Vivo, Xiaomi).
  - **Huawei SCP (SuperCharge Protocol 22.5W):** Low-voltage direct charging for Huawei devices.
- **USB-A Low Current Mode (4h) (`PIID 18`):**
  - Keeps the USB-A port active for 4 hours without shutting down due to low current draw—ideal for smartwatches, fitness bands, and wireless earbuds.

---

## 8. Session Tracking, Wh Integrators & CSV Export
- **Live & Historical Charging Sessions:**
  - Automatic session start detection when power exceeds `0.2W`.
  - Tracks start timestamp, elapsed duration, peak wattage, average wattage, and integrated energy in Watt-hours (`Wh`) using trapezoidal energy integration.
- **Session Detail Inspector Dialog:** Tap any session card in the History tab to view full charging metrics and time breakdown.
- **Consolidated Sessions Table Dialog:** Unified chronological spreadsheet view displaying all charging sessions across all ports.
- **CSV Data Export:**
  - Filter by timeframe: `24 Hours`, `7 Days`, `30 Days`, `All Time`.
  - Filter by scope: `All Data`, `Active Only`, `Completed Only`.
  - Exports directly to `Downloads/cuktech10/history/` on shared storage.
- **Granular History Clearing Dialog:** Delete completed sessions while keeping active charging sessions intact, or perform a full purge.

---

## 9. Charger Hardware Settings & Display Control
- **Display Brightness (`PIID 14`):** `Low (20%)`, `Medium (60%)`, `High (100%)`.
- **Screen Timeout (`PIID 15`):** `Always On`, `1 min`, `5 min`, `10 min`, `30 min`.
- **Idle Screen-Off (`PIID 19`):** Automatically puts the charger's color LCD to sleep when all ports are idle (`0W`), waking immediately when a cable is plugged in.
- **Physical Screen Language (`PIID 13`):** Toggle the charger's onboard LCD between `Chinese (中文)` and `English`.
- **5 Custom Dark Theme Accent Palettes:**
  - `CUKTECH Gunmetal` (Classic deep navy gunmetal with balanced cyan)
  - `AMOLED Pure Black` (True `#000000` with soft electric cyan)
  - `Cyberpunk Neon` (Midnight obsidian with neon emerald green)
  - `Titanium Amber` (Warm dark titanium with industrial hazard amber)
  - `Deep Ocean Blue` (Midnight navy with cool sapphire blue)

---

## 10. Home Screen Widgets (HyperOS 4 & Solid Dark)
4 native home screen widgets in the Android Widget Picker (`updatePeriodMillis="0"` for zero periodic battery drain):
- **`CUKTECH 10 · 4x2 Soft Glass`:** Translucent frosted slate glass, connection status dot, total wattage, scenario pill (tap to cycle modes), and 4 interactive port capsules (tap to toggle port ON/OFF).
- **`CUKTECH 10 · 2x2 Soft Glass`:** Compact square tile with total wattage, scenario badge, an uncompressed 2×2 port wattage grid, and a live cyan sparkline graph when charging.
- **`CUKTECH 10 · 4x2 Solid Dark`:** 100% opaque obsidian dark card with the full 4x2 control center layout.
- **`CUKTECH 10 · 2x2 Solid Dark`:** 100% opaque obsidian dark card with the compact 2x2 layout.

---

## 11. Dual-Tab Terminal: Console Log & BLE Diagnostics
- **Tab 0 (Console Log):**
  - 1,000-message scroll-persisted rolling buffer.
  - 3 Granular Verbosity Levels:
    - `LEVEL 1: Errors Only`
    - `LEVEL 2: Connections & Status`
    - `LEVEL 3: Full Raw Telemetry` (Raw hex, MIoT frames, decrypted buffers)
  - 1-tap Copy to Clipboard and manual Clear button.
- **Tab 1 (BLE Diagnostics):**
  - Fully manual, on-demand hardware diagnostics (never runs automatically).
  - Audits phone Bluetooth adapter capabilities (LE 2M, Coded PHY, Extended Advertising).
  - Checks Xiaomi MiOT service UUID (`0xFE95`) discovery.
  - Displays charger serial number, firmware version, and GATT MTU.
  - Verifies Android Battery Whitelist and CPU WakeLock keepalive status.

---

## 12. Automation Hub: Tasker Plugin, Webhooks & ADB
- **Tasker / Locale / MacroDroid Plugin (`TaskerEditActivity` & `TaskerFireReceiver`):**
  - Configure automated actions directly inside Tasker's GUI:
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
- **Tasker Telemetry Broadcast Intent (`com.cramqin.blecuktech10.TELEMETRY_UPDATE`):**
  - Broadcasts live variables to Tasker: `%total_watts`, `%port_1_v`, `%port_1_a`, `%port_1_w`, `%port_1_protocol`, `%port_1_enabled` (for ports 1..4).
- **Home Assistant HTTP Webhook Push:**
  - Pushes JSON telemetry (`total_watts`, per-port `V`/`A`/`W`/`protocol`, `rssi`) to any Home Assistant webhook endpoint.
- **ADB / Termux Broadcast Intent API:**
  - Control the charger from Termux scripts or ADB commands over USB/Wi-Fi.
- **Raw TCP-to-BLE Socket Gateway (`port 8080`):**
  - Bridges raw TCP socket connections straight into physical BLE GATT characteristics for low-level protocol development and scripting.
