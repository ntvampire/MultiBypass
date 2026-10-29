package com.multibypass.app.core.tgproxy

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Преобразователь рукопожатий и потоков MTProto:
 * Обеспечивает бесшовную трансляцию прямого клиентского протокола MTProto (obfuscated2)
 * в защищённый формат локального MTProto-прокси (с секретом и WebSocket-туннелированием).
 */
object TelegramHandshakeTranslator {

    const val HANDSHAKE_LEN = 64

    data class SessionCiphers(
        val dc: Int,
        val isMedia: Boolean,
        val protoTag: Int,
        val proxyHandshake: ByteArray,
        val clientDecryptCipher: Cipher,
        val clientEncryptCipher: Cipher,
        val proxyEncryptCipher: Cipher,
        val proxyDecryptCipher: Cipher
    )

    fun translateHandshake(
        clientHs: ByteArray,
        proxySecretHex: String,
        guessedDc: Int = 2
    ): SessionCiphers? {
        if (clientHs.size != HANDSHAKE_LEN) return null

        val dirKey = clientHs.copyOfRange(8, 40)
        val dirIv = clientHs.copyOfRange(40, 56)

        // 1. Дешифруем рукопожатие прямого клиента (obfuscated2 direct, без секрета)
        val dirDecCipher = Cipher.getInstance("AES/CTR/NoPadding")
        dirDecCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dirKey, "AES"), IvParameterSpec(dirIv))
        val decryptedHs = dirDecCipher.update(clientHs)

        // Проверяем тег транспортного протокола MTProto в байтах [56..60]
        val t0 = decryptedHs[56].toInt() and 0xFF
        val t1 = decryptedHs[57].toInt() and 0xFF
        val t2 = decryptedHs[58].toInt() and 0xFF
        val t3 = decryptedHs[59].toInt() and 0xFF

        // В MTProto Abridged транспорт определяется 1 байтом 0xEF (байты 57..59 произвольные).
        // Intermediate и Padded требуют 4 одинаковых байта (0xEEEEEEEE и 0xDDDDDDDD).
        val isAbridged = (t0 == 0xef)
        val isIntermediate = (t0 == 0xee && t1 == 0xee && t2 == 0xee && t3 == 0xee)
        val isPadded = (t0 == 0xdd && t1 == 0xdd && t2 == 0xdd && t3 == 0xdd)

        if (!isAbridged && !isIntermediate && !isPadded) {
            return null // Не является корректным рукопожатием прямого MTProto
        }

        val protoTag = t0

        // Анализ номера ДЦ и типа соединения (по эталонной логике sTGWS из Zapret Manager):
        // При прямом подключении клиент шлет в байтах [60..61] псевдослучайные байты.
        // Клиент осознанно указывает номер ДЦ только если число лежит в диапазоне [-5..5] (кроме 0) или ±203.
        // Если число случайное — берем номер ДЦ по IP-адресу (guessedDc), а isMedia жестко ставим в false,
        // чтобы исключить 50% сбоев и разрывов связи из-за попадания на чужие медийные кластеры.
        val o0 = decryptedHs[60].toInt() and 0xFF
        val o1 = decryptedHs[61].toInt() and 0xFF
        val own = ((o0 or (o1 shl 8)).toShort()).toInt()

        val dc: Int
        val isMedia: Boolean

        if (own == 203 || own == -203) {
            dc = 203
            isMedia = (own < 0)
        } else if (own in -5..5 && own != 0) {
            dc = if (own < 0) -own else own
            isMedia = (own < 0)
        } else {
            dc = if (guessedDc in 1..5 || guessedDc == 203) guessedDc else 2
            isMedia = false
        }

        // 2. Инициализируем шифратор для ответов прямому клиенту (dirEncKey, dirEncIv)
        val dirEncPrekeyAndIv = ByteArray(48)
        for (i in 0 until 48) {
            dirEncPrekeyAndIv[i] = clientHs[8 + 47 - i]
        }
        val dirEncKey = dirEncPrekeyAndIv.copyOfRange(0, 32)
        val dirEncIv = dirEncPrekeyAndIv.copyOfRange(32, 48)
        val dirEncCipher = Cipher.getInstance("AES/CTR/NoPadding")
        dirEncCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dirEncKey, "AES"), IvParameterSpec(dirEncIv))

        // 3. Генерируем 64-байтное рукопожатие для локального прокси с секретом (127.0.0.1:1443)
        val random = SecureRandom()
        val proxyHs = ByteArray(HANDSHAKE_LEN)
        while (true) {
            random.nextBytes(proxyHs)
            val b0 = proxyHs[0].toInt() and 0xFF
            if (b0 == 0xEF) continue
            // Не должно совпадать с HTTP-глаголами или TLS ClientHello
            if (proxyHs[0] == 'H'.code.toByte() && proxyHs[1] == 'E'.code.toByte() &&
                proxyHs[2] == 'A'.code.toByte() && proxyHs[3] == 'D'.code.toByte()
            ) continue
            if (proxyHs[0] == 'P'.code.toByte() && proxyHs[1] == 'O'.code.toByte() &&
                proxyHs[2] == 'S'.code.toByte() && proxyHs[3] == 'T'.code.toByte()
            ) continue
            if (proxyHs[0] == 'G'.code.toByte() && proxyHs[1] == 'E'.code.toByte() &&
                proxyHs[2] == 'T'.code.toByte() && proxyHs[3] == ' '.code.toByte()
            ) continue
            if (proxyHs[0] == 0x16.toByte() && proxyHs[1] == 0x03.toByte() && proxyHs[2] == 0x01.toByte()) continue
            if (proxyHs[4] == 0.toByte() && proxyHs[5] == 0.toByte() && proxyHs[6] == 0.toByte() && proxyHs[7] == 0.toByte()) continue
            break
        }

        val pKey = proxyHs.copyOfRange(8, 40)
        val pIv = proxyHs.copyOfRange(40, 56)
        val secretBytes = hexToBytes(proxySecretHex)

        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(pKey)
        sha.update(secretBytes)
        val hashDec = sha.digest()

        val proxyEncryptCipher = Cipher.getInstance("AES/CTR/NoPadding")
        proxyEncryptCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hashDec, "AES"), IvParameterSpec(pIv))
        val pEncryptedFull = proxyEncryptCipher.update(proxyHs)

        // Формируем хвост: Rust tgwsproxy требует ровно 4 байта валидного тега (0xEFEFEFEF, 0xEEEEEEEE, 0xDDDDDDDD)
        val tailPlain = ByteArray(8)
        when {
            isAbridged -> {
                tailPlain[0] = 0xef.toByte()
                tailPlain[1] = 0xef.toByte()
                tailPlain[2] = 0xef.toByte()
                tailPlain[3] = 0xef.toByte()
            }
            isIntermediate -> {
                tailPlain[0] = 0xee.toByte()
                tailPlain[1] = 0xee.toByte()
                tailPlain[2] = 0xee.toByte()
                tailPlain[3] = 0xee.toByte()
            }
            isPadded -> {
                tailPlain[0] = 0xdd.toByte()
                tailPlain[1] = 0xdd.toByte()
                tailPlain[2] = 0xdd.toByte()
                tailPlain[3] = 0xdd.toByte()
            }
        }
        val effectiveDcRaw = if (isMedia) (-dc).toShort() else dc.toShort()
        tailPlain[4] = (effectiveDcRaw.toInt() and 0xFF).toByte()
        tailPlain[5] = ((effectiveDcRaw.toInt() shr 8) and 0xFF).toByte()
        val tailRand = ByteArray(2)
        random.nextBytes(tailRand)
        tailPlain[6] = tailRand[0]
        tailPlain[7] = tailRand[1]

        for (i in 0 until 8) {
            val keystreamTail = (pEncryptedFull[56 + i].toInt() xor proxyHs[56 + i].toInt()).toByte()
            proxyHs[56 + i] = (tailPlain[i].toInt() xor keystreamTail.toInt()).toByte()
        }

        // 4. Инициализируем дешифратор для ответов от локального прокси
        val pEncPrekeyAndIv = ByteArray(48)
        for (i in 0 until 48) {
            pEncPrekeyAndIv[i] = proxyHs[8 + 47 - i]
        }
        val shaEnc = MessageDigest.getInstance("SHA-256")
        shaEnc.update(pEncPrekeyAndIv, 0, 32)
        shaEnc.update(secretBytes)
        val hashEnc = shaEnc.digest()
        val pEncIv = pEncPrekeyAndIv.copyOfRange(32, 48)

        val proxyDecryptCipher = Cipher.getInstance("AES/CTR/NoPadding")
        proxyDecryptCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(hashEnc, "AES"), IvParameterSpec(pEncIv))

        return SessionCiphers(
            dc = dc,
            isMedia = isMedia,
            protoTag = protoTag,
            proxyHandshake = proxyHs,
            clientDecryptCipher = dirDecCipher,
            clientEncryptCipher = dirEncCipher,
            proxyEncryptCipher = proxyEncryptCipher,
            proxyDecryptCipher = proxyDecryptCipher
        )
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val len = clean.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
