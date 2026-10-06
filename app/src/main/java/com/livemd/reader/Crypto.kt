package com.livemd.reader

import android.util.Base64
import org.json.JSONArray
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Self-hosted LiveSync 数据解码：
 * - V2/HKDF（前缀 "%="）：PBKDF2-SHA256(310000 次, salt 来自服务器 _local/obsidian_livesync_sync_parameters)
 *   派生主密钥，再经 HKDF-SHA256（每次加密内嵌 32 字节 salt）派生 AES-256-GCM 密钥。
 *   payload = base64( iv(12B) | hkdfSalt(32B) | ciphertext+tag )
 * - V1（前缀 "%"）：PBKDF2-SHA256(SHA256(passphrase) 作为口令，10 万次或动态次数) → AES-256-GCM。
 *   payload = "%" + hex(iv 16B) + hex(salt 16B) + base64(ciphertext+tag)
 * - 旧版 JSON（前缀 "["）：["base64", "ivHex", "saltHex"]
 * 算法参数与 vrtmrz/obsidian-livesync + octagonal-wheels 源码逐一核对。
 */
object Crypto {

    private const val PBKDF2_ITERATIONS_V2 = 310000
    private val masterKeyCache = HashMap<String, ByteArray>()

    /** 解密任意 LiveSync 加密字符串；未加密时原样返回 */
    fun decryptChunk(data: String, passphrase: String, pbkdf2Salt: ByteArray?): String {
        if (passphrase.isEmpty()) {
            if (data.startsWith("%=") || data.startsWith("%") || data.startsWith("["))
                throw Exception("笔记已加密，请在设置中填写 E2EE 密码短语")
            return data
        }
        return when {
            data.startsWith("%=") -> decryptV2(data, passphrase, pbkdf2Salt)
            data.startsWith("%~") -> throw Exception("暂不支持 V3 加密格式(%~)，请反馈")
            data.startsWith("%") -> decryptV1(data, passphrase)
            data.startsWith("[") && data.endsWith("]") -> decryptLegacyJson(data, passphrase)
            else -> data
        }
    }

    /** V2/HKDF：解密块内容或 HKDF 加密的元数据 JSON */
    private fun decryptV2(data: String, passphrase: String, pbkdf2Salt: ByteArray?): String {
        val salt = pbkdf2Salt ?: throw Exception("服务器缺少 pbkdf2salt（同步参数文档）")
        val raw = Base64.decode(data.substring(2), Base64.NO_WRAP)
        if (raw.size < 12 + 32 + 16) throw Exception("HKDF 数据长度异常")
        val iv = raw.copyOfRange(0, 12)
        val hkdfSalt = raw.copyOfRange(12, 44)
        val ct = raw.copyOfRange(44, raw.size)
        val master = masterKey(passphrase, salt)
        val key = hkdfSha256(master, hkdfSalt, ByteArray(0), 32)
        return String(aesGcmDecrypt(key, iv, ct), Charsets.UTF_8)
    }

    /** V1："%"+hex(iv)+hex(salt)+base64，动态迭代次数失败时回退固定 10 万次（与插件行为一致） */
    private fun decryptV1(data: String, passphrase: String): String {
        if (data.length < 65) throw Exception("V1 数据长度异常")
        val iv = hexToBytes(data.substring(1, 33))
        val salt = hexToBytes(data.substring(33, 65))
        val ct = Base64.decode(data.substring(65), Base64.NO_WRAP)
        var last: Exception? = null
        for (dynamic in booleanArrayOf(true, false)) {
            try {
                val key = v1Key(passphrase, salt, dynamic)
                return String(aesGcmDecrypt(key, iv, ct), Charsets.UTF_8)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: Exception("V1 解密失败")
    }

    /** 最古老的 JSON 数组格式，内容是 JSON.stringify 过的字符串 */
    private fun decryptLegacyJson(data: String, passphrase: String): String {
        val arr = JSONArray(data)
        val iv = hexToBytes(arr.getString(1))
        val salt = hexToBytes(arr.getString(2))
        val ct = Base64.decode(arr.getString(0), Base64.NO_WRAP)
        var last: Exception? = null
        var plain: String? = null
        for (dynamic in booleanArrayOf(true, false)) {
            try {
                plain = String(aesGcmDecrypt(v1Key(passphrase, salt, dynamic), iv, ct), Charsets.UTF_8)
                break
            } catch (e: Exception) {
                last = e
            }
        }
        val v = JSONTokener(plain ?: throw last ?: Exception("解密失败")).nextValue()
        return v.toString()
    }

    private fun v1Key(passphrase: String, salt: ByteArray, dynamic: Boolean): ByteArray {
        // keyMaterial = SHA-256(passphrase)；次数：动态时按口令长度计算，否则固定 100000
        val keyMaterial = MessageDigest.getInstance("SHA-256")
            .digest(passphrase.toByteArray(Charsets.UTF_8))
        val pl = 15 - passphrase.length
        val iterations = if (dynamic) (if (pl > 0) pl else 0) * 1000 + 121 - pl else 100000
        return pbkdf2(keyMaterial, salt, iterations, 32)
    }

    private fun masterKey(passphrase: String, pbkdf2Salt: ByteArray): ByteArray {
        val cacheKey = hex(pbkdf2Salt) + "|" + passphrase.hashCode()
        synchronized(masterKeyCache) { masterKeyCache[cacheKey]?.let { return it } }
        val bytes = try {
            val spec = PBEKeySpec(passphrase.toCharArray(), pbkdf2Salt, PBKDF2_ITERATIONS_V2, 256)
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } catch (e: Exception) {
            pbkdf2(passphrase.toByteArray(Charsets.UTF_8), pbkdf2Salt, PBKDF2_ITERATIONS_V2, 32)
        }
        synchronized(masterKeyCache) {
            if (masterKeyCache.size > 4) masterKeyCache.clear()
            masterKeyCache[cacheKey] = bytes
        }
        return bytes
    }

    /** 纯手写 PBKDF2-HMAC-SHA256，支持任意二进制口令（WebCrypto 行为一致） */
    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val hLen = 32
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        for (i in 1..blocks) {
            mac.init(SecretKeySpec(password, "HmacSHA256"))
            mac.update(salt)
            mac.update(byteArrayOf(0, 0, 0, i.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (iter in 2..iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (i - 1) * hLen, hLen)
        }
        return out.copyOf(dkLen)
    }

    /** RFC 5869 HKDF (extract + expand) */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val okm = ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (okm.size() < len) {
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            okm.write(t)
            counter++
        }
        return okm.toByteArray().copyOf(len)
    }

    private fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, ct: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append("%02x".format(x))
        return sb.toString()
    }

    private fun hexToBytes(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }
}
