# 🌐 Optional TCP Socket Bridge Guide

The **Optional TCP Socket Bridge** in BLEBridge-Cuktech10-Ultra allows external computers, scripts, and local automation hubs to receive live telemetry from the **CUKTECH 10 Ultra GaN Charger (AD1204)** over a local TCP socket.

---

## 📌 Key Differences & Important Notes

> [!NOTE]
> - **Bluetooth is Independent:** You do **not** need the TCP Socket Bridge turned on to connect to your charger via Bluetooth, configure port limits, or view live telemetry inside the Android app.
> - **Safe to Turn Off:** Disabling the TCP bridge switch at any time will **never** disconnect your active Bluetooth connection with the charger.
> - **Home Assistant Users:** If integrating with Home Assistant, we recommend using the **HTTP Webhook Integration** (`Dashboard ➔ Setup ➔ Home Assistant Webhook`) instead of this raw TCP bridge.

---

## 📡 1. Testing on the Same Wi-Fi Network

When your Android phone and your PC are connected to the **same Wi-Fi network**:

1. Open the app and navigate to **Gateway**.
2. Scroll to the **OPTIONAL TCP SOCKET BRIDGE** card.
3. Turn **ON** the switch and note your phone's Wi-Fi IP and Port (e.g. `192.168.1.50:8080`).
4. Test connectivity from your PC terminal:

### Linux / macOS / WSL
```bash
# 1. Netcat (stream live telemetry lines directly to console):
nc 192.168.1.50 8080

# 2. Telnet:
telnet 192.168.1.50 8080

# 3. Python one-liner:
python3 -c "import socket; s=socket.create_connection(('192.168.1.50', 8080)); print(s.recv(1024).decode())"
```

### Windows (PowerShell)
```powershell
# Quick connection test:
Test-NetConnection -ComputerName 192.168.1.50 -Port 8080

# Or connect using Telnet:
telnet 192.168.1.50 8080
```

Once connected, the Android app logs a new TCP client in the Console Log and broadcasts live JSON telemetry packets every second!

---

## 🌍 2. Testing from a Different Wi-Fi Network or Remote PC

Because raw TCP sockets bind to local private IPs (e.g. `192.168.x.x`), devices on different subnets or Wi-Fi routers cannot reach each other directly without a bridge. Use one of these three easy methods:

### Method A: ADB Port Forwarding (Recommended for Local Dev)
If your phone is plugged into your PC via USB or connected via **Wireless ADB** in Android Developer Options:
```bash
# Forward PC port 8080 to the phone's port 8080:
adb forward tcp:8080 tcp:8080

# Connect directly to localhost from your PC:
nc 127.0.0.1 8080
```
> [!TIP]
> This works completely offline, with or without any Wi-Fi connection!

---

### Method B: Tailscale / Mesh VPN (Connect From Anywhere in the World)
1. Install **[Tailscale](https://tailscale.com/)** (free) on both your Android phone and PC.
2. Sign in with the same account on both devices.
3. Note your phone's Tailscale virtual IP (e.g. `100.85.20.10`).
4. Run:
   ```bash
   nc 100.85.20.10 8080
   ```
> [!TIP]
> This connects securely and directly over an encrypted peer-to-peer wireguard tunnel even if the phone is on cellular data (4G/5G) and your PC is on an office or home network.

---

### Method C: Phone Mobile Hotspot
1. Turn on **Mobile Hotspot** on your Android phone.
2. Connect your PC's Wi-Fi to the phone's hotspot.
3. The phone's IP is typically `192.168.43.1` (or whatever IP is displayed in the app).
4. Run:
   ```bash
   nc 192.168.43.1 8080
   ```

---

## 📦 3. JSON Telemetry Payload Reference

Each broadcast packet over the TCP socket is a single-line JSON string followed by a newline (`\n`):

```json
{
  "total_power": 18.2,
  "scene_mode": 1,
  "ports": {
    "1": {
      "port_id": 1,
      "name": "Type-C1",
      "voltage_v": 5.0,
      "current_a": 0.42,
      "power_w": 2.1,
      "protocol": "PD3.0",
      "enabled": true
    },
    "2": {
      "port_id": 2,
      "name": "Type-C2",
      "voltage_v": 9.0,
      "current_a": 1.73,
      "power_w": 15.6,
      "protocol": "PPS",
      "enabled": true
    },
    "3": {
      "port_id": 3,
      "name": "Type-C3",
      "voltage_v": 0.0,
      "current_a": 0.0,
      "power_w": 0.0,
      "protocol": "OFF",
      "enabled": false
    },
    "4": {
      "port_id": 4,
      "name": "USB-A",
      "voltage_v": 5.0,
      "current_a": 0.1,
      "power_w": 0.5,
      "protocol": "DCP",
      "enabled": true
    }
  }
}
```
