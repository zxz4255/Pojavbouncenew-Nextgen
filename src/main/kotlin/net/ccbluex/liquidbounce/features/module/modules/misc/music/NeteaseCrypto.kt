package net.ccbluex.liquidbounce.features.module.modules.misc.music

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NetEase Cloud Music "weapi" request encryption.
 *
 * <p>Ported verbatim from NeoRaven's `music.NeteaseCrypto` (itself Melodify's): the payload is
 * AES-CBC encrypted twice (first with the well-known preset key, then with a random 16-character
 * secret), and the secret itself is RSA-encrypted so only the server can unwrap it. The secret is
 * reversed before the RSA step, which is what the web client does. This is what makes QR login,
 * daily recommendations, user playlists and `song/url` work at all — the plaintext API family the
 * previous module used cannot sign in.</p>
 */
object NeteaseCrypto {

    private val UTF8 = StandardCharsets.UTF_8
    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private const val RSA_EXPONENT = "010001"

    private const val RSA_MODULUS =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"

    private val BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray()
    private val RANDOM = SecureRandom()

    /** Builds the `params` / `encSecKey` pair for one weapi request. */
    fun weapi(json: String): Map<String, String> {
        val secret = randomString(16)
        val once = aesCbc(json, PRESET_KEY)
        val form = LinkedHashMap<String, String>()
        form["params"] = aesCbc(once, secret)
        form["encSecKey"] = rsa(secret)
        return form
    }

    private fun aesCbc(data: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(UTF8), "AES"),
            IvParameterSpec(IV.toByteArray(UTF8)),
        )
        return Base64.getEncoder().encodeToString(cipher.doFinal(data.toByteArray(UTF8)))
    }

    private fun rsa(secret: String): String {
        val reversed = StringBuilder(secret).reverse().toString()
        val message = BigInteger(1, reversed.toByteArray(UTF8))
        val exponent = BigInteger(RSA_EXPONENT, 16)
        val modulus = BigInteger(RSA_MODULUS, 16)
        val hex = message.modPow(exponent, modulus).toString(16)
        val out = StringBuilder(256)
        for (i in hex.length until 256) {
            out.append('0')
        }
        return out.append(hex).toString()
    }

    private fun randomString(length: Int): String {
        val out = CharArray(length)
        for (i in 0 until length) {
            out[i] = BASE62[RANDOM.nextInt(BASE62.size)]
        }
        return String(out)
    }

    /** Lower-case MD5 hex, used by the eapi id digests. */
    fun md5(text: String): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val hash = digest.digest(text.toByteArray(UTF8))
            val out = StringBuilder(hash.size * 2)
            for (b in hash) {
                out.append(Character.forDigit((b.toInt() shr 4) and 0xF, 16))
                out.append(Character.forDigit(b.toInt() and 0xF, 16))
            }
            out.toString()
        } catch (t: Throwable) {
            ""
        }
    }
}
