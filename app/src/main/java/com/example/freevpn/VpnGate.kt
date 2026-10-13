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
    val proto: String = "",      // "UDP" / "TCP"
    val score: Long = 0,         // VPN Gate kalite puanı
) {
    val speedMbps: Int get() = (speedBps / 1_000_000L).toInt()
    val pingMs: Int get() = ping.toIntOrNull() ?: 9999
    val isUdp: Boolean get() = proto == "UDP"
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
                    proto = detectProto(ovpn),
                    score = p[2].trim().toLongOrNull() ?: 0L,
                )
            )
        }
        return servers
    }

    /** OpenVPN yapılandırmasından protokolü (UDP/TCP) okur. */
    private fun detectProto(ovpn: String): String {
        val m = Regex("(?im)^\\s*proto\\s+(udp|tcp)").find(ovpn) ?: return ""
        return m.groupValues[1].uppercase()
    }

    /** Türkiye'den gezinme hızına göre bölge yakınlık sırası (küçük = yakın). */
    private fun regionRank(continent: String): Int = when (continent) {
        "Avrupa" -> 0
        "Asya" -> 1
        "Afrika" -> 2
        "Kuzey Amerika" -> 3
        "Güney Amerika" -> 4
        "Okyanusya" -> 5
        else -> 6
    }

    /** Bir sunucunun gezinme için kalitesi (büyük = iyi): UDP öncelikli, sonra puan. */
    private fun quality(s: VpnServer): Long = (if (s.isUdp) 1_000_000_000L else 0L) + s.score

    /**
     * Her bölge (kıta) için en iyi sunucuyu seçer:
     * UDP ve yüksek puan önceliğiyle; liste Türkiye'ye yakınlık sırasına göre döner.
     */
    fun bestPerContinent(servers: List<VpnServer> = fetchServers()): List<VpnServer> {
        return servers
            .groupBy { continentOf(it.countryShort) }
            .mapNotNull { (continent, list) ->
                list.maxByOrNull { quality(it) }?.copy(continent = continent)
            }
            .sortedBy { regionRank(it.continent) }
    }

    /** En iyi tek sunucu (en yakın bölgenin en iyisi). */
    fun best(): VpnServer? = bestPerContinent().firstOrNull()

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
