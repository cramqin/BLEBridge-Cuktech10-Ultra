package com.example.cuktech.cloud

import android.util.Base64
import com.example.cuktech.security.CuktechDeviceCredentials
import com.example.cuktech.security.XiaomiAccountSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.abs

data class QrLoginSession(
    val loginUrl: String,
    val qrImageUrl: String,
    val lpUrl: String,
    val timeoutSeconds: Int = 120,
    val deviceId: String
)

data class TwoFactorChallenge(
    val notificationUrl: String,
    val contextId: String = "",
    val identitySessionCookies: Map<String, String> = emptyMap(),
    val hasPhoneOption: Boolean = true,
    val hasEmailOption: Boolean = false,
    val maskedPhone: String = "",
    val maskedEmail: String = "",
    val deviceId: String = "",
    val region: String = "cn"
)

sealed class XiaomiLoginResult {
    data class Success(val session: XiaomiAccountSession) : XiaomiLoginResult()
    data class Requires2FA(val challenge: TwoFactorChallenge) : XiaomiLoginResult()
    data class Error(val message: String) : XiaomiLoginResult()
}

sealed class QrPollResult {
    data class Success(val session: XiaomiAccountSession) : QrPollResult()
    object Waiting : QrPollResult()
    object Timeout : QrPollResult()
    data class Requires2FA(val challenge: TwoFactorChallenge) : QrPollResult()
    data class Error(val message: String) : QrPollResult()
}

/**
 * Native Kotlin implementation of Xiaomi Cloud Authentication (QR Code Login +
 * Username/Password Login + 2FA Challenge + RC4-drop[1024] encrypted MiOT API
 * for automatic CUKTECH 10 Ultra MAC, token, and BLE beaconkey extraction).
 *
 * Security policy: Never logs passwords, OTPs, cookies, tokens, or BLE keys.
 */
class XiaomiCloudClient {

    private val random = SecureRandom()

    fun generateDeviceId(): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        return (1..16).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    // =========================================================================
    // 1. QR CODE LOGIN FLOW
    // =========================================================================

    suspend fun startQrLogin(region: String = "cn"): Result<QrLoginSession> = withContext(Dispatchers.IO) {
        runCatching {
            val deviceId = generateDeviceId()
            val dc = System.currentTimeMillis().toString()
            val queryParams = mapOf(
                "_qrsize" to "240",
                "qs" to "%3Fsid%3Dxiaomiio%26_json%3Dtrue",
                "callback" to "https://sts.api.io.mi.com/sts",
                "_hasLogo" to "false",
                "sid" to "xiaomiio",
                "serviceParam" to "",
                "_locale" to "zh_CN",
                "_dc" to dc
            )
            val urlStr = "https://account.xiaomi.com/longPolling/loginUrl?" + encodeForm(queryParams)
            val resp = httpRequest(
                urlStr = urlStr,
                method = "GET",
                headers = mapOf(
                    "User-Agent" to USER_AGENT_BROWSER,
                    "Cookie" to "deviceId=$deviceId; sdkVersion=3.8.6"
                ),
                timeoutMs = 15000
            )
            val json = JSONObject(stripXiaomiPrefix(resp.body))
            val code = json.optInt("code", -1)
            if (code != 0) {
                throw IllegalStateException("获取二维码失败: ${json.optString("desc", "code=$code")}")
            }
            val loginUrl = json.optString("loginUrl", "")
            val qrImageUrl = json.optString("qr", "")
            val lpUrl = json.optString("lp", "")
            val timeout = json.optInt("timeout", 120)
            if (loginUrl.isBlank() || lpUrl.isBlank()) {
                throw IllegalStateException("二维码会话缺少 loginUrl 或 lp 参数")
            }
            QrLoginSession(
                loginUrl = loginUrl,
                qrImageUrl = qrImageUrl,
                lpUrl = lpUrl,
                timeoutSeconds = timeout,
                deviceId = deviceId
            )
        }
    }

