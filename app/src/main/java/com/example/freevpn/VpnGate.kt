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
    val continent: String = "",
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

    /** Her kıta için en hızlı sunucu, hızdan yavaşa sıralı. */
    fun bestPerContinent(servers: List<VpnServer> = fetchServers()): List<VpnServer> {
        return servers
            .groupBy { continentOf(it.countryShort) }
            .mapNotNull { (continent, list) ->
                list.maxByOrNull { it.speedBps }?.copy(continent = continent)
            }
            .sortedByDescending { it.speedBps }
    }

    /** ISO ülke kodundan kıta adı (Türkçe). */
    fun continentOf(cc: String): String = when (cc.uppercase()) {
        // Avrupa
        "AL","AD","AT","BY","BE","BA","BG","HR","CY","CZ","DK","EE","FI","FR","DE","GR","HU",
        "IS","IE","IT","LV","LI","LT","LU","MT","MD","MC","ME","NL","MK","NO","PL","PT","RO",
        "RU","SM","RS","SK","SI","ES","SE","CH","UA","GB","VA","XK" -> "Avrupa"
        // Asya
        "AF","AM","AZ","BH","BD","BT","BN","KH","CN","GE","HK","IN","ID","IR","IQ","IL","JP",
        "JO","KZ","KW","KG","LA","LB","MO","MY","MV","MN","MM","NP","KP","OM","PK","PS","PH",
        "QA","SA","SG","KR","LK","SY","TW","TJ","TH","TL","TR","TM","AE","UZ","VN","YE" -> "Asya"
        // Kuzey Amerika
        "CA","US","MX","GT","BZ","SV","HN","NI","CR","PA","CU","DO","HT","JM","BS","BB","TT" -> "Kuzey Amerika"
        // Güney Amerika
        "AR","BO","BR","CL","CO","EC","GY","PY","PE","SR","UY","VE" -> "Güney Amerika"
        // Afrika
        "DZ","AO","BJ","BW","BF","BI","CM","CV","CF","TD","KM","CG","CD","CI","DJ","EG","GQ",
        "ER","ET","GA","GM","GH","GN","GW","KE","LS","LR","LY","MG","MW","ML","MR","MU","MA",
        "MZ","NA","NE","NG","RW","SN","SC","SL","SO","ZA","SS","SD","SZ","TZ","TG","TN","UG",
        "ZM","ZW" -> "Afrika"
        // Okyanusya
        "AU","NZ","FJ","PG","WS","TO","VU","SB","NC" -> "Okyanusya"
        else -> "Diğer"
    }

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
