package com.example.freevpn

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL

/** VPN Gate'ten alınan tek bir ücretsiz OpenVPN sunucusu. */
data class VpnServer(
    val hostName: String,
    val country: String,
    val countryShort: String,
    val speedBps: Long,
    val ping: String,
    val ovpn: String,
) {
    val speedMbps: Int get() = (speedBps / 1_000_000L).toInt()
}

object VpnGate {
    private const val API_HTTPS = "https://www.vpngate.net/api/iphone/"
    private const val API_HTTP = "http://www.vpngate.net/api/iphone/"

    /** Sunucu listesini indirir, en hızlıdan yavaşa sıralar. IO thread'inde çağrılır. */
    fun fetchServers(): List<VpnServer> {
        val csv = download(API_HTTPS) ?: download(API_HTTP) ?: return emptyList()
        val servers = ArrayList<VpnServer>()
        for (line in csv.lineSequence()) {
            if (line.isBlank() || line.startsWith("*") || line.startsWith("#")) continue
            val p = line.split(",")
            if (p.size < 15) continue
            val b64 = p.last().trim()
            if (b64.isEmpty()) continue
            val ovpn = try {
                String(Base64.decode(b64, Base64.DEFAULT))
            } catch (e: Exception) {
                continue
            }
            if (!ovpn.contains("remote ")) continue
            val speed = p[4].trim().toLongOrNull() ?: 0L
            servers.add(
                VpnServer(
                    hostName = p[0].trim(),
                    country = p[5].trim(),
                    countryShort = p[6].trim(),
                    speedBps = speed,
                    ping = p[3].trim(),
                    ovpn = ovpn,
                )
            )
        }
        return servers.sortedByDescending { it.speedBps }
    }

    /** En hızlı sunucu. */
    fun best(): VpnServer? = fetchServers().firstOrNull()

    private fun download(urlStr: String): String? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "FreeVPN-Android")
            }
            conn.use {
                if (it.responseCode != 200) return null
                it.inputStream.bufferedReader().readText()
            }
        } catch (e: Exception) {
            null
        }
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T {
        try {
            return block(this)
        } finally {
            disconnect()
        }
    }
}