    suspend fun pollQrLogin(qrSession: QrLoginSession, region: String = "cn"): QrPollResult = withContext(Dispatchers.IO) {
        try {
            val resp = httpRequest(
                urlStr = qrSession.lpUrl,
                method = "GET",
                headers = mapOf(
                    "User-Agent" to USER_AGENT_BROWSER,
                    "Cookie" to "deviceId=${qrSession.deviceId}; sdkVersion=3.8.6"
                ),
                timeoutMs = 30000
            )
            if (resp.body.isBlank()) return@withContext QrPollResult.Waiting
            val json = JSONObject(stripXiaomiPrefix(resp.body))
            val code = json.optInt("code", -1)
            if (code != 0) {
                val desc = json.optString("desc", "")
                if (desc.contains("timeout", ignoreCase = true)) {
                    return@withContext QrPollResult.Timeout
                }
                return@withContext QrPollResult.Waiting
            }

            val notificationUrl = json.optString("notificationUrl", "")
            if (notificationUrl.isNotBlank()) {
                val challenge = inspect2faChallenge(notificationUrl, qrSession.deviceId, region, resp.cookies)
                return@withContext QrPollResult.Requires2FA(challenge)
            }

            val userId = json.opt("userId")?.toString().orEmpty()
            val ssecurity = json.optString("ssecurity", "")
            val location = json.optString("location", "")
            val passToken = json.optString("passToken", "")

            if (userId.isBlank() || ssecurity.isBlank() || location.isBlank()) {
                return@withContext QrPollResult.Error("扫码返回数据不完整")
            }

            val serviceToken = completeStsRedirect(
                location = location,
                deviceId = qrSession.deviceId,
                extraCookies = resp.cookies + mapOf("userId" to userId, "passToken" to passToken)
            )
            if (serviceToken.isBlank()) {
                return@withContext QrPollResult.Error("未能获取 serviceToken")
            }

            QrPollResult.Success(
                XiaomiAccountSession(
                    userId = userId,
                    ssecurity = ssecurity,
                    serviceToken = serviceToken,
                    region = region
                )
            )
        } catch (e: java.net.SocketTimeoutException) {
            QrPollResult.Waiting
        } catch (e: Exception) {
            QrPollResult.Error(e.message ?: "轮询异常")
        }
    }

    // =========================================================================
    // 2. USERNAME / PASSWORD + 2FA LOGIN FLOW
    // =========================================================================

    suspend fun loginWithPassword(
        username: String,
        password: String,
        region: String = "cn"
    ): XiaomiLoginResult = withContext(Dispatchers.IO) {
        try {
            val deviceId = generateDeviceId()
            val baseCookies = mutableMapOf(
                "deviceId" to deviceId,
                "sdkVersion" to "accountsdk-18.8.15",
                "userId" to username.trim()
            )

            // Step 1: serviceLogin to obtain _sign, qs, callback
            val step1Resp = httpRequest(
                urlStr = "https://account.xiaomi.com/pass/serviceLogin?_json=true&sid=xiaomiio&_locale=zh_CN",
                method = "GET",
                headers = mapOf(
                    "User-Agent" to USER_AGENT_APP,
                    "Cookie" to formatCookies(baseCookies)
                ),
                timeoutMs = 15000
            )
            baseCookies.putAll(step1Resp.cookies)
            val step1Json = JSONObject(stripXiaomiPrefix(step1Resp.body))
            val sign = step1Json.optString("_sign", "")
            val qs = step1Json.optString("qs", "%3Fsid%3Dxiaomiio%26_json%3Dtrue")
            val callback = step1Json.optString("callback", "https://sts.api.io.mi.com/sts")

            // Step 2: serviceLoginAuth2 with MD5 uppercase password hash
            val passwordMd5 = md5Upper(password)
            val formParams = mapOf(
                "_json" to "true",
                "callback" to callback,
                "sid" to "xiaomiio",
                "qs" to qs,
                "_sign" to sign,
                "user" to username.trim(),
                "hash" to passwordMd5,
                "cc" to "",
                "_locale" to "zh_CN"
            )

            val step2Resp = httpRequest(
                urlStr = "https://account.xiaomi.com/pass/serviceLoginAuth2",
                method = "POST",
                headers = mapOf(
                    "User-Agent" to USER_AGENT_APP,
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Cookie" to formatCookies(baseCookies)
                ),
                body = encodeForm(formParams),
                timeoutMs = 15000
            )
            baseCookies.putAll(step2Resp.cookies)
            val step2Json = JSONObject(stripXiaomiPrefix(step2Resp.body))

            val notificationUrl = step2Json.optString("notificationUrl", "")
            if (notificationUrl.isNotBlank()) {
                val fullNotifUrl = if (notificationUrl.startsWith("http")) {
                    notificationUrl
                } else {
                    "https://account.xiaomi.com$notificationUrl"
                }
                val challenge = inspect2faChallenge(fullNotifUrl, deviceId, region, baseCookies)
                return@withContext XiaomiLoginResult.Requires2FA(challenge)
            }

            val code = step2Json.optInt("code", -1)
            val ssecurity = step2Json.optString("ssecurity", "")
            val userId = step2Json.opt("userId")?.toString().orEmpty()
            val location = step2Json.optString("location", "")

            if (code != 0 || ssecurity.isBlank() || location.isBlank()) {
                val desc = step2Json.optString("description", step2Json.optString("desc", "登录失败 (code=$code)"))
                return@withContext XiaomiLoginResult.Error(desc)
            }

            val serviceToken = completeStsRedirect(
                location = location,
                deviceId = deviceId,
                extraCookies = baseCookies
            )
            if (serviceToken.isBlank()) {
                return@withContext XiaomiLoginResult.Error("未能获取 serviceToken")
            }

            XiaomiLoginResult.Success(
                XiaomiAccountSession(
                    userId = userId,
                    ssecurity = ssecurity,
                    serviceToken = serviceToken,
                    region = region
                )
            )
        } catch (e: Exception) {
            XiaomiLoginResult.Error(e.message ?: "登录请求异常")
        }
    }

