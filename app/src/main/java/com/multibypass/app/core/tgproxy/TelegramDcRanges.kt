package com.multibypass.app.core.tgproxy

/**
 * Определение и быстрая битовая классификация подсетей дата-центров Telegram (AS44907, AS62041).
 * Используется в прозрачном режиме sTGWS для перехвата прямого трафика Telegram.
 */
object TelegramDcRanges {

    private data class CidrSubnet(val baseIp: Int, val mask: Int)

    // Официальные подсети дата-центров Telegram (DC 1..5 и DC 203)
    private val CIDR_STRINGS = listOf(
        "149.154.160.0/20",
        "91.108.4.0/22",
        "91.108.8.0/21",
        "91.108.16.0/21",
        "91.108.56.0/22",
        "95.161.64.0/20",
        "91.105.192.0/23",
        "185.76.151.0/24"
    )

    private val subnets: List<CidrSubnet> = CIDR_STRINGS.map { parseCidr(it) }

    fun isTelegramPort(port: Int): Boolean {
        return port == 443 || port == 80 || port == 5222
    }

    fun isTelegramIp(ipString: String): Boolean {
        val ipInt = ipToInt(ipString) ?: return false
        return isTelegramIpInt(ipInt)
    }

    fun isTelegramIpInt(ipInt: Int): Boolean {
        for (subnet in subnets) {
            if ((ipInt and subnet.mask) == subnet.baseIp) {
                return true
            }
        }
        return false
    }

    fun isTelegramTarget(ipString: String, port: Int): Boolean {
        return isTelegramPort(port) && isTelegramIp(ipString)
    }

    private fun parseCidr(cidr: String): CidrSubnet {
        val parts = cidr.split("/")
        val ipInt = ipToInt(parts[0]) ?: 0
        val prefix = parts.getOrNull(1)?.toIntOrNull() ?: 32
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        return CidrSubnet(ipInt and mask, mask)
    }

    private fun ipToInt(ip: String): Int? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        var result = 0
        for (p in parts) {
            val byte = p.toIntOrNull() ?: return null
            if (byte !in 0..255) return null
            result = (result shl 8) or byte
        }
        return result
    }
}
