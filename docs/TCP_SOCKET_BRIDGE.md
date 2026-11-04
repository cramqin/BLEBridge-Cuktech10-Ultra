# 🌐 Optional TCP Socket Bridge Guide

The **Optional TCP Socket Bridge** in BLEBridge-Cuktech10-Ultra is directly inherited from the upstream parent repository **[`woldphone/BLEBridge`](https://github.com/woldphone/BLEBridge)**.

It provides a raw, bidirectional JSON-over-TCP network socket server running directly inside the Android foreground service, allowing external computers, Python scripts, or terminal utilities to interact with Bluetooth LE peripherals over your local network.

---

## 📌 Key Differences & Important Notes

> [!NOTE]
> - **Inherited from Parent `woldphone/BLEBridge`:** The TCP socket bridge is a general-purpose GATT-over-TCP bridge ported directly from the upstream project.
> - **Bluetooth is Independent:** You do **not** need the TCP Socket Bridge turned on to connect to your CUKTECH 10 Ultra charger via Bluetooth, switch ports, or view live telemetry inside the Android app.
> - **Safe to Turn Off:** Disabling the TCP bridge switch at any time will **never** disconnect your active Bluetooth connection with the charger.
> - **Home Assistant Users:** If integrating with Home Assistant, we recommend using the **HTTP Webhook Integration** (`Dashboard ➔ Setup ➔ Home Assistant Webhook`) or **Tasker Intents**, which provide pre-decoded voltage/wattage floats rather than raw hex GATT frames.

---

## 📡 1. Testing on the Same Wi-Fi Network

When your Android phone and your PC are connected to the **same Wi-Fi network**:

1. Open the app and navigate to **Gateway**.
2. Scroll down to the **OPTIONAL TCP SOCKET BRIDGE** card.
3. Turn **ON** the switch and note your phone's Wi-Fi IP and Port (e.g. `192.168.1.50:8080`).
4. Connect from your PC terminal:

### Linux / macOS / WSL
```bash
# 1. Netcat:
nc 192.168.1.50 8080

# 2. Telnet:
telnet 192.168.1.50 8080

# 3. Python one-liner:
python3 -c "import socket; s=socket.create_connection(('192.168.1.50', 8080)); print(s.recv(1024).decode())"
```

### Windows (PowerShell)
```powershell
# Quick connection check:
Test-NetConnection -ComputerName 192.168.1.50 -Port 8080

# Connect via Telnet:
telnet 192.168.1.50 8080
```

---

## 🌍 2. Testing Across Different Wi-Fi Networks or Remote PC

Because raw TCP sockets bind to local private IPs (e.g. `192.168.x.x`), devices on different subnets or Wi-Fi routers cannot reach each other directly without a tunnel. Use one of these three methods:

### Method A: ADB Port Forwarding (Recommended for Local Dev)
If your phone is plugged into your PC via USB or connected via **Wireless ADB** in Android Developer Options:
```bash
# Forward PC port 8080 to the phone's port 8080:
adb forward tcp:8080 tcp:8080

# Connect directly to localhost from your PC:
nc 127.0.0.1 8080
```
> [!TIP]
> This works completely offline, with or without any Wi-Fi network!

---

### Method B: Tailscale / Mesh VPN (Connect From Anywhere in the World)
1. Install **[Tailscale](https://tailscale.com/)** (free) on both your Android phone and PC.
2. Sign in with the same account on both devices.
3. Note your phone's Tailscale virtual IP (e.g. `100.85.20.10`).
4. Run from your PC:
   ```bash
   nc 100.85.20.10 8080
   ```
> [!TIP]
> This connects securely over an encrypted peer-to-peer WireGuard tunnel even if the phone is on cellular data (4G/5G) and your PC is on an office or home network.

---

### Method C: Phone Mobile Hotspot
1. Turn on **Mobile Hotspot** on your Android phone.
2. Connect your PC's Wi-Fi to the phone's hotspot.
3. The phone's IP is typically `192.168.43.1` (or whatever IP is displayed in the app).
4. Run from your PC:
   ```bash
   nc 192.168.43.1 8080
   ```

---

## 🛠️ 3. Upstream `woldphone/BLEBridge` Protocol & Commands

The TCP socket server accepts single-line JSON commands followed by newline (`\n`):

### 1. Ping / Health Check
```json
{"command": "ping"}
```
**Response:**
```json
{"status": "pong", "timestamp": 1730709000000}
```

### 2. BLE Scanning
```json
{"command": "scan"}
```

### 3. Connect to a BLE Peripheral
```json
{"command": "connect", "address": "3C:CD:73:35:26:D1"}
```

### 4. Read Characteristic
```json
{"command": "read", "service": "0000fe95-0000-1000-8000-00805f9b34fb", "characteristic": "00000010-0000-1000-8000-00805f9b34fb"}
```

### 5. Write Characteristic
```json
{
  "command": "write",
  "service": "0000fe95-0000-1000-8000-00805f9b34fb",
  "characteristic": "00000010-0000-1000-8000-00805f9b34fb",
  "payload_hex": "04010203",
  "with_response": true
}
```

### 6. Register / Toggle Notifications
```json
{
  "command": "notify",
  "service": "0000fe95-0000-1000-8000-00805f9b34fb",
  "characteristic": "00000010-0000-1000-8000-00805f9b34fb",
  "enable": true
}
```

### 7. Incoming GATT Notification Stream (Broadcast)
Whenever the connected CUKTECH 10 Ultra or peripheral sends a notification/indication:
```json
{
  "status": "notification",
  "timestamp": 1730709005123,
  "device": "3C:CD:73:35:26:D1",
  "service": "0000fe95-0000-1000-8000-00805f9b34fb",
  "characteristic": "00000010-0000-1000-8000-00805f9b34fb",
  "hex_payload": "0402000000000000",
  "ascii_payload": "........"
}
```

---

## ⚡ Decoded Telemetry vs. Raw TCP GATT Bridge

If you are looking for clean decoded values (`total_watts`, `volts`, `amps`, `wh` per port) rather than raw hex frames, use:
1. **Home Assistant Webhook:** Configured under `Dashboard ➔ Setup ➔ Home Assistant Webhook`.
2. **Tasker Broadcast Intent:** `com.cramqin.blecuktech10.TELEMETRY_UPDATE` (see [FEATURES.md](../FEATURES.md)).
