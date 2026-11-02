package com.example.cuktech.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class SessionKeys(
    val devKey: ByteArray, // 16 bytes: Device -> App AES-CCM key
    val appKey: ByteArray, // 16 bytes: App -> Device AES-CCM key
    val devIv: ByteArray,  // 4 bytes:  Device -> App IV prefix
    val appIv: ByteArray   // 4 bytes:  App -> Device IV prefix
)

data class Fe95Advertisement(
    val frameCtrl: Int,
    val productId: Int,
    val frameCounter: Int,
    val embeddedMac: String?,
    val isCuktech10Ultra: Boolean
)

/**
 * Core cryptographic, framing, and BLE UUID protocol implementation for CUKTECH 10 Ultra
 * (Xiaomi MiOT BLE v2 over service 0xFE95, Product ID 0x660E).
 * Ported directly from `cuktech-ble-server/src/cuktech_ble/protocol.py` and `controller.py`.
 */
object CuktechProtocol {

    // BLE Service & Characteristic UUIDs
    val MI_SERVICE_UUID: UUID = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
    val CHAR_AUTH_CTRL: UUID = UUID.fromString("00000010-0000-1000-8000-00805f9b34fb")
    val CHAR_AUTH_DATA: UUID = UUID.fromString("00000019-0000-1000-8000-00805f9b34fb")
    val CHAR_CMD_SEND: UUID = UUID.fromString("0000001a-0000-1000-8000-00805f9b34fb")
    val CHAR_CMD_RECV: UUID = UUID.fromString("0000001b-0000-1000-8000-00805f9b34fb")
    val CHAR_DEVICE_INFO: UUID = UUID.fromString("0000001c-0000-1000-8000-00805f9b34fb")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val PRODUCT_ID_CUKTECH_10_ULTRA = 0x660e

    // Authentication Handshake Control Commands & Responses
    val CMD_INIT_A4 = byteArrayOf(0xA4.toByte())
    val CMD_LOGIN = byteArrayOf(0x24, 0x00, 0x00, 0x00)
    val CMD_SEND_KEY = byteArrayOf(0x00, 0x00, 0x00, 0x0b, 0x01, 0x00)
    val CMD_SEND_INFO = byteArrayOf(0x00, 0x00, 0x00, 0x0a, 0x01, 0x00)
    val RCV_RDY = byteArrayOf(0x00, 0x00, 0x01, 0x01)
    val RCV_OK = byteArrayOf(0x00, 0x00, 0x01, 0x00)
    val RCV_TOUT = byteArrayOf(0x00, 0x00, 0x01, 0x02)
    val RCV_ERR = byteArrayOf(0x00, 0x00, 0x01, 0x03)
    val KEEPALIVE_PAYLOAD = byteArrayOf(0x00, 0x00, 0x00, 0x00)

    // SIID & PIID Definitions (njcuk.fitting.ad1204)
    const val SIID_DEVICE_INFO = 1
    const val SIID_CHARGER = 2

    const val PIID_PORT_C1 = 1
    const val PIID_PORT_C2 = 2
    const val PIID_PORT_C3 = 3
    const val PIID_PORT_A = 4
    const val PIID_SCENE_MODE = 5
    const val PIID_SCREEN_TIMEOUT = 6
    const val PIID_PORT_C1_PDO = 7
    const val PIID_PORT_C2_PDO = 8
    const val PIID_COUNTDOWN_C1 = 9
    const val PIID_COUNTDOWN_C2 = 10
    const val PIID_COUNTDOWN_C3 = 11
    const val PIID_COUNTDOWN_A = 12
    const val PIID_LANGUAGE = 13
    const val PIID_USB_A_LOW_CURRENT = 15
    const val PIID_PORT_SW_BITMAP = 16
    const val PIID_PORT_C1_PROTOCOL = 17
    const val PIID_PORT_C2_PROTOCOL = 18
    const val PIID_IDLE_SCREEN_OFF = 19
    const val PIID_ORIENTATION_LOCK = 20
    const val PIID_PROTOCOL_EXTEND = 21

    val SCENE_MODES = mapOf(
        1 to "动态分配",
        2 to "笔记本优先",
        3 to "单口模式",
        4 to "双口同充"
    )

    val SCREEN_TIMEOUTS = mapOf(
        0 to "常亮",
        1 to "60 秒",
        2 to "5 分钟",
        3 to "60 分钟"
    )

