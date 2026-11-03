package com.example.cuktech.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class XiaomiAccountSession(
    val userId: String,
    val ssecurity: String,
    val serviceToken: String,
    val region: String = "cn",
    val loginTimestamp: Long = System.currentTimeMillis()
)

data class CuktechDeviceCredentials(
    val did: String,
    val name: String,
    val model: String,
    val mac: String,
    val tokenHex: String,   // 12 bytes = 24 hex chars
    val bleKeyHex: String,  // 16 bytes = 32 hex chars
    val region: String = "cn"
) {
    val isCuktech10Ultra: Boolean
        get() = model.contains("ad1204", ignoreCase = true) ||
                model.contains("cuktech", ignoreCase = true) ||
                name.contains("CUKTECH", ignoreCase = true) ||
                name.contains("酷态科", ignoreCase = true) ||
                name.contains("10", ignoreCase = true)

    val hasValidToken: Boolean
        get() {
            val clean = tokenHex.trim().replace(":", "").replace("-", "").replace(" ", "")
            return (clean.length == 24 || clean.length == 32) && clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        }

    fun tokenBytes(): ByteArray {
        val clean = tokenHex.trim().replace(":", "").replace("-", "").replace(" ", "")
        val hex24 = if (clean.length >= 24) clean.substring(0, 24) else clean
        return hexToBytes(hex24)
    }

    fun bleKeyBytes(): ByteArray {
        val clean = bleKeyHex.trim().replace(":", "").replace("-", "").replace(" ", "")
        return if (clean.length == 32) hexToBytes(clean) else ByteArray(16)
    }

    companion object {
        fun hexToBytes(hex: String): ByteArray {
            val clean = hex.replace(":", "").replace("-", "").replace(" ", "")
            val len = clean.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }
    }
}

/**
 * Android Keystore-backed encrypted credential storage for Xiaomi Cloud sessions
 * and CUKTECH 10 Ultra BLE credentials (MAC, MiOT token, BLE beaconkey).
 * Never logs sensitive tokens, passwords, or keys.
 */
class SecureCredentialStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _session = MutableStateFlow<XiaomiAccountSession?>(null)
    val session: StateFlow<XiaomiAccountSession?> = _session.asStateFlow()

    private val _devices = MutableStateFlow<List<CuktechDeviceCredentials>>(emptyList())
    val devices: StateFlow<List<CuktechDeviceCredentials>> = _devices.asStateFlow()

    private val _activeDevice = MutableStateFlow<CuktechDeviceCredentials?>(null)
    val activeDevice: StateFlow<CuktechDeviceCredentials?> = _activeDevice.asStateFlow()

    init {
        reloadFromDisk()
    }

    fun reloadFromDisk() {
        _session.value = loadSessionInternal()
        val devList = loadDevicesInternal()
        _devices.value = devList
        val activeMac = prefs.getString(KEY_ACTIVE_MAC, null)
        _activeDevice.value = devList.firstOrNull { it.mac.equals(activeMac, ignoreCase = true) }
            ?: devList.firstOrNull { it.isCuktech10Ultra && it.hasValidToken }
            ?: devList.firstOrNull()
    }

    fun saveSession(newSession: XiaomiAccountSession) {
        val json = JSONObject().apply {
            put("userId", newSession.userId)
            put("ssecurity", newSession.ssecurity)
            put("serviceToken", newSession.serviceToken)
            put("region", newSession.region)
            put("loginTimestamp", newSession.loginTimestamp)
        }.toString()
        prefs.edit().putString(KEY_SESSION_ENC, encrypt(json)).apply()
        _session.value = newSession
    }

    fun saveDevices(deviceList: List<CuktechDeviceCredentials>) {
        val arr = JSONArray()
        for (dev in deviceList) {
            arr.put(JSONObject().apply {
                put("did", dev.did)
                put("name", dev.name)
                put("model", dev.model)
                put("mac", dev.mac.uppercase())
                put("tokenHex", dev.tokenHex.lowercase())
                put("bleKeyHex", dev.bleKeyHex.lowercase())
                put("region", dev.region)
            })
        }
        prefs.edit().putString(KEY_DEVICES_ENC, encrypt(arr.toString())).apply()
        _devices.value = deviceList
        val currentActive = _activeDevice.value
        val nextActive = if (currentActive != null) {
            deviceList.firstOrNull { it.mac.equals(currentActive.mac, ignoreCase = true) }
        } else null
        val chosen = nextActive
            ?: deviceList.firstOrNull { it.isCuktech10Ultra && it.hasValidToken }
            ?: deviceList.firstOrNull()
        if (chosen != null) {
            selectActiveDevice(chosen.mac)
        } else {
            _activeDevice.value = null
        }
    }

    fun addOrUpdateDevice(device: CuktechDeviceCredentials) {
        val current = _devices.value.toMutableList()
        val idx = current.indexOfFirst {
            (it.mac.isNotBlank() && it.mac.equals(device.mac, ignoreCase = true)) ||
                (it.did.isNotBlank() && it.did == device.did)
        }
        if (idx >= 0) {
            current[idx] = device
        } else {
            current.add(0, device)
        }
        saveDevices(current)
        selectActiveDevice(device.mac)
    }

    fun selectActiveDevice(mac: String) {
        prefs.edit().putString(KEY_ACTIVE_MAC, mac.uppercase()).apply()
        _activeDevice.value = _devices.value.firstOrNull { it.mac.equals(mac, ignoreCase = true) }
    }

    /**
     * Imports a JSON credentials file (e.g. cuktech_credentials.json) if requested by user.
     */
    fun importFromJsonString(jsonStr: String): CuktechDeviceCredentials? {
        return try {
            val obj = JSONObject(jsonStr)
            val mac = obj.optString("mac", obj.optString("device_mac", "")).uppercase().trim()
            val token = obj.optString("token", obj.optString("device_token", "")).lowercase().trim()
            val bleKey = obj.optString("ble_key", obj.optString("beaconkey", obj.optString("device_ble_key", ""))).lowercase().trim()
            val name = obj.optString("name", "CUKTECH 10 Ultra")
            val model = obj.optString("model", "njcuk.fitting.ad1204")
            val did = obj.optString("did", "manual_${mac.replace(":", "")}")
            if (mac.isNotBlank() && token.length == 24) {
                val cred = CuktechDeviceCredentials(
                    did = did,
                    name = name,
                    model = model,
                    mac = mac,
                    tokenHex = token,
                    bleKeyHex = bleKey
                )
                addOrUpdateDevice(cred)
                cred
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun tryAutoImportLocalCredentialsFile(): Boolean {
        if (_devices.value.isNotEmpty()) return false
        val candidates = listOf(
            File("/storage/emulated/0/Development/cuktech10/cuktech_credentials.json"),
            File("/storage/emulated/0/Download/cuktech_credentials.json")
        )
        for (file in candidates) {
            if (file.exists() && file.canRead()) {
                val imported = runCatching { importFromJsonString(file.readText()) }.getOrNull()
                if (imported != null) return true
            }
        }
        return false
    }

    fun logoutSessionOnly() {
        prefs.edit().remove(KEY_SESSION_ENC).apply()
        _session.value = null
    }

    fun logoutAndWipeAll() {
        prefs.edit()
            .remove(KEY_SESSION_ENC)
            .remove(KEY_DEVICES_ENC)
            .remove(KEY_ACTIVE_MAC)
            .apply()
        _session.value = null
        _devices.value = emptyList()
        _activeDevice.value = null
    }

    private fun loadSessionInternal(): XiaomiAccountSession? {
        val enc = prefs.getString(KEY_SESSION_ENC, null) ?: return null
        val jsonStr = decrypt(enc) ?: return null
        return try {
            val obj = JSONObject(jsonStr)
            XiaomiAccountSession(
                userId = obj.getString("userId"),
                ssecurity = obj.getString("ssecurity"),
                serviceToken = obj.getString("serviceToken"),
                region = obj.optString("region", "cn"),
                loginTimestamp = obj.optLong("loginTimestamp", 0L)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun loadDevicesInternal(): List<CuktechDeviceCredentials> {
        val enc = prefs.getString(KEY_DEVICES_ENC, null) ?: return emptyList()
        val jsonStr = decrypt(enc) ?: return emptyList()
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<CuktechDeviceCredentials>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    CuktechDeviceCredentials(
                        did = obj.optString("did", ""),
                        name = obj.optString("name", "CUKTECH 10 Ultra"),
                        model = obj.optString("model", ""),
                        mac = obj.optString("mac", "").uppercase(),
                        tokenHex = obj.optString("tokenHex", "").lowercase(),
                        bleKeyHex = obj.optString("bleKeyHex", "").lowercase(),
                        region = obj.optString("region", "cn")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) {
                existing.secretKey
            } else {
                val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                val spec = KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGen.init(spec)
                keyGen.generateKey()
            }
        } catch (_: Exception) {
            // Fallback for Robolectric unit test environments without AndroidKeyStore provider
            val fallbackB64 = prefs.getString(KEY_FALLBACK_RAW, null)
            if (fallbackB64 != null) {
                SecretKeySpec(Base64.decode(fallbackB64, Base64.NO_WRAP), "AES")
            } else {
                val raw = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                prefs.edit().putString(KEY_FALLBACK_RAW, Base64.encodeToString(raw, Base64.NO_WRAP)).apply()
                SecretKeySpec(raw, "AES")
            }
        }
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? {
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            val ivLen = combined[0].toInt() and 0xFF
            val iv = combined.copyOfRange(1, 1 + ivLen)
            val ciphertext = combined.copyOfRange(1 + ivLen, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "cuktech_secure_vault"
        private const val KEYSTORE_ALIAS = "cuktech_master_key_v1"
        private const val KEY_SESSION_ENC = "xiaomi_session_enc"
        private const val KEY_DEVICES_ENC = "cuktech_devices_enc"
        private const val KEY_ACTIVE_MAC = "active_device_mac"
        private const val KEY_FALLBACK_RAW = "test_fallback_k"
    }
}
