package com.example.freevpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import de.blinkt.openvpn.api.IOpenVPNAPIService
import de.blinkt.openvpn.api.IOpenVPNStatusCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val OPENVPN_PKG = "de.blinkt.openvpn"

class MainActivity : ComponentActivity() {

    private var service: IOpenVPNAPIService? = null
    private var bound = false

    // UI durumu
    private val uiStatus = mutableStateOf("Hazır")
    private val uiServer = mutableStateOf<String?>(null)
    private val uiBusy = mutableStateOf(false)
    private val uiConnected = mutableStateOf(false)
    private val uiNeedInstall = mutableStateOf(false)
    private val uiChoices = mutableStateOf<List<VpnServer>>(emptyList())
    private var pendingServer: VpnServer? = null

    private lateinit var apiPermLauncher: ActivityResultLauncher<Intent>
    private lateinit var vpnPermLauncher: ActivityResultLauncher<Intent>

    private val statusCallback = object : IOpenVPNStatusCallback.Stub() {
        override fun newStatus(uuid: String?, state: String?, message: String?, level: String?) {
            runOnUiThread {
                // Resmi arayüzün kullandığı gibi, durum için `level` değerine bakıyoruz.
                when (level) {
                    "LEVEL_CONNECTED" -> {
                        uiStatus.value = "🟢 Bağlı"
                        uiConnected.value = true
                        uiBusy.value = false
                    }
                    "LEVEL_NOTCONNECTED" -> {
                        uiStatus.value = "⚪ Bağlı değil"
                        uiConnected.value = false
                        uiBusy.value = false
                    }
                    "LEVEL_AUTH_FAILED" -> {
                        uiStatus.value = "❌ Kimlik doğrulama başarısız (başka sunucu deneyin)"
                        uiConnected.value = false
                        uiBusy.value = false
                    }
                    "LEVEL_VPNPAUSED" -> {
                        uiStatus.value = "⏸ Duraklatıldı"
                        uiConnected.value = false
                    }
                    "LEVEL_CONNECTING_SERVER_REPLIED",
                    "LEVEL_CONNECTING_NO_SERVER_REPLY_YET",
                    "LEVEL_START",
                    "LEVEL_NONETWORK",
                    "LEVEL_WAITING_FOR_USER_INPUT" -> {
                        uiStatus.value = "🟡 Bağlanılıyor…"
                        uiConnected.value = false
                        uiBusy.value = true
                    }
                    else -> {
                        if (!message.isNullOrBlank()) uiStatus.value = "${state ?: ""} $message".trim()
                    }
                }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IOpenVPNAPIService.Stub.asInterface(binder)
            bound = true
            try {
                service?.registerStatusCallback(statusCallback)
            } catch (e: Exception) { /* yok say */ }
            onBound()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private var pendingConnect = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        apiPermLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK) afterApiPermission()
            else fail("OpenVPN'e erişim izni verilmedi.")
        }
        vpnPermLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK) afterVpnPermission()
            else fail("VPN izni verilmedi.")
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (bound) { service?.unregisterStatusCallback(statusCallback); unbindService(connection) }
        } catch (e: Exception) { /* yok say */ }
    }

    // ---- Akış ----

    /** Sunucuları indirir ve her kıtanın en hızlısını seçtirmek için listeler. */
    private fun loadChoices() {
        if (!isOpenVpnInstalled()) { uiNeedInstall.value = true; return }
        uiNeedInstall.value = false
        uiBusy.value = true
        uiServer.value = null
        uiChoices.value = emptyList()
        uiStatus.value = "Sunucular alınıyor…"
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { VpnGate.bestPerContinent() }
            uiBusy.value = false
            if (list.isEmpty()) { fail("Ücretsiz sunucu bulunamadı. İnternetinizi kontrol edip tekrar deneyin."); return@launch }
            uiChoices.value = list
            uiStatus.value = "Bir bölge seçin"
        }
    }

    /** Seçilen sunucuya bağlanma akışını başlatır. */
    private fun chooseServer(server: VpnServer) {
        pendingServer = server
        uiChoices.value = emptyList()
        uiServer.value = buildString {
            append(server.continent).append(" • ").append(server.country.ifBlank { server.countryShort })
            if (server.speedMbps > 0) append(" • ~${server.speedMbps} Mbps")
            append(" • ping ${server.ping} ms")
        }
        uiBusy.value = true
        uiStatus.value = "OpenVPN'e bağlanılıyor…"
        pendingConnect = true
        if (service != null) onBound()
        else {
            val intent = Intent(IOpenVPNAPIService::class.java.name).setPackage(OPENVPN_PKG)
            val ok = bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!ok) fail("OpenVPN servisine bağlanılamadı.")
        }
    }

    private fun onBound() {
        if (!pendingConnect) return
        try {
            val p = service?.prepare(packageName)
            if (p != null) apiPermLauncher.launch(p) else afterApiPermission()
        } catch (e: Exception) {
            fail("İzin hatası: ${e.message}")
        }
    }

    private fun afterApiPermission() {
        try {
            val p = service?.prepareVPNService()
            if (p != null) vpnPermLauncher.launch(p) else afterVpnPermission()
        } catch (e: Exception) {
            fail("VPN izni hatası: ${e.message}")
        }
    }

    private fun afterVpnPermission() {
        val srv = pendingServer ?: run { fail("Sunucu seçilmedi."); return }
        uiStatus.value = "Bağlanılıyor…"
        pendingConnect = false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { service?.startVPN(srv.ovpn) }
            } catch (e: Exception) {
                fail("Başlatılamadı: ${e.message}")
            }
        }
    }

    private fun disconnect() {
        uiBusy.value = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { service?.disconnect() }
                uiConnected.value = false
                uiStatus.value = "Bağlantı kesildi"
            } catch (e: Exception) {
                uiStatus.value = "Kesilemedi: ${e.message}"
            } finally { uiBusy.value = false }
        }
    }

    private fun fail(msg: String) {
        pendingConnect = false
        uiBusy.value = false
        uiStatus.value = "❌ $msg"
    }

    private fun isOpenVpnInstalled(): Boolean = try {
        packageManager.getPackageInfo(OPENVPN_PKG, 0); true
    } catch (e: Exception) { false }

    private fun openStore() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$OPENVPN_PKG"))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/$OPENVPN_PKG/"))
        try { startActivity(market) } catch (e: Exception) { startActivity(web) }
    }

    // ---- Arayüz ----

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        val status by uiStatus
        val server by uiServer
        val busy by uiBusy
        val connected by uiConnected
        val needInstall by uiNeedInstall
        val choices by uiChoices

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Free VPN", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Her bölgenin en hızlı ücretsiz sunucusunu bulur, seçtiğinize bağlanır (VPN Gate).",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (connected) Color(0xFF14532D) else MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(status, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    server?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                }
            }

            if (needInstall) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("OpenVPN for Android gerekli", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Bağlantıyı ücretsiz, reklamsız ve açık kaynaklı OpenVPN for Android yapar. " +
                                "Bir kez kurmanız yeterli, sonra bu uygulamadan bağlanırsınız.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { openStore() }, modifier = Modifier.fillMaxWidth()) {
                            Text("OpenVPN for Android'i kur")
                        }
                    }
                }
            }

            // Kıta seçim listesi
            if (choices.isNotEmpty() && !connected) {
                Text("Her bölgenin en hızlı sunucusu", style = MaterialTheme.typography.titleSmall)
                choices.forEach { srv ->
                    ElevatedCard(
                        onClick = { chooseServer(srv) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(srv.continent, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    srv.country.ifBlank { srv.countryShort },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                if (srv.speedMbps > 0) Text("~${srv.speedMbps} Mbps", style = MaterialTheme.typography.titleSmall)
                                Text("ping ${srv.ping} ms", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = { uiChoices.value = emptyList(); uiStatus.value = "Hazır" },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("İptal") }
            } else {
                Button(
                    onClick = { if (connected) disconnect() else loadChoices() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp, color = Color.White)
                    else Text(
                        if (connected) "Bağlantıyı kes" else "Sunucuları bul ve seç",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            HorizontalDivider()
            Text(
                "Uyarı: VPN Gate sunucularını gönüllüler işletir ve trafiğinizi görebilirler. " +
                    "Bankacılık gibi hassas işlemler için kullanmayın.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}
