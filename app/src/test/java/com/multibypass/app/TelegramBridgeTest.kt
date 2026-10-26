package com.multibypass.app

import com.multibypass.app.core.tgproxy.TelegramDcRanges
import com.multibypass.app.core.tgproxy.TelegramHandshakeTranslator
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class TelegramBridgeTest {

    @Test
    fun testTelegramDcRangesMatchingAndDcGuessing() {
        // Проверяем валидные IP-адреса дата-центров Telegram
        assertTrue("DC2 IP must match", TelegramDcRanges.isTelegramIp("149.154.167.220"))
        assertTrue("DC4 IP must match", TelegramDcRanges.isTelegramIp("149.154.167.91"))
        assertTrue("DC1 IP must match", TelegramDcRanges.isTelegramIp("149.154.175.50"))
        assertTrue("DC5 IP must match", TelegramDcRanges.isTelegramIp("91.108.56.120"))
        assertTrue("DC4 subnet IP must match", TelegramDcRanges.isTelegramIp("91.108.4.1"))
        assertTrue("DC 203 IP must match", TelegramDcRanges.isTelegramIp("91.105.192.100"))
        assertTrue("AS62041 IP must match", TelegramDcRanges.isTelegramIp("95.161.64.5"))
        assertTrue("185.76.151.x must match", TelegramDcRanges.isTelegramIp("185.76.151.10"))

        // Проверяем порты Telegram
        assertTrue("Port 443 must be valid", TelegramDcRanges.isTelegramPort(443))
        assertTrue("Port 80 must be valid", TelegramDcRanges.isTelegramPort(80))
        assertTrue("Port 5222 must be valid", TelegramDcRanges.isTelegramPort(5222))
        assertFalse("Port 8080 is not Telegram port", TelegramDcRanges.isTelegramPort(8080))

        // Проверяем не относящиеся к Telegram IP-адреса
        assertFalse("Google DNS must NOT match", TelegramDcRanges.isTelegramIp("8.8.8.8"))
        assertFalse("Cloudflare DNS must NOT match", TelegramDcRanges.isTelegramIp("1.1.1.1"))
        assertFalse("Comss SmartDNS must NOT match", TelegramDcRanges.isTelegramIp("195.133.25.16"))
        assertFalse("Random web IP must NOT match", TelegramDcRanges.isTelegramIp("142.250.180.14"))

        // Проверяем автоопределение DC по целевому IP
        assertEquals("DC1 exact IP", 1, TelegramDcRanges.guessDcForIp("149.154.175.50"))
        assertEquals("DC2 exact IP", 2, TelegramDcRanges.guessDcForIp("149.154.167.51"))
        assertEquals("DC2 subnet IP", 2, TelegramDcRanges.guessDcForIp("149.154.167.220"))
        assertEquals("DC3 exact IP", 3, TelegramDcRanges.guessDcForIp("149.154.175.100"))
        assertEquals("DC4 exact IP", 4, TelegramDcRanges.guessDcForIp("149.154.167.91"))
        assertEquals("DC4 subnet IP", 4, TelegramDcRanges.guessDcForIp("91.108.4.1"))
        assertEquals("DC5 exact IP", 5, TelegramDcRanges.guessDcForIp("149.154.171.5"))
        assertEquals("DC5 subnet IP", 5, TelegramDcRanges.guessDcForIp("91.108.56.120"))
    }

    @Test
    fun testHandshakeTranslationAndCipherSync() {
        val secret = "112233445566778899aabbccddeeff00"
        val random = SecureRandom()

        // 1. Создаём прямое рукопожатие от Telegram клиента к DC 4 с 1-байтовым Abridged (0xEF)
        // В реальном клиенте байты [57..59] НЕ равны 0xEF, а номер DC равен 0
        val directHs = ByteArray(64)
        random.nextBytes(directHs)
        directHs[0] = 0xee.toByte()
        directHs[4] = 0x01.toByte()

        val dirKey = directHs.copyOfRange(8, 40)
        val dirIv = directHs.copyOfRange(40, 56)

        // Имитируем шифрование хвоста: байт 56 = 0xEF, байты 57..59 = случайные, байты 60..61 = 0
        val tailPlain = ByteArray(8)
        tailPlain[0] = 0xef.toByte()
        tailPlain[1] = 0x12.toByte() // Произвольный байт
        tailPlain[2] = 0x34.toByte() // Произвольный байт
        tailPlain[3] = 0x56.toByte() // Произвольный байт
        tailPlain[4] = 0x00.toByte() // DC = 0
        tailPlain[5] = 0x00.toByte()
        tailPlain[6] = 0x78.toByte()
        tailPlain[7] = 0x9a.toByte()

        val clientInitCipher = Cipher.getInstance("AES/CTR/NoPadding")
        clientInitCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dirKey, "AES"), IvParameterSpec(dirIv))
        val keystreamDir = clientInitCipher.update(directHs)

        for (i in 0 until 8) {
            val ksTailByte = (keystreamDir[56 + i].toInt() xor directHs[56 + i].toInt()).toByte()
            directHs[56 + i] = (tailPlain[i].toInt() xor ksTailByte.toInt()).toByte()
        }

        // 2. Транслируем рукопожатие через TelegramHandshakeTranslator с guessedDc = 4
        val session = TelegramHandshakeTranslator.translateHandshake(
            clientHs = directHs,
            proxySecretHex = secret,
            guessedDc = 4
        )
        assertNotNull("1-byte Abridged handshake translation must succeed", session)
        session!!

        assertEquals("Decoded DC must fallback to guessed DC 4", 4, session.dc)
        assertEquals("Decoded proto tag must be 0xEF", 0xef, session.protoTag)
        assertEquals("Proxy handshake must be 64 bytes", 64, session.proxyHandshake.size)

        // 3. Проверяем, что прокси-рукопожатие расшифровывается в нормализованный 4-байтовый тег 0xEFEFEFEF и DC 4
        val pKey = session.proxyHandshake.copyOfRange(8, 40)
        val pIv = session.proxyHandshake.copyOfRange(40, 56)
        val secretBytes = hexToBytes(secret)

        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(pKey)
        sha.update(secretBytes)
        val hashDec = sha.digest()

        val proxySimDecCipher = Cipher.getInstance("AES/CTR/NoPadding")
        proxySimDecCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(hashDec, "AES"), IvParameterSpec(pIv))
        val proxyDecryptedHs = proxySimDecCipher.update(session.proxyHandshake)

        val expectedRustProtoTag = byteArrayOf(0xef.toByte(), 0xef.toByte(), 0xef.toByte(), 0xef.toByte())
        val readTag = proxyDecryptedHs.copyOfRange(56, 60)
        assertArrayEquals("Proxy must decode normalized 4-byte proto tag 0xEFEFEFEF", expectedRustProtoTag, readTag)
        val readDc = ((proxyDecryptedHs[60].toInt() and 0xFF) or ((proxyDecryptedHs[61].toInt() and 0xFF) shl 8)).toShort().toInt()
        assertEquals("Proxy must decode DC 4", 4, readDc)

        // 4. Проверяем двунаправленную потоковую трансляцию данных Client -> Proxy
        val clientStreamCipher = Cipher.getInstance("AES/CTR/NoPadding")
        clientStreamCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dirKey, "AES"), IvParameterSpec(dirIv))
        clientStreamCipher.update(directHs) // Синхронизируем первые 64 байта

        val testPayload = "Hello MTProto DC4 Packet via sTGWS MultiBypass!".toByteArray(Charsets.UTF_8)
        val clientEncryptedData = clientStreamCipher.update(testPayload)

        val plainFromClient = session.clientDecryptCipher.update(clientEncryptedData)
        assertArrayEquals("Interceptor must get clean plain MTProto", testPayload, plainFromClient)

        val proxyEncryptedData = session.proxyEncryptCipher.update(plainFromClient)

        val plainAtProxy = proxySimDecCipher.update(proxyEncryptedData)
        assertArrayEquals("Proxy must receive exact original plain MTProto", testPayload, plainAtProxy)

        // 5. Проверяем потоковую трансляцию данных Proxy -> Client
        val pEncPrekeyAndIv = ByteArray(48)
        for (i in 0 until 48) {
            pEncPrekeyAndIv[i] = session.proxyHandshake[8 + 47 - i]
        }
        val shaEnc = MessageDigest.getInstance("SHA-256")
        shaEnc.update(pEncPrekeyAndIv, 0, 32)
        shaEnc.update(secretBytes)
        val hashEnc = shaEnc.digest()
        val pEncIv = pEncPrekeyAndIv.copyOfRange(32, 48)

        val proxySimEncCipher = Cipher.getInstance("AES/CTR/NoPadding")
        proxySimEncCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hashEnc, "AES"), IvParameterSpec(pEncIv))

        val serverResponsePayload = "MTProto AuthKey Response from Telegram Origin DC4!".toByteArray(Charsets.UTF_8)
        val proxyEncryptedResponse = proxySimEncCipher.update(serverResponsePayload)

        val plainFromProxy = session.proxyDecryptCipher.update(proxyEncryptedResponse)
        assertArrayEquals("Interceptor must receive clean response from proxy", serverResponsePayload, plainFromProxy)

        val clientEncryptedResponse = session.clientEncryptCipher.update(plainFromProxy)

        val dirEncPrekeyAndIv = ByteArray(48)
        for (i in 0 until 48) {
            dirEncPrekeyAndIv[i] = directHs[8 + 47 - i]
        }
        val clientSimDecCipher = Cipher.getInstance("AES/CTR/NoPadding")
        clientSimDecCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dirEncPrekeyAndIv.copyOfRange(0, 32), "AES"), IvParameterSpec(dirEncPrekeyAndIv.copyOfRange(32, 48)))

        val plainAtClient = clientSimDecCipher.update(clientEncryptedResponse)
        assertArrayEquals("Client must receive exact clean response", serverResponsePayload, plainAtClient)
    }

    @Test
    fun testIntermediateAndPaddedHandshakes() {
        val secret = "112233445566778899aabbccddeeff00"
        val random = SecureRandom()

        // Test Intermediate: 0xEEEEEEEE
        val intermediateHs = ByteArray(64)
        random.nextBytes(intermediateHs)
        val dirKey = intermediateHs.copyOfRange(8, 40)
        val dirIv = intermediateHs.copyOfRange(40, 56)

        val tailPlain = byteArrayOf(
            0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte(),
            0x02.toByte(), 0x00.toByte(), // DC = 2
            0x11.toByte(), 0x22.toByte()
        )

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dirKey, "AES"), IvParameterSpec(dirIv))
        val ks = cipher.update(intermediateHs)

        for (i in 0 until 8) {
            val ksByte = (ks[56 + i].toInt() xor intermediateHs[56 + i].toInt()).toByte()
            intermediateHs[56 + i] = (tailPlain[i].toInt() xor ksByte.toInt()).toByte()
        }

        val session = TelegramHandshakeTranslator.translateHandshake(intermediateHs, secret, guessedDc = 2)
        assertNotNull("Intermediate handshake must be accepted", session)
        assertEquals("Proto tag must be 0xEE", 0xee, session!!.protoTag)
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