    val COUNTDOWN_OPTIONS = mapOf(
        0 to "不开启",
        1 to "1 小时",
        2 to "2 小时",
        3 to "4 小时",
        4 to "8 小时",
        5 to "10 小时"
    )

    val LANGUAGE_OPTIONS = mapOf(
        0 to "中文",
        1 to "English"
    )

    // =========================================================================
    // 1. ADVERTISEMENT PARSING (0xFE95 Service Data)
    // =========================================================================

    fun parseFe95Advertisement(serviceData: ByteArray?): Fe95Advertisement? {
        if (serviceData == null || serviceData.size < 5) return null
        val frameCtrl = (serviceData[0].toInt() and 0xFF) or ((serviceData[1].toInt() and 0xFF) shl 8)
        val productId = (serviceData[2].toInt() and 0xFF) or ((serviceData[3].toInt() and 0xFF) shl 8)
        val frameCounter = serviceData[4].toInt() and 0xFF
        val hasMac = (frameCtrl and 0x0010) != 0
        var macStr: String? = null
        if (hasMac && serviceData.size >= 11) {
            // Little-endian 6-byte MAC at offset 5..10
            val macBytes = serviceData.sliceArray(5 until 11).reversedArray()
            macStr = macBytes.joinToString(":") { "%02X".format(it) }
        }
        return Fe95Advertisement(
            frameCtrl = frameCtrl,
            productId = productId,
            frameCounter = frameCounter,
            embeddedMac = macStr,
            isCuktech10Ultra = (productId == PRODUCT_ID_CUKTECH_10_ULTRA)
        )
    }