    private fun inspect2faChallenge(
        notificationUrl: String,
        deviceId: String,
        region: String,
        initialCookies: Map<String, String>
    ): TwoFactorChallenge {
        val cookies = initialCookies.toMutableMap()
        cookies["deviceId"] = deviceId
        var contextId = extractQueryParam(notificationUrl, "context") ?: ""
        var hasPhone = true
        var hasEmail = false
        var maskedPhone = ""
        var maskedEmail = ""

        runCatching {
            // Follow notificationUrl to get identity_session cookie
            val notifResp = httpRequest(
                urlStr = notificationUrl,
                method = "GET",
                headers = mapOf(
                    "User-Agent" to USER_AGENT_BROWSER,
                    "Cookie" to formatCookies(cookies)
                ),
                followRedirects = true,
                timeoutMs = 12000
            )
            cookies.putAll(notifResp.cookies)

            if (contextId.isNotBlank()) {
                val listUrl = "https://account.xiaomi.com/identity/list?sid=xiaomiio&ticketType=&_json=true&context=${
                    URLEncoder.encode(contextId, "UTF-8")
                }&_locale=zh_CN"
                val listResp = httpRequest(
                    urlStr = listUrl,
                    method = "GET",
                    headers = mapOf(
                        "User-Agent" to USER_AGENT_BROWSER,
                        "Cookie" to formatCookies(cookies)
                    ),
                    timeoutMs = 10000
                )
                cookies.putAll(listResp.cookies)
                val listJson = JSONObject(stripXiaomiPrefix(listResp.body))
                maskedPhone = listJson.optString("maskedPhone", "")
                maskedEmail = listJson.optString("maskedEmail", "")
                hasPhone = maskedPhone.isNotBlank() || listJson.optInt(" phone", 1) == 1
                hasEmail = maskedEmail.isNotBlank()
            }
        }

