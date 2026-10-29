package com.multibypass.app.core.tgproxy

/**
 * Определение и быстрая битовая классификация подсетей дата-центров Telegram (AS44907, AS62041).
 * Используется в прозрачном режиме sTGWS для перехвата прямого трафика Telegram.
 */
object TelegramDcRanges {

    private data class CidrSubnet(val baseIp: Int, val mask: Int)
    private data class SubnetWithDc(val subnet: CidrSubnet, val dc: Int)

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

    // Таблица соответствия IP/подсетей конкретным дата-центрам Telegram (DC 1..5)
    // Конкретные хосты (/32) проверяются первыми перед диапазонами
    private val DC_SUBNETS: List<SubnetWithDc> = listOf(
        // Точечные адреса DC
        parseCidr("149.154.175.50/32") toDc 1,
        parseCidr("149.154.167.51/32") toDc 2,
        parseCidr("149.154.167.50/32") toDc 2,
        parseCidr("149.154.175.100/32") toDc 3,
        parseCidr("149.154.167.91/32") toDc 4,
        parseCidr("149.154.171.5/32") toDc 5,
        // Подсети DC 1 (Miami)
        parseCidr("149.154.160.0/22") toDc 1,
        // Подсети DC 2 (Amsterdam)
        parseCidr("149.154.164.0/22") toDc 2,
        parseCidr("91.108.8.0/22") toDc 2,
        parseCidr("91.108.16.0/22") toDc 2,
        // Подсеть DC 203 (отдельный медийный ДЦ Telegram)
        parseCidr("91.105.192.0/23") toDc 203,
        // Подсети DC 3 (Miami)
        parseCidr("149.154.175.0/24") toDc 3,
        // Подсети DC 4 (Amsterdam)
        parseCidr("149.154.168.0/22") toDc 4,
        parseCidr("91.108.4.0/22") toDc 4,
        parseCidr("91.108.12.0/22") toDc 4,
        // Подсети DC 5 (Singapore)
        parseCidr("149.154.172.0/22") toDc 5,
        parseCidr("91.108.56.0/22") toDc 5
    )

    private infix fun CidrSubnet.toDc(dc: Int) = SubnetWithDc(this, dc)

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

    /**
     * Определение номера дата-центра (DC 1..5) по целевому IP-адресу.
     * Используется для sTGWS при прямых клиентских подключениях,
     * когда номер DC не передается явно в MTProto obfuscated2 рукопожатии.
     */
    fun guessDcForIp(ipString: String): Int {
        val ipInt = ipToInt(ipString) ?: return 2
        for (item in DC_SUBNETS) {
            if ((ipInt and item.subnet.mask) == item.subnet.baseIp) {
                return item.dc
            }
        }
        return 2 // Дефолтный DC2 (Европа/Амстердам)
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
