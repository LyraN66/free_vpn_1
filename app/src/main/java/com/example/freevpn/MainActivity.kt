package com.example.freevpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.StringReader

class MainActivity : ComponentActivity() {

    private val backend: Backend by lazy { GoBackend(applicationContext) }

    private val tunnel = object : Tunnel {
        override fun getName() = "freevpn"
        override fun onStateChange(newState: Tunnel.State) { onTunnelState(newState) }
    }

    // UI'ın dinleyebilmesi için durum
    private var stateListener: ((Tunnel.State) -> Unit)? = null
    private fun onTunnelState(s: Tunnel.State) { stateListener?.invoke(s) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { VpnScreen() }
            }
        }
    }

    /** Tüneli kurar/kaldırır. IO thread'inde çağrılır. */
    private fun setState(up: Boolean, configText: String): Tunnel.State {
        val config = if (up) Config.parse(BufferedReader(StringReader(configText))) else null
        return backend.setState(tunnel, if (up) Tunnel.State.UP else Tunnel.State.DOWN, config)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun VpnScreen() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val prefs = remember { context.getSharedPreferences("vpn", Context.MODE_PRIVATE) }

        var configText by remember { mutableStateOf(prefs.getString("config", "") ?: "") }
        var state by remember { mutableStateOf(Tunnel.State.DOWN) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }

        DisposableEffect(Unit) {
            stateListener = { s -> state = s }
            onDispose { stateListener = null }
        }

        // Bağlanmadan önce gereken VPN izni
        val pending = remember { mutableStateOf(false) }
        val permLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK && pending.value) {
                pending.value = false
                doConnect(scope, configText, onBusy = { busy = it }, onMsg = { message = it }, onState = { state = it })
            } else {
                pending.value = false
                busy = false
                message = "VPN izni verilmedi."
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Free VPN", style = MaterialTheme.typography.headlineMedium)

            val connected = state == Tunnel.State.UP
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (connected) Color(0xFF14532D) else MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        when (state) {
                            Tunnel.State.UP -> "🟢 Bağlı"
                            Tunnel.State.DOWN -> "⚪ Bağlı değil"
                            else -> "🟡 Geçiş..."
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        if (connected) "Trafiğiniz VPN üzerinden geçiyor." else "Aşağıya bir WireGuard yapılandırması yapıştırın.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            OutlinedTextField(
                value = configText,
                onValueChange = {
                    configText = it
                    prefs.edit().putString("config", it).apply()
                },
                label = { Text("WireGuard yapılandırması (.conf)") },
                placeholder = { Text("[Interface]\nPrivateKey = ...\nAddress = ...\nDNS = ...\n\n[Peer]\nPublicKey = ...\nEndpoint = host:port\nAllowedIPs = 0.0.0.0/0, ::/0") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                minLines = 8,
                enabled = !busy && !connected,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    message = null
                    if (connected) {
                        // Bağlantıyı kes
                        busy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { setState(false, "") }
                                state = Tunnel.State.DOWN
                            } catch (e: Exception) {
                                message = "Kesilemedi: ${e.message}"
                            } finally { busy = false }
                        }
                    } else {
                        if (configText.isBlank()) { message = "Önce bir yapılandırma yapıştırın."; return@Button }
                        // Yapılandırmayı doğrula
                        try {
                            Config.parse(BufferedReader(StringReader(configText)))
                        } catch (e: Exception) {
                            message = "Yapılandırma hatalı: ${e.message}"
                            return@Button
                        }
                        busy = true
                        val prepare: Intent? = VpnService.prepare(context)
                        if (prepare != null) {
                            pending.value = true
                            permLauncher.launch(prepare)
                        } else {
                            doConnect(scope, configText, onBusy = { busy = it }, onMsg = { message = it }, onState = { state = it })
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp, color = Color.White)
                else Text(if (connected) "Bağlantıyı kes" else "Bağlan")
            }

            message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            HorizontalDivider()
            Text("Ücretsiz sunucu yapılandırması nereden bulunur?", style = MaterialTheme.typography.titleSmall)
            Text(
                "• VPN Gate (vpngate.net) WireGuard/OpenVPN yapılandırmaları paylaşır.\n" +
                    "• Ücretsiz WireGuard config paylaşan siteler.\n\n" +
                    "Uyarı: Tanımadığınız kişilerin işlettiği ücretsiz sunucular trafiğinizi görebilir. " +
                    "Bankacılık gibi hassas işlerde kullanmayın.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    // İki kez çağrı hatasını önlemek için tek giriş noktası
    private fun doConnect(
        scope: kotlinx.coroutines.CoroutineScope,
        configText: String,
        onBusy: (Boolean) -> Unit,
        onMsg: (String?) -> Unit,
        onState: (Tunnel.State) -> Unit,
    ) {
        onBusy(true)
        scope.launch {
            try {
                val s = withContext(Dispatchers.IO) { setState(true, configText) }
                onState(s)
                onMsg(null)
            } catch (e: Exception) {
                onMsg("Bağlanılamadı: ${e.message}")
                onState(Tunnel.State.DOWN)
            } finally {
                onBusy(false)
            }
        }
    }

}