        return TwoFactorChallenge(
            notificationUrl = notificationUrl,
            contextId = contextId,
            identitySessionCookies = cookies,
            hasPhoneOption = hasPhone,
            hasEmailOption = hasEmail,
            maskedPhone = maskedPhone,
            maskedEmail = maskedEmail,
            deviceId = deviceId,
            region = region
        )
    }

    /**
     * Requests Xiaomi to send an SMS or Email 2FA verification code (OTP).
     */
    suspend fun send2faCode(challenge: TwoFactorChallenge, useEmail: Boolean = false): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val endpoint = if (useEmail) {
                    "https://account.xiaomi.com/identity/auth/sendEmailTicket"
                } else {
                    "https://account.xiaomi.com/identity/auth/sendPhoneTicket"
                }
                val cookies = challenge.identitySessionCookies.toMutableMap()
                val form = mapOf(
                    "retry" to "0",
                    "ick" to (cookies["ick"] ?: ""),
                    "_json" to "true",
                    "context" to challenge.contextId,
                    "_locale" to "zh_CN"
                )
                val resp = httpRequest(
                    urlStr = "$endpoint?dc=${System.currentTimeMillis()}",
                    method = "POST",
                    headers = mapOf(
                        "User-Agent" to USER_AGENT_BROWSER,
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Cookie" to formatCookies(cookies)
                    ),
                    body = encodeForm(form),
                    timeoutMs = 15000
                )
                val json = JSONObject(stripXiaomiPrefix(resp.body))
                val code = json.optInt("code", -1)
                if (code != 0) {
                    throw IllegalStateException(
                        json.optString("tips", json.optString("desc", "验证码发送失败 (code=$code)，请尝试下方安全网页验证"))
                    )
                }
            }
        }

    /**
     * Verifies a 2FA OTP / Authenticator code and completes the Xiaomi Cloud session.
     */
    suspend fun verify2faCode(
        challenge: TwoFactorChallenge,
        otpCode: String,
        useEmail: Boolean = false
    ): XiaomiLoginResult = withContext(Dispatchers.IO) {
        try {
            val endpoint = if (useEmail) {
                "https://account.xiaomi.com/identity/auth/verifyEmail"
            } else {
                "https://account.xiaomi.com/identity/auth/verifyPhone"
            }
            val cookies = challenge.identitySessionCookies.toMutableMap()
            val form = mapOf(
                "_flag" to if (useEmail) "8" else "4",
                "ticket" to otpCode.trim(),
                "trust" to "true",
                "_json" to "true",
                "ick" to (cookies["ick"] ?: ""),
                "context" to challenge.contextId,
                "_locale" to "zh_CN"
            )
            val resp = httpRequest(
                urlStr = "$endpoint?_dc=${System.currentTimeMillis()}",
                method = "POST",
                headers = mapOf(
                    "User-Agent" to USER_AGENT_BROWSER,
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Cookie" to formatCookies(cookies)
                ),
                body = encodeForm(form),
                timeoutMs = 15000
            )
            cookies.putAll(resp.cookies)
            val json = JSONObject(stripXiaomiPrefix(resp.body))
            val code = json.optInt("code", -1)
            val location = json.optString("location", "")
            if (code != 0 || location.isBlank()) {
                val msg = json.optString("tips", json.optString("desc", "验证码校验未通过 (code=$code)"))
                return@withContext XiaomiLoginResult.Error(msg)
            }

            complete2faFinishRedirect(location, cookies, challenge.deviceId, challenge.region)
        } catch (e: Exception) {
            XiaomiLoginResult.Error(e.message ?: "二步验证校验失败")
        }
    }

    /**
     * Completes a 2FA redirect chain (either from OTP verification or interactive WebView callback).
     */
    suspend fun complete2faFinishRedirect(
        finishUrl: String,
        cookiesIn: Map<String, String>,
        deviceId: String,
        region: String
    ): XiaomiLoginResult = withContext(Dispatchers.IO) {
        try {
            val cookies = cookiesIn.toMutableMap()
            var currentUrl = if (finishUrl.startsWith("http")) finishUrl else "https://account.xiaomi.com$finishUrl"
            var ssecurity = ""
            var userId = cookies["userId"].orEmpty()
            var serviceToken = cookies["serviceToken"].orEmpty()

            for (hop in 0 until 6) {
                val resp = httpRequest(
                    urlStr = currentUrl,
                    method = "GET",
                    headers = mapOf(
                        "User-Agent" to USER_AGENT_BROWSER,
                        "Cookie" to formatCookies(cookies)
                    ),
                    followRedirects = false,
                    timeoutMs = 15000
                )
                cookies.putAll(resp.cookies)
                resp.headers["extension-pragma"]?.firstOrNull()?.let { pragma ->
                    runCatching {
                        val pJson = JSONObject(pragma)
                        if (pJson.optString("ssecurity").isNotBlank()) {
                            ssecurity = pJson.optString("ssecurity")
                        }
                    }
                }
                if (cookies["serviceToken"]?.isNotBlank() == true) {
                    serviceToken = cookies["serviceToken"]!!
                }
                if (cookies["userId"]?.isNotBlank() == true) {
                    userId = cookies["userId"]!!
                }
                if (resp.body.contains("ssecurity")) {
                    runCatching {
                        val bJson = JSONObject(stripXiaomiPrefix(resp.body))
                        if (bJson.optString("ssecurity").isNotBlank()) ssecurity = bJson.optString("ssecurity")
                        if (bJson.opt("userId") != null) userId = bJson.opt("userId").toString()
                        val nextLoc = bJson.optString("location", "")
                        if (nextLoc.isNotBlank() && resp.redirectLocation.isNullOrBlank()) {
                            currentUrl = nextLoc
                            continue
                        }
                    }
                }
                val redirect = resp.redirectLocation
                if (redirect.isNullOrBlank()) break
                currentUrl = if (redirect.startsWith("http")) redirect else URL(URL(currentUrl), redirect).toString()
            }

            if (userId.isNotBlank() && ssecurity.isNotBlank() && serviceToken.isNotBlank()) {
                XiaomiLoginResult.Success(
                    XiaomiAccountSession(
                        userId = userId,
                        ssecurity = ssecurity,
                        serviceToken = serviceToken,
                        region = region
                    )
                )
            } else {
                XiaomiLoginResult.Error("未能从二步验证回调提取完整会话凭证")
            }
        } catch (e: Exception) {
            XiaomiLoginResult.Error(e.message ?: "完成二步验证会话失败")
        }
    }

    private fun completeStsRedirect(
        location: String,
        deviceId: String,
        extraCookies: Map<String, String>
    ): String {
        val cookies = extraCookies.toMutableMap()
        cookies["deviceId"] = deviceId
        val resp = httpRequest(
            urlStr = location,
            method = "GET",
            headers = mapOf(
                "User-Agent" to USER_AGENT_BROWSER,
                "Cookie" to formatCookies(cookies)
            ),
            followRedirects = false,
            timeoutMs = 15000
        )
        return resp.cookies["serviceToken"].orEmpty()
    }

    // =========================================================================
    // 3. MIOT CLOUD API & AUTOMATIC CREDENTIAL RETRIEVAL (RC4-DROP[1024])
    // =========================================================================

    fun getApiBaseUrl(region: String): String {
        val r = region.lowercase().trim()
        return if (r == "cn" || r.isEmpty()) {
            "https://api.io.mi.com/app"
        } else {
            "https://$r.api.io.mi.com/app"
        }
    }

    /**
     * Queries Xiaomi Cloud across the selected region (plus fallback regions if empty)
     * to discover all devices, identify CUKTECH 10 Ultra chargers, and retrieve their
     * MAC address, 12-byte MiOT token, and 16-byte BLE beaconkey.
     */
    suspend fun fetchDevicesAndBleKeys(
        session: XiaomiAccountSession,
        searchAllRegionsIfEmpty: Boolean = true
    ): Result<List<CuktechDeviceCredentials>> = withContext(Dispatchers.IO) {
        runCatching {
            val primaryRegion = session.region.ifBlank { "cn" }
            val regionsToTry = if (searchAllRegionsIfEmpty) {
                listOf(primaryRegion) + SUPPORTED_REGIONS.filter { it != primaryRegion }
            } else {
                listOf(primaryRegion)
            }

            val results = mutableListOf<CuktechDeviceCredentials>()
            val seenKey = mutableSetOf<String>()

            for (region in regionsToTry) {
                val regionDevices = runCatching { fetchDevicesInRegion(session, region) }.getOrDefault(emptyList())
                for (dev in regionDevices) {
                    val key = "${dev.did}_${dev.mac}"
                    if (seenKey.add(key)) {
                        results.add(dev)
                    }
                }
                // Stop searching fallback regions if we found a CUKTECH 10 Ultra in the primary region
                if (results.any { it.isCuktech10Ultra && it.hasValidToken }) {
                    break
                }
            }

            // Sort CUKTECH 10 Ultra devices first
            results.sortedWith(
                compareByDescending<CuktechDeviceCredentials> { it.isCuktech10Ultra && it.hasValidToken }
                    .thenByDescending { it.isCuktech10Ultra }
                    .thenByDescending { it.hasValidToken }
            )
        }
    }

    private fun fetchDevicesInRegion(
        session: XiaomiAccountSession,
        region: String
    ): List<CuktechDeviceCredentials> {
        val rawDevMap = linkedMapOf<String, JSONObject>()

        // 1. /v2/homeroom/gethome -> /v2/home/home_device_list
        runCatching {
            val homeData = apiPostEncrypted(
                session = session,
                region = region,
                uri = "/v2/homeroom/gethome",
                dataObj = JSONObject().apply {
                    put("fg", true)
                    put("fetch_share", true)
                    put("fetch_share_dev", true)
                    put("limit", 300)
                    put("app_ver", 7)
                }
            )
            val homeList = homeData.optJSONObject("result")?.optJSONArray("homelist")
            if (homeList != null) {
                for (i in 0 until homeList.length()) {
                    val home = homeList.getJSONObject(i)
                    val homeId = home.optString("id", "").toLongOrNull() ?: continue
                    val ownerUid = home.optLong("uid", session.userId.toLongOrNull() ?: 0L)

                    val devResp = apiPostEncrypted(
                        session = session,
                        region = region,
                        uri = "/v2/home/home_device_list",
                        dataObj = JSONObject().apply {
                            put("home_owner", ownerUid)
                            put("home_id", homeId)
                            put("limit", 200)
                            put("get_split_device", true)
                            put("support_smart_home", true)
                        }
                    )
                    val devInfo = devResp.optJSONObject("result")?.optJSONArray("device_info")
                    if (devInfo != null) {
                        for (j in 0 until devInfo.length()) {
                            val d = devInfo.getJSONObject(j)
                            val did = d.optString("did", "")
                            if (did.isNotBlank()) rawDevMap[did] = d
                        }
                    }
                }
            }
        }

        // 2. Also query /home/device_list to catch BLE devices not assigned to a specific room
        runCatching {
            val listResp = apiPostEncrypted(
                session = session,
                region = region,
                uri = "/home/device_list",
                dataObj = JSONObject().apply {
                    put("getVirtualModel", true)
                    put("getHuamiDevices", 1)
                    put("get_split_device", false)
                    put("support_smart_home", true)
                }
            )
            val listArr = listResp.optJSONObject("result")?.optJSONArray("list")
            if (listArr != null) {
                for (i in 0 until listArr.length()) {
                    val d = listArr.getJSONObject(i)
                    val did = d.optString("did", "")
                    if (did.isNotBlank() && !rawDevMap.containsKey(did)) {
                        rawDevMap[did] = d
                    }
                }
            }
        }

        val credentialsList = mutableListOf<CuktechDeviceCredentials>()
        for ((did, d) in rawDevMap) {
            val name = d.optString("name", "Mi Device")
            val model = d.optString("model", "")
            val mac = d.optString("mac", "").uppercase()
            val token = d.optString("token", "").lowercase()

            // Fetch BLE beaconkey for CUKTECH or BLE-capable devices
            var bleKey = ""
            if (did.startsWith("blt.") ||
                model.contains("ad1204", ignoreCase = true) ||
                model.contains("cuktech", ignoreCase = true) ||
                name.contains("CUKTECH", ignoreCase = true) ||
                name.contains("酷态科", ignoreCase = true) ||
                token.length == 24
            ) {
                bleKey = runCatching { fetchBleBeaconKey(session, region, did) }.getOrDefault("")
            }

            if (mac.isNotBlank() || token.isNotBlank()) {
                credentialsList.add(
                    CuktechDeviceCredentials(
                        did = did,
                        name = name,
                        model = model,
                        mac = mac,
                        tokenHex = token,
                        bleKeyHex = bleKey,
                        region = region
                    )
                )
            }
        }
        return credentialsList
    }

    fun fetchBleBeaconKey(session: XiaomiAccountSession, region: String, did: String): String {
        val resp = apiPostEncrypted(
            session = session,
            region = region,
            uri = "/v2/device/blt_get_beaconkey",
            dataObj = JSONObject().apply {
                put("did", did)
                put("pdid", 1)
            }
        )
        val resObj = resp.optJSONObject("result") ?: return ""
        return resObj.optString("beaconkey", resObj.optString("key", "")).lowercase().trim()
    }

    private fun apiPostEncrypted(
        session: XiaomiAccountSession,
        region: String,
        uri: String,
        dataObj: JSONObject
    ): JSONObject {
        val params = mapOf("data" to dataObj.toString())
        val (encParams, snonce) = generateEncParams(uri, "POST", session.ssecurity, params)

        val deviceId = generateDeviceId()
        val cookies = mapOf(
            "userId" to session.userId,
            "yetAnotherServiceToken" to session.serviceToken,
            "serviceToken" to session.serviceToken,
            "locale" to "zh_CN",
            "timezone" to "GMT+08:00",
            "is_daylight" to "0",
            "dst_offset" to "0",
            "channel" to "MI_APP_STORE",
            "PassportDeviceId" to deviceId
        )

        val headers = mapOf(
            "User-Agent" to USER_AGENT_MIHOME,
            "Accept-Encoding" to "identity",
            "x-xiaomi-protocal-flag-cli" to "PROTOCAL-HTTP2",
            "content-type" to "application/x-www-form-urlencoded",
            "MIOT-ENCRYPT-ALGORITHM" to "ENCRYPT-RC4",
            "Cookie" to formatCookies(cookies)
        )

        val url = getApiBaseUrl(region) + uri
        val httpResp = httpRequest(
            urlStr = url,
            method = "POST",
            headers = headers,
            body = encodeForm(encParams),
            timeoutMs = 15000
        )

        val decrypted = decryptRc4Response(session.ssecurity, snonce, httpResp.body)
        return JSONObject(decrypted)
    }

    // =========================================================================
    // 4. RC4-DROP[1024] & SIGNATURE HELPERS (EXACT MATCH WITH xiaomi_cloud.py)
    // =========================================================================

    companion object {
        val SUPPORTED_REGIONS = listOf("cn", "de", "us", "ru", "tw", "sg", "in", "i2")

        private const val USER_AGENT_BROWSER =
            "Mozilla/5.0 (Linux; Android 14; Xiaomi 17) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private const val USER_AGENT_APP =
            "Android-7.1.1-1.0.0-ONEPLUS A3010-136-AAB1020304050607 APP/xiaomi.smarthome APPV/62830"
        private const val USER_AGENT_MIHOME =
            "iOS-14.4-6.0.103-iPhone12,3--D7744744F7AF32F0544445285880DD63E47D9BE9-8816080-84A3F44E137B71AE-iPhone"

        fun stripXiaomiPrefix(text: String): String {
            val trimmed = text.trim()
            return if (trimmed.startsWith("&&&START&&&")) {
                trimmed.removePrefix("&&&START&&&").trim()
            } else {
                trimmed
            }
        }

        fun md5Upper(input: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02X".format(it) }
        }

        /**
         * RC4-drop[1024]: Initializes standard 256-byte RC4 state, drops the first
         * 1024 keystream bytes, and XORs payload with the remaining keystream.
         */
        fun rc4Drop1024(key: ByteArray, payload: ByteArray): ByteArray {
            val s = IntArray(256) { it }
            var j = 0
            for (i in 0 until 256) {
                j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
                val tmp = s[i]
                s[i] = s[j]
                s[j] = tmp
            }

            var iIdx = 0
            var jIdx = 0
            // Drop first 1024 bytes
            for (k in 0 until 1024) {
                iIdx = (iIdx + 1) and 0xFF
                jIdx = (jIdx + s[iIdx]) and 0xFF
                val tmp = s[iIdx]
                s[iIdx] = s[jIdx]
                s[jIdx] = tmp
            }

            val out = ByteArray(payload.size)
            for (idx in payload.indices) {
                iIdx = (iIdx + 1) and 0xFF
                jIdx = (jIdx + s[iIdx]) and 0xFF
                val tmp = s[iIdx]
                s[iIdx] = s[jIdx]
                s[jIdx] = tmp
                val kByte = s[(s[iIdx] + s[jIdx]) and 0xFF]
                out[idx] = ((payload[idx].toInt() and 0xFF) xor kByte).toByte()
            }
            return out
        }

        fun signedNonce(ssecurityB64: String, nonceB64: String): String {
            val ssecBytes = Base64.decode(ssecurityB64, Base64.DEFAULT)
            val nonceBytes = Base64.decode(nonceB64, Base64.DEFAULT)
            val md = MessageDigest.getInstance("SHA-256")
            md.update(ssecBytes)
            md.update(nonceBytes)
            return Base64.encodeToString(md.digest(), Base64.NO_WRAP)
        }

        fun generateEncSignature(
            uri: String,
            method: String,
            snonce: String,
            params: Map<String, String>
        ): String {
            val parts = mutableListOf(method.uppercase(), uri)
            for ((k, v) in params) {
                parts.add("$k=$v")
            }
            parts.add(snonce)
            val joined = parts.joinToString("&")
            val sha1 = MessageDigest.getInstance("SHA-1").digest(joined.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(sha1, Base64.NO_WRAP)
        }

        fun generateEncParams(
            uri: String,
            method: String,
            ssecurity: String,
            params: Map<String, String>
        ): Pair<Map<String, String>, String> {
            val randBytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
            val timestampMin = (System.currentTimeMillis() / 60000L).toInt()
            val timeBytes = byteArrayOf(
                ((timestampMin ushr 24) and 0xFF).toByte(),
                ((timestampMin ushr 16) and 0xFF).toByte(),
                ((timestampMin ushr 8) and 0xFF).toByte(),
                (timestampMin and 0xFF).toByte()
            )
            val nonceRaw = randBytes + timeBytes
            val nonce = Base64.encodeToString(nonceRaw, Base64.NO_WRAP)
            val snonce = signedNonce(ssecurity, nonce)
            val snonceKey = Base64.decode(snonce, Base64.DEFAULT)

            val mutable = LinkedHashMap<String, String>()
            for ((k, v) in params) mutable[k] = v
            mutable["rc4_hash__"] = generateEncSignature(uri, method, snonce, mutable)

            for ((k, v) in mutable.entries.toList()) {
                val encryptedBytes = rc4Drop1024(snonceKey, v.toByteArray(Charsets.UTF_8))
                mutable[k] = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
            }

            mutable["signature"] = generateEncSignature(uri, method, snonce, mutable)
            mutable["_nonce"] = nonce
            mutable["ssecurity"] = ssecurity
            return mutable to snonce
        }

        fun decryptRc4Response(ssecurity: String, snonce: String, encryptedBodyB64: String): String {
            val key = Base64.decode(snonce, Base64.DEFAULT)
            val cipherBytes = Base64.decode(encryptedBodyB64.trim(), Base64.DEFAULT)
            val plainBytes = rc4Drop1024(key, cipherBytes)
            return String(plainBytes, Charsets.UTF_8)
        }
    }

    // =========================================================================
    // 5. LIGHTWEIGHT HTTP HELPERS
    // =========================================================================

    private data class HttpResponseData(
        val statusCode: Int,
        val body: String,
        val cookies: Map<String, String>,
        val headers: Map<String, List<String>>,
        val redirectLocation: String?
    )

    private fun httpRequest(
        urlStr: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        followRedirects: Boolean = true,
        timeoutMs: Int = 15000
    ): HttpResponseData {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = followRedirects
            for ((k, v) in headers) {
                setRequestProperty(k, v)
            }
        }

        if (body != null && (method == "POST" || method == "PUT")) {
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body)
                writer.flush()
            }
        }

        val status = conn.responseCode
        val stream = if (status in 200..399) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        val respBody = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
        }.orEmpty()

        val respHeaders = mutableMapOf<String, List<String>>()
        val cookies = mutableMapOf<String, String>()
        for ((k, vList) in conn.headerFields) {
            if (k != null && vList != null) {
                respHeaders[k.lowercase()] = vList
                if (k.equals("Set-Cookie", ignoreCase = true)) {
                    for (cookieHeader in vList) {
                        val firstPart = cookieHeader.substringBefore(";")
                        val eqIdx = firstPart.indexOf('=')
                        if (eqIdx > 0) {
                            val cName = firstPart.substring(0, eqIdx).trim()
                            val cVal = firstPart.substring(eqIdx + 1).trim()
                            if (cVal.isNotEmpty() && !cVal.equals("EXPIRED", ignoreCase = true)) {
                                cookies[cName] = cVal
                            }
                        }
                    }
                }
            }
        }

        val location = conn.getHeaderField("Location")
        conn.disconnect()
        return HttpResponseData(
            statusCode = status,
            body = respBody,
            cookies = cookies,
            headers = respHeaders,
            redirectLocation = location
        )
    }

    private fun encodeForm(params: Map<String, String>): String {
        return params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
    }

    private fun formatCookies(cookies: Map<String, String>): String {
        return cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
    }

    private fun extractQueryParam(url: String, param: String): String? {
        val query = url.substringAfter("?", "")
        if (query.isBlank()) return null
        for (pair in query.split("&")) {
            val k = pair.substringBefore("=")
            val v = pair.substringAfter("=", "")
            if (k == param) return java.net.URLDecoder.decode(v, "UTF-8")
        }
        return null
    }
}
