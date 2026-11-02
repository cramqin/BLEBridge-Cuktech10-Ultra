package com.example.ui.cuktech

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.cuktech.ble.CuktechAuthState
import com.example.cuktech.ble.CuktechBleManager
import com.example.cuktech.ble.DiscoveredCuktechDevice
import com.example.cuktech.cloud.QrLoginSession
import com.example.cuktech.cloud.QrPollResult
import com.example.cuktech.cloud.TwoFactorChallenge
import com.example.cuktech.cloud.XiaomiCloudClient
import com.example.cuktech.cloud.XiaomiLoginResult
import com.example.cuktech.security.CuktechDeviceCredentials
import com.example.cuktech.security.SecureCredentialStore
import com.example.cuktech.security.XiaomiAccountSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

@Composable
fun XiaomiAccountLoginDialog(
    cloudClient: XiaomiCloudClient,
    credentialStore: SecureCredentialStore,
    onDismiss: () -> Unit,
    onDevicesSynced: (List<CuktechDeviceCredentials>) -> Unit
) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) } // 0 = QR Login, 1 = Password Login, 2 = Manual/JSON
    var selectedRegion by remember { mutableStateOf("cn") }

    // QR Login state
    var qrSession by remember { mutableStateOf<QrLoginSession?>(null) }
    var qrBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var qrStatusText by remember { mutableStateOf("Generating Xiaomi Cloud QR session...") }
    var isQrLoading by remember { mutableStateOf(false) }

    // Password Login state
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoggingIn by remember { mutableStateOf(false) }
    var loginStatusText by remember { mutableStateOf("") }

    // 2FA Challenge state
    var active2faChallenge by remember { mutableStateOf<TwoFactorChallenge?>(null) }
    var otpCode by remember { mutableStateOf("") }
    var useEmail2fa by remember { mutableStateOf(false) }
    var showWebView2fa by remember { mutableStateOf(false) }

    // Manual/JSON credential entry
    var manualMac by remember { mutableStateOf("") }
    var manualToken by remember { mutableStateOf("") }
    var manualBleKey by remember { mutableStateOf("") }

    suspend fun handleAuthenticatedSession(session: XiaomiAccountSession) {
        credentialStore.saveSession(session)
        loginStatusText = "Logged in (UID: ${session.userId}). Fetching devices from Xiaomi Cloud..."
        qrStatusText = loginStatusText
        val devRes = cloudClient.fetchDevicesAndBleKeys(session, searchAllRegionsIfEmpty = true)
        devRes.onSuccess { list ->
            if (list.isNotEmpty()) {
                credentialStore.saveDevices(list)
                onDevicesSynced(list)
            } else {
                onDevicesSynced(emptyList())
            }
        }.onFailure {
            onDevicesSynced(emptyList())
        }
        onDismiss()
    }

    // Automatically start QR login when tab 0 is opened
    LaunchedEffect(selectedTab, selectedRegion) {
        if (selectedTab == 0) {
            isQrLoading = true
            qrBitmap = null
            qrSession = null
            qrStatusText = "Creating secure QR code session for region [${selectedRegion.uppercase()}]..."
            val res = cloudClient.startQrLogin(selectedRegion)
            isQrLoading = false
            res.onSuccess { session ->
                qrSession = session
                qrStatusText = "Scan with Mi Home or Xiaomi account to confirm login"
                // Download QR image bitmap
                withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = URL(session.qrImageUrl).openStream().use { it.readBytes() }
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        qrBitmap = bmp?.asImageBitmap()
                    }
                }
                // Start polling loop
                while (isActive && qrSession == session) {
                    when (val poll = cloudClient.pollQrLogin(session, selectedRegion)) {
                        is QrPollResult.Waiting -> {
                            // Continue waiting
                        }
                        is QrPollResult.Timeout -> {
                            qrStatusText = "QR Code expired. Tap refresh to reload."
                            break
                        }
                        is QrPollResult.Requires2FA -> {
                            active2faChallenge = poll.challenge
                            qrStatusText = "Xiaomi Account 2-Factor Authentication (2FA) required"
                            break
                        }
                        is QrPollResult.Success -> {
                            handleAuthenticatedSession(poll.session)
                            break
                        }
                        is QrPollResult.Error -> {
                            qrStatusText = "QR status: ${poll.message}"
                            break
                        }
                    }
                }
            }.onFailure { e ->
                qrStatusText = "Failed to create QR code: ${e.message}"
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 680.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Xiaomi Cloud Login & Sync",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                // Region warning banner if existing logged-in account is not Mainland China
                val currentSession = credentialStore.session.value
                if (currentSession != null && !currentSession.region.equals("cn", ignoreCase = true)) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Warning: Active account region is [${currentSession.region.uppercase()}]. CUKTECH 10 Ultra is a Mainland China device (requires Mainland China 'CN' region in Mi Home).",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                // If 2FA Challenge is active, show 2FA verification panel
                val challenge = active2faChallenge
                if (challenge != null) {
                    TwoFactorChallengeSection(
                        challenge = challenge,
                        otpCode = otpCode,
                        onOtpChange = { otpCode = it },
                        useEmail = useEmail2fa,
                        onToggleChannel = { useEmail2fa = it },
                        showWebView = showWebView2fa,
                        onToggleWebView = { showWebView2fa = it },
                        statusText = loginStatusText,
                        onSendCode = {
                            scope.launch {
                                loginStatusText = "Requesting verification code..."
                                val r = cloudClient.send2faCode(challenge, useEmail2fa)
                                loginStatusText = if (r.isSuccess) {
                                    "Code sent! Please enter the 6-digit code received."
                                } else {
                                    val errStr = r.exceptionOrNull()?.message ?: "Failed to send code. Try web verification below."
                                    XiaomiCloudClient.translateXiaomiError(errStr)
                                }
                            }
                        },
                        onVerifyCode = {
                            scope.launch {
                                isLoggingIn = true
                                loginStatusText = "Verifying 2FA code..."
                                when (val res = cloudClient.verify2faCode(challenge, otpCode, useEmail2fa)) {
                                    is XiaomiLoginResult.Success -> handleAuthenticatedSession(res.session)
                                    is XiaomiLoginResult.Error -> loginStatusText = XiaomiCloudClient.translateXiaomiError(res.message)
                                    is XiaomiLoginResult.Requires2FA -> active2faChallenge = res.challenge
                                }
                                isLoggingIn = false
                            }
                        },
                        onWebViewFinishUrl = { finishUrl, webCookies ->
                            scope.launch {
                                isLoggingIn = true
                                loginStatusText = "Web 2FA complete, extracting encrypted session..."
                                val mergedCookies = challenge.identitySessionCookies + webCookies
                                when (val res = cloudClient.complete2faFinishRedirect(
                                    finishUrl,
                                    mergedCookies,
                                    challenge.deviceId,
                                    selectedRegion
                                )) {
                                    is XiaomiLoginResult.Success -> handleAuthenticatedSession(res.session)
                                    is XiaomiLoginResult.Error -> loginStatusText = XiaomiCloudClient.translateXiaomiError(res.message)
                                    is XiaomiLoginResult.Requires2FA -> active2faChallenge = res.challenge
                                }
                                isLoggingIn = false
                            }
                        },
                        onBack = {
                            active2faChallenge = null
                            showWebView2fa = false
                        }
                    )
                } else {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("QR Code", fontSize = 13.sp) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Password", fontSize = 13.sp) }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("Manual / Import", fontSize = 13.sp) }
                        )
                    }

                    when (selectedTab) {
                        0 -> {
                            // QR Code Login Tab
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(210.dp)
                                        .background(Color.White, RoundedCornerShape(12.dp))
                                        .padding(10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    when {
                                        isQrLoading -> CircularProgressIndicator()
                                        qrBitmap != null -> Image(
                                            bitmap = qrBitmap!!,
                                            contentDescription = "Xiaomi Cloud QR Code",
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        else -> Text(
                                            text = "Tap refresh to load QR code",
                                            color = Color.DarkGray,
                                            fontSize = 12.sp
                                        )
                                    }
                                }

                                Text(
                                    text = qrStatusText,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            qrBitmap = null
                                            qrSession = null
                                        }
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Refresh QR Code")
                                    }
                                }
                            }
                        }

                        1 -> {
                            // Username & Password Login Tab
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = username,
                                    onValueChange = { username = it },
                                    label = { Text("Xiaomi ID / Phone / Email") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                var passwordVisible by remember { mutableStateOf(false) }
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = { Text("Password") },
                                    singleLine = true,
                                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                            Icon(
                                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                                contentDescription = if (passwordVisible) "Hide password" else "Show password"
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                if (loginStatusText.isNotBlank()) {
                                    Text(
                                        text = loginStatusText,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Button(
                                    onClick = {
                                        if (username.isBlank() || password.isBlank()) {
                                            loginStatusText = "Please enter username and password"
                                            return@Button
                                        }
                                        scope.launch {
                                            isLoggingIn = true
                                            loginStatusText = "Authenticating with Xiaomi Cloud..."
                                            when (val res = cloudClient.loginWithPassword(username, password, selectedRegion)) {
                                                is XiaomiLoginResult.Success -> handleAuthenticatedSession(res.session)
                                                is XiaomiLoginResult.Requires2FA -> {
                                                    active2faChallenge = res.challenge
                                                    loginStatusText = "2-Factor Authentication required. Please complete verification."
                                                }
                                                is XiaomiLoginResult.Error -> {
                                                    loginStatusText = XiaomiCloudClient.translateXiaomiError(res.message)
                                                }
                                            }
                                            isLoggingIn = false
                                        }
                                    },
                                    enabled = !isLoggingIn,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (isLoggingIn) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text("Sign In & Auto-Extract BLE Key")
                                }
                            }
                        }

                        2 -> {
                            // Manual / JSON Import Tab
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val ok = credentialStore.tryAutoImportLocalCredentialsFile()
                                        if (ok) {
                                            onDevicesSynced(credentialStore.devices.value)
                                            onDismiss()
                                        } else {
                                            loginStatusText = "Local cuktech_credentials.json not found or already imported"
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Import Local cuktech_credentials.json")
                                }

                                OutlinedTextField(
                                    value = manualMac,
                                    onValueChange = { manualMac = it },
                                    label = { Text("Charger Bluetooth MAC (e.g. AA:BB:CC:11:22:33)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = manualToken,
                                    onValueChange = { manualToken = it },
                                    label = { Text("12-Byte MiOT Token (24 Hex characters)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = manualBleKey,
                                    onValueChange = { manualBleKey = it },
                                    label = { Text("16-Byte BLE Key / beaconkey (Optional, 32 Hex characters)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                if (loginStatusText.isNotBlank()) {
                                    Text(loginStatusText, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                }

                                Button(
                                    onClick = {
                                        val cleanMac = manualMac.trim().uppercase()
                                        val cleanTok = manualToken.trim().lowercase()
                                        if (cleanMac.length < 11 || cleanTok.length != 24) {
                                            loginStatusText = "Please provide valid MAC address and 24-hex token"
                                            return@Button
                                        }
                                        val cred = CuktechDeviceCredentials(
                                            did = "manual_${cleanMac.replace(":", "")}",
                                            name = "CUKTECH 10 Ultra",
                                            model = "njcuk.fitting.ad1204",
                                            mac = cleanMac,
                                            tokenHex = cleanTok,
                                            bleKeyHex = manualBleKey.trim().lowercase(),
                                            region = selectedRegion
                                        )
                                        credentialStore.addOrUpdateDevice(cred)
                                        onDevicesSynced(credentialStore.devices.value)
                                        onDismiss()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Save to Android Keystore")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TwoFactorChallengeSection(
    challenge: TwoFactorChallenge,
    otpCode: String,
    onOtpChange: (String) -> Unit,
    useEmail: Boolean,
    onToggleChannel: (Boolean) -> Unit,
    showWebView: Boolean,
    onToggleWebView: (Boolean) -> Unit,
    statusText: String,
    onSendCode: () -> Unit,
    onVerifyCode: () -> Unit,
    onWebViewFinishUrl: (String, Map<String, String>) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Xiaomi Account 2-Factor Authentication (2FA)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = buildString {
                append("Two-factor authentication is enabled for this account.")
                if (challenge.maskedPhone.isNotBlank()) append(" Linked Phone: ${challenge.maskedPhone}")
                if (challenge.maskedEmail.isNotBlank()) append(" Linked Email: ${challenge.maskedEmail}")
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !useEmail,
                onClick = { onToggleChannel(false) },
                label = { Text("SMS / Authenticator") }
            )
            FilterChip(
                selected = useEmail,
                onClick = { onToggleChannel(true) },
                label = { Text("Email Code") }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = otpCode,
                onValueChange = onOtpChange,
                label = { Text("6-Digit Verification Code") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = onSendCode) {
                Text("Send Code", fontSize = 12.sp)
            }
        }

        if (statusText.isNotBlank()) {
            Text(text = statusText, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text("Back")
            }
            Button(
                onClick = onVerifyCode,
                enabled = otpCode.trim().length >= 4,
                modifier = Modifier.weight(1f)
            ) {
                Text("Verify & Sign In")
            }
        }

        TextButton(
            onClick = { onToggleWebView(!showWebView) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (showWebView) "Hide embedded web verification" else "Require captcha or push approval? Expand web verification")
        }

        if (showWebView) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .background(Color.White, RoundedCornerShape(8.dp))
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            for ((k, v) in challenge.identitySessionCookies) {
                                cm.setCookie("https://account.xiaomi.com", "$k=$v; Domain=.xiaomi.com; Path=/")
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val url = request?.url?.toString() ?: return false
                                    if (url.contains("sts.api.io.mi.com/sts") || url.contains("serviceToken=")) {
                                        val rawCookie = cm.getCookie("https://account.xiaomi.com").orEmpty() +
                                            "; " + cm.getCookie(url).orEmpty()
                                        val cookieMap = parseCookieString(rawCookie)
                                        onWebViewFinishUrl(url, cookieMap)
                                        return true
                                    }
                                    return false
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    val u = url ?: return
                                    val rawCookie = cm.getCookie("https://sts.api.io.mi.com").orEmpty() +
                                        "; " + cm.getCookie("https://account.xiaomi.com").orEmpty()
                                    val cookieMap = parseCookieString(rawCookie)
                                    if (cookieMap.containsKey("serviceToken") || u.contains("sts.api.io.mi.com/sts")) {
                                        onWebViewFinishUrl(u, cookieMap)
                                    }
                                }
                            }
                            loadUrl(challenge.notificationUrl)
                        }
                    }
                )
            }
        }
    }
}

private fun parseCookieString(raw: String): Map<String, String> {
    val map = mutableMapOf<String, String>()
    for (part in raw.split(";")) {
        val trimmed = part.trim()
        val idx = trimmed.indexOf('=')
        if (idx > 0) {
            map[trimmed.substring(0, idx).trim()] = trimmed.substring(idx + 1).trim()
        }
    }
    return map
}

/**
 * Prominent "SEARCH & PAIR CUKTECH 10 ULTRA" discovery and pairing modal.
 */
@Composable
fun CuktechDiscoveryPairingDialog(
    bleManager: CuktechBleManager,
    onDismiss: () -> Unit,
    onSelectAndConnect: (DiscoveredCuktechDevice) -> Unit
) {
    val discovered by bleManager.discoveredDevices.collectAsState()
    val authState by bleManager.authState.collectAsState()
    val statusMsg by bleManager.statusMessage.collectAsState()
    val savedCreds by bleManager.credentialStore.devices.collectAsState()

    LaunchedEffect(Unit) {
        bleManager.startSearchForCuktechChargers(10000L)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 600.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.BluetoothSearching,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Search & Pair CUKTECH 10 Ultra",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Text(
                    text = statusMsg,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (authState == CuktechAuthState.SCANNING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                // Saved Xiaomi Cloud credentials quick-connect section
                if (savedCreds.isNotEmpty()) {
                    Text(
                        text = "Synced Cloud Devices (Tap to Connect)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    savedCreds.forEach { cred ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    bleManager.connectToCharger(cred.mac)
                                    onDismiss()
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(cred.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(
                                        text = "MAC: ${cred.mac} · Token: ${if (cred.hasValidToken) "Ready" else "Missing"}",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Button(
                                    onClick = {
                                        bleManager.connectToCharger(cred.mac)
                                        onDismiss()
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("Connect", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                Text(
                    text = "Discovered Bluetooth Peripherals (${discovered.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (discovered.isEmpty()) {
                        Text(
                            text = "No 0xFE95 broadcast packets detected yet. Tip: If the charger display is asleep, press its button or plug in a cable to wake BLE broadcast, or select a saved credential above.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        discovered.forEach { dev ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectAndConnect(dev) },
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(dev.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            if (dev.isCuktech10Ultra) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                                    Text("0x660E", fontSize = 10.sp)
                                                }
                                            }
                                            if (dev.matchedCredentials != null) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Badge(containerColor = Color(0xFF10B981)) {
                                                    Text("Matched", color = Color.White, fontSize = 10.sp)
                                                }
                                            }
                                        }
                                        Text(
                                            text = "MAC: ${dev.mac} · RSSI: ${dev.rssi} dBm",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Button(
                                        onClick = { onSelectAndConnect(dev) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("Pair & Connect", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = { bleManager.startSearchForCuktechChargers(10000L) }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Rescan")
                    }
                }
            }
        }
    }
}
