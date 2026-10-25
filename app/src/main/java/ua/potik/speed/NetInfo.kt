package ua.potik.speed

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import org.json.JSONObject

/** Що за з'єднання зараз: Wi‑Fi (діапазон, швидкість зв'язку, сигнал), мобільний чи дротовий. */
object NetInfo {

    fun current(context: Context): JSONObject {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val o = JSONObject()
        if (caps == null) {
            return o.put("type", "none").put("title", "Немає з'єднання").put("sub", "Увімкніть Wi‑Fi або мобільний інтернет").put("metered", false)
        }
        val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        o.put("metered", metered)
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                o.put("type", "wifi")
                val info = wifiInfo(context, caps)
                val freq = info?.frequency ?: 0
                val band = when {
                    freq >= 5925 -> "6 ГГц"
                    freq >= 4900 -> "5 ГГц"
                    freq >= 2400 -> "2,4 ГГц"
                    else -> null
                }
                o.put("title", if (band != null) "Wi‑Fi $band" else "Wi‑Fi")
                val parts = ArrayList<String>()
                val link = info?.linkSpeed ?: -1
                if (link > 0) parts += "зв'язок $link Мбіт/с"
                val rssi = info?.rssi ?: -127
                if (rssi > -127 && rssi < 0) parts += "сигнал " + when {
                    rssi >= -55 -> "відмінний"
                    rssi >= -67 -> "добрий"
                    rssi >= -75 -> "середній"
                    else -> "слабкий"
                }
                if (vpn) parts += "через VPN"
                o.put("sub", parts.joinToString(", "))
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                o.put("type", "cell")
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val gen = runCatching { generation(tm.dataNetworkType) }.getOrNull()
                o.put("title", if (gen != null) "Мобільний $gen" else "Мобільний інтернет")
                val parts = ArrayList<String>()
                tm.networkOperatorName?.takeIf { it.isNotBlank() }?.let { parts += it }
                if (vpn) parts += "через VPN"
                o.put("sub", parts.joinToString(", "))
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                o.put("type", "eth").put("title", "Дротове з'єднання").put("sub", if (vpn) "через VPN" else "")
            }
            else -> {
                o.put("type", "wifi").put("title", if (vpn) "VPN" else "Інтернет").put("sub", "")
            }
        }
        return o
    }

    @Suppress("DEPRECATION")
    private fun wifiInfo(context: Context, caps: NetworkCapabilities): WifiInfo? {
        if (Build.VERSION.SDK_INT >= 31) (caps.transportInfo as? WifiInfo)?.let { return it }
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return runCatching { wm.connectionInfo }.getOrNull()
    }

    /** Покоління мережі (потрібен дозвіл на стан телефона; без нього — null). */
    private fun generation(type: Int): String? = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_CDMA -> "2G"
        else -> null
    }
}