    // =========================================================================
    // 2. HKDF-SHA256 & HMAC-SHA256 SESSION KEY DERIVATION
    // =========================================================================

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int = 64
    ): ByteArray {
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmacSha256(actualSalt, ikm)
        var t = ByteArray(0)
        val okm = ByteArray(length)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val input = ByteArray(t.size + info.size + 1)
            System.arraycopy(t, 0, input, 0, t.size)
            System.arraycopy(info, 0, input, t.size, info.size)
            input[input.size - 1] = counter.toByte()
            t = hmacSha256(prk, input)
            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, okm, offset, copyLen)
            offset += copyLen
            counter++
        }
        return okm
    }

    /**
     * Derives the 64-byte MiOT session keys from:
     * - token: 12-byte device token
     * - randKey: 16-byte app random challenge
     * - devRandom: 16-byte device random challenge
     */
    fun deriveSessionKeys(token: ByteArray, randKey: ByteArray, devRandom: ByteArray): SessionKeys {
        val salt = randKey + devRandom
        val info = "mible-login-info".toByteArray(Charsets.US_ASCII)
        val okm = hkdfSha256(ikm = token, salt = salt, info = info, length = 64)
        return SessionKeys(
            devKey = okm.copyOfRange(0, 16),
            appKey = okm.copyOfRange(16, 32),
            devIv = okm.copyOfRange(32, 36),
            appIv = okm.copyOfRange(36, 40)
        )
    }

    fun computeDeviceExpectedHmac(devKey: ByteArray, devRandom: ByteArray, randKey: ByteArray): ByteArray {
        return hmacSha256(devKey, devRandom + randKey)
    }

    fun computeAppHmac(appKey: ByteArray, randKey: ByteArray, devRandom: ByteArray): ByteArray {
        return hmacSha256(appKey, randKey + devRandom)
    }

    // =========================================================================
    // 3. NONCE CONSTRUCTION & RFC 3610 AES-CCM (12B NONCE, 4B TAG, AAD=0x11)
    // =========================================================================

    /**
     * Constructs 12-byte nonce: ivPrefix (4B) + [00 00 00 00] (4B) + counter (4B little-endian).
     */
    fun buildNonce(ivPrefix: ByteArray, counter: Long): ByteArray {
        require(ivPrefix.size == 4) { "IV prefix must be 4 bytes" }
        val buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(ivPrefix)
        buf.putInt(0)
        buf.putInt((counter and 0xFFFFFFFFL).toInt())
        return buf.array()
    }

    /**
     * Pure RFC 3610 AES-CCM encryption built on standard AES/ECB/NoPadding so it works
     * identically on all Android API levels and unit test JVMs.
     * Returns ciphertext + 4-byte tag.
     */
    fun aesCcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = byteArrayOf(0x11),
        tagLen: Int = 4
    ): ByteArray {
        require(nonce.size in 7..13) { "Invalid CCM nonce size: ${nonce.size}" }
        require(tagLen in listOf(4, 6, 8, 10, 12, 14, 16)) { "Invalid CCM tagLen: $tagLen" }

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)

        val macBlock = computeCcmCbcMac(cipher, nonce, plaintext, aad, tagLen)
        val s0 = cipher.doFinal(buildCcmCounterBlock(nonce, 0))
        val tag = ByteArray(tagLen)
        for (i in 0 until tagLen) {
            tag[i] = ((macBlock[i].toInt() and 0xFF) xor (s0[i].toInt() and 0xFF)).toByte()
        }

        val ciphertext = xorCcmCtr(cipher, nonce, plaintext)
        return ciphertext + tag
    }

    /**
     * Pure RFC 3610 AES-CCM decryption. Splits `ciphertextWithTag` into `ciphertext` and
     * `tag` (last `tagLen` bytes), decrypts, and verifies the tag in constant time.
     * Throws `SecurityException` on authentication tag mismatch.
     */
    fun aesCcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextWithTag: ByteArray,
        aad: ByteArray = byteArrayOf(0x11),
        tagLen: Int = 4
    ): ByteArray {
        if (ciphertextWithTag.size < tagLen) {
            throw SecurityException("Ciphertext shorter than tag length ($tagLen)")
        }
        val cipherLen = ciphertextWithTag.size - tagLen
        val ciphertext = ciphertextWithTag.copyOfRange(0, cipherLen)
        val receivedTag = ciphertextWithTag.copyOfRange(cipherLen, ciphertextWithTag.size)

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)

        val plaintext = xorCcmCtr(cipher, nonce, ciphertext)
        val macBlock = computeCcmCbcMac(cipher, nonce, plaintext, aad, tagLen)
        val s0 = cipher.doFinal(buildCcmCounterBlock(nonce, 0))

        var diff = 0
        for (i in 0 until tagLen) {
            val expectedByte = ((macBlock[i].toInt() and 0xFF) xor (s0[i].toInt() and 0xFF)).toByte()
            diff = diff or ((expectedByte.toInt() and 0xFF) xor (receivedTag[i].toInt() and 0xFF))
        }
        if (diff != 0) {
            throw SecurityException("AES-CCM tag verification failed")
        }
        return plaintext
    }

    private fun computeCcmCbcMac(
        ecbCipher: Cipher,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
        tagLen: Int
    ): ByteArray {
        val qLen = 15 - nonce.size
        val flags = (if (aad.isNotEmpty()) 0x40 else 0x00) or
            (((tagLen - 2) / 2) shl 3) or
            (qLen - 1)

        val b0 = ByteArray(16)
        b0[0] = flags.toByte()
        System.arraycopy(nonce, 0, b0, 1, nonce.size)
        var msgLen = plaintext.size
        for (i in 0 until qLen) {
            b0[15 - i] = (msgLen and 0xFF).toByte()
            msgLen = msgLen ushr 8
        }

        var x = ecbCipher.doFinal(b0)

        if (aad.isNotEmpty()) {
            val aadEncoded = ByteArray(2 + aad.size)
            aadEncoded[0] = ((aad.size ushr 8) and 0xFF).toByte()
            aadEncoded[1] = (aad.size and 0xFF).toByte()
            System.arraycopy(aad, 0, aadEncoded, 2, aad.size)
            x = cbcMacFeedPadded(ecbCipher, x, aadEncoded)
        }

        if (plaintext.isNotEmpty()) {
            x = cbcMacFeedPadded(ecbCipher, x, plaintext)
        }
        return x
    }

    private fun cbcMacFeedPadded(ecbCipher: Cipher, initialX: ByteArray, data: ByteArray): ByteArray {
        var x = initialX
        var offset = 0
        while (offset < data.size) {
            val block = ByteArray(16)
            val copyLen = minOf(16, data.size - offset)
            for (i in 0 until copyLen) {
                block[i] = ((x[i].toInt() and 0xFF) xor (data[offset + i].toInt() and 0xFF)).toByte()
            }
            for (i in copyLen until 16) {
                block[i] = x[i]
            }
            x = ecbCipher.doFinal(block)
            offset += 16
        }
        return x
    }

    private fun xorCcmCtr(ecbCipher: Cipher, nonce: ByteArray, input: ByteArray): ByteArray {
        val output = ByteArray(input.size)
        var offset = 0
        var counterIdx = 1
        while (offset < input.size) {
            val sBlock = ecbCipher.doFinal(buildCcmCounterBlock(nonce, counterIdx))
            val copyLen = minOf(16, input.size - offset)
            for (i in 0 until copyLen) {
                output[offset + i] = ((input[offset + i].toInt() and 0xFF) xor (sBlock[i].toInt() and 0xFF)).toByte()
            }
            offset += 16
            counterIdx++
        }
        return output
    }

    private fun buildCcmCounterBlock(nonce: ByteArray, counterIdx: Int): ByteArray {
        val qLen = 15 - nonce.size
        val block = ByteArray(16)
        block[0] = (qLen - 1).toByte()
        System.arraycopy(nonce, 0, block, 1, nonce.size)
        var c = counterIdx
        for (i in 0 until qLen) {
            block[15 - i] = (c and 0xFF).toByte()
            c = c ushr 8
        }
        return block
    }

    // =========================================================================
    // 4. MIOT COMMAND FRAMING (TLV BUILDER & PACKET ASSEMBLER)
    // =========================================================================

    /**
     * Builds the unencrypted 10-byte + value MiOT TLV payload:
     * `[reqId_lo, reqId_hi, b2, b3, b4, count, siid, piid_lo, piid_hi, fmt, ...valueBytes]`
     *
     * Exact match with `protocol.py::build_miot_tlv`:
     * - GET: `b2=0x03, b4=0x03, fmt=0x00, value=empty`
     * - SET: `b2=0x01, b4=0x01, fmt=0x01 (uint8) or 0x09 (bytes)`
     */
    fun buildMiotTlv(
        reqId: Int,
        siid: Int,
        piid: Int,
        isSet: Boolean = false,
        valueBytes: ByteArray = ByteArray(0),
        fmtOverride: Int? = null
    ): ByteArray {
        val b2 = if (isSet) 0x01 else 0x03
        val b3 = 0x00
        val b4 = if (isSet) 0x01 else 0x03
        val count = 0x01
        val fmt = fmtOverride ?: if (!isSet) 0x00 else if (valueBytes.size == 1) 0x01 else 0x09

        val header = byteArrayOf(
            (reqId and 0xFF).toByte(),
            ((reqId ushr 8) and 0xFF).toByte(),
            b2.toByte(),
            b3.toByte(),
            b4.toByte(),
            count.toByte(),
            (siid and 0xFF).toByte(),
            (piid and 0xFF).toByte(),
            ((piid ushr 8) and 0xFF).toByte(),
            (fmt and 0xFF).toByte()
        )
        return header + valueBytes
    }

    /**
     * Wraps encrypted TLV + 4B tag into a frame ready to write to `CHAR_CMD_SEND` (0x001a):
     * `[0x01, 0x00, it_lo, it_hi] + ciphertextWithTag`
     */
    fun buildEncryptedCmdPacket(
        appKey: ByteArray,
        appIv: ByteArray,
        appItCounter: Long,
        tlvPlaintext: ByteArray
    ): ByteArray {
        val nonce = buildNonce(appIv, appItCounter)
        val encryptedWithTag = aesCcmEncrypt(appKey, nonce, tlvPlaintext)
        val header = byteArrayOf(
            0x01,
            0x00,
            (appItCounter and 0xFF).toByte(),
            ((appItCounter ushr 8) and 0xFF).toByte()
        )
        return header + encryptedWithTag
    }

    /**
     * Encodes PIID 21 (`PIID_PROTOCOL_EXTEND`, 14 bytes, format `0x09`) for PD, PPS, UFCS, SCP switches.
     * Matches `protocol.py::encode_protocol_extend`:
     * `[pd, pps, ufcs, 0, scp, 0, 1, 1, 1, 1, 1, 0, 0, 0]`
     */
    fun encodeProtocolExtend(
        pd: Boolean = true,
        pps: Boolean = true,
        ufcs: Boolean = true,
        scp: Boolean = true,
        rawExisting: ByteArray? = null
    ): ByteArray {
        val base = if (rawExisting != null && rawExisting.size == 14) {
            rawExisting.copyOf()
        } else {
            byteArrayOf(1, 1, 1, 0, 1, 0, 1, 1, 1, 1, 1, 0, 0, 0)
        }
        base[0] = if (pd) 1 else 0
        base[1] = if (pps) 1 else 0
        base[2] = if (ufcs) 1 else 0
        base[4] = if (scp) 1 else 0
        return base
    }
}
