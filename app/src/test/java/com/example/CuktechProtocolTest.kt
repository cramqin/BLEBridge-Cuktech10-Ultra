package com.example

import com.example.cuktech.cloud.XiaomiCloudClient
import com.example.cuktech.energy.AdaptiveEnergyIntegrator
import com.example.cuktech.energy.ChargeSessionDetector
import com.example.cuktech.protocol.CuktechProtocol
import com.example.cuktech.protocol.PortTelemetryDecoder
import com.example.cuktech.security.CuktechDeviceCredentials
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CuktechProtocolTest {

    @Test
    fun `test HKDF-SHA256 session key derivation and HMACs`() {
        val token = CuktechDeviceCredentials.hexToBytes("0102030405060708090a0b0c")
        val randKey = ByteArray(16) { (it + 1).toByte() }
        val devRandom = ByteArray(16) { (0x20 + it).toByte() }

        val keys = CuktechProtocol.deriveSessionKeys(token, randKey, devRandom)
        assertEquals(16, keys.devKey.size)
        assertEquals(16, keys.appKey.size)
        assertEquals(4, keys.devIv.size)
        assertEquals(4, keys.appIv.size)

        val devHmac = CuktechProtocol.computeDeviceExpectedHmac(keys.devKey, devRandom, randKey)
        val appHmac = CuktechProtocol.computeAppHmac(keys.appKey, randKey, devRandom)
        assertEquals(32, devHmac.size)
        assertEquals(32, appHmac.size)
    }

    @Test
    fun `test RFC 3610 AES-CCM encrypt and decrypt round-trip with 4-byte tag`() {
        val key = ByteArray(16) { (0x10 + it).toByte() }
        val ivPrefix = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        val nonce = CuktechProtocol.buildNonce(ivPrefix, 42L)
        assertEquals(12, nonce.size)

        val tlvPlain = CuktechProtocol.buildMiotTlv(
            reqId = 0x0305,
            siid = CuktechProtocol.SIID_CHARGER,
            piid = CuktechProtocol.PIID_PORT_SW_BITMAP,
            isSet = true,
            valueBytes = byteArrayOf(0x0F)
        )

        val encrypted = CuktechProtocol.aesCcmEncrypt(key, nonce, tlvPlain)
        assertEquals(tlvPlain.size + 4, encrypted.size)

        val decrypted = CuktechProtocol.aesCcmDecrypt(key, nonce, encrypted)
        assertArrayEquals(tlvPlain, decrypted)

        // Verify tamper detection throws SecurityException
        val tampered = encrypted.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xFF).toByte()
        var threw = false
        try {
            CuktechProtocol.aesCcmDecrypt(key, nonce, tampered)
        } catch (_: SecurityException) {
            threw = true
        }
        assertTrue("Tampered tag must throw SecurityException", threw)
    }

    @Test
    fun `test decodePortV2 8-byte and 9-byte frames and Mijia protocol lookup`() {
        // Status=2 (Charging), Voltage=2000 (20.00V), Current=325 (3.25A), Power=65000mW (65.00W), Proto=8
        val frame9 = byteArrayOf(
            0x08,                         // Length prefix
            0x02,                         // Status = Outputting
            0xD0.toByte(), 0x07,          // 2000 -> 20.00V
            0x45, 0x01,                   // 325 -> 3.25A
            0xE8.toByte(), 0xFD.toByte(), // 65000 mW -> 65.00W
            0x08                          // Proto raw
        )

        val decoded = PortTelemetryDecoder.decodePortV2(1, frame9)
        assertNotNull(decoded)
        assertEquals(20.0, decoded!!.voltageV, 0.01)
        assertEquals(3.25, decoded.currentA, 0.01)
        assertEquals(65.0, decoded.powerW, 0.05)
        assertTrue(decoded.isCharging)

        // Apply Mijia PIID 17 code 19 -> Xiaomi 120W
        val protoName = PortTelemetryDecoder.resolveProtocolName(1, 2, 20.0, 5.0, 19)
        assertEquals("Xiaomi 120W", protoName)
    }

    @Test
    fun `test decodePortV2 with native Cuktech 10 Ultra 4-byte record and 16-byte decrypted frame`() {
        // [status, code, current*10, voltage*10] = [1, 0x07, 30, 90] -> 9.0V, 3.0A, 27.0W
        val frame4 = byteArrayOf(0x01, 0x07, 30, 90)
        val decoded4 = PortTelemetryDecoder.decodePortV2(1, frame4)
        assertNotNull(decoded4)
        assertEquals(9.0, decoded4!!.voltageV, 0.01)
        assertEquals(3.0, decoded4.currentA, 0.01)
        assertEquals(27.0, decoded4.powerW, 0.05)
        assertTrue(decoded4.isCharging)

        // 16-byte decrypted MiOT push frame from test_virtual_charger.py:
        // [16, 0x20, 0, 0, 0x04, 0x01, 2, 1, 0, 0, 0, 0, 1, 0x07, 30, 90]
        val fullFrame16 = byteArrayOf(16, 0x20, 0, 0, 0x04, 0x01, 2, 1, 0, 0, 0, 0, 1, 0x07, 30, 90)
        val decoded16 = PortTelemetryDecoder.decodePortV2(1, fullFrame16)
        assertNotNull(decoded16)
        assertEquals(9.0, decoded16!!.voltageV, 0.01)
        assertEquals(3.0, decoded16.currentA, 0.01)
        assertEquals(27.0, decoded16.powerW, 0.05)
    }

    @Test
    fun `test 0xFE95 advertisement parser for Product ID 0x660E`() {
        // frameCtrl = 0x0010 (hasMac), productId = 0x660e (LE: 0x0e, 0x66), counter = 0x05, MAC LE = 66:55:44:33:22:11
        val serviceData = byteArrayOf(
            0x10, 0x00,
            0x0E, 0x66,
            0x05,
            0x66, 0x55, 0x44, 0x33, 0x22, 0x11
        )
        val parsed = CuktechProtocol.parseFe95Advertisement(serviceData)
        assertNotNull(parsed)
        assertTrue(parsed!!.isCuktech10Ultra)
        assertEquals(0x660e, parsed.productId)
        assertEquals("11:22:33:44:55:66", parsed.embeddedMac)
    }

    @Test
    fun `test RC4-drop1024 round-trip and MD5 uppercase`() {
        val key = "test_snonce_secret_key".toByteArray(Charsets.UTF_8)
        val plain = "{\"did\":\"blt.12345\",\"pdid\":1}".toByteArray(Charsets.UTF_8)
        val cipher = XiaomiCloudClient.rc4Drop1024(key, plain)
        val decrypted = XiaomiCloudClient.rc4Drop1024(key, cipher)
        assertArrayEquals(plain, decrypted)
        assertEquals(32, XiaomiCloudClient.md5Upper("password123").length)
    }

    @Test
    fun `test AdaptiveEnergyIntegrator and ChargeSessionDetector`() {
        val integrator = AdaptiveEnergyIntegrator(maxDtSec = 60.0)
        integrator.update(60.0, 1000.0)
        val deltaWh = integrator.update(60.0, 1060.0) // 60W for 60s = 1.0 Wh
        assertEquals(1.0, deltaWh, 0.001)
        assertEquals(1.0, integrator.totalWh, 0.001)

        val detector = ChargeSessionDetector(1, "USB-C1")
        detector.onSample(45.0, 0.2, "PD3.0 PPS", 1000.0)
        detector.onSample(45.0, 0.2, "PD3.0 PPS", 1005.0)
        detector.onSample(45.0, 0.2, "PD3.0 PPS", 1015.0)
        assertNotNull(detector.activeSession)

        // Simulate unplug (< 0.15W for >= 6s)
        detector.onSample(0.0, 0.0, "空闲", 1020.0)
        detector.onSample(0.0, 0.0, "空闲", 1022.0)
        val completed = detector.onSample(0.0, 0.0, "空闲", 1028.0)
        assertNotNull(completed)
        assertEquals("PD3.0 PPS", completed!!.protocolUsed)
        assertEquals(45.0, completed.peakWatts, 0.01)
    }
}
