package ru.seasonvar.tv.data.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import okhttp3.Cookie
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CookieCodec {
    fun encode(cookies: List<Cookie>): String = JSONArray(cookies.map { cookie -> JSONObject()
        .put("name", cookie.name).put("value", cookie.value).put("domain", cookie.domain)
        .put("path", cookie.path).put("expires", cookie.expiresAt).put("persistent", cookie.persistent)
        .put("secure", cookie.secure).put("httpOnly", cookie.httpOnly).put("hostOnly", cookie.hostOnly)
    }).toString()
    fun decode(value: String): List<Cookie> {
        val array = JSONArray(value)
        return (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            Cookie.Builder().name(row.getString("name")).value(row.getString("value")).apply {
                if (row.getBoolean("hostOnly")) hostOnlyDomain(row.getString("domain")) else domain(row.getString("domain"))
                path(row.getString("path"))
                if (row.getBoolean("persistent")) expiresAt(row.getLong("expires"))
                if (row.getBoolean("secure")) secure()
                if (row.getBoolean("httpOnly")) httpOnly()
            }.build()
        }
    }
}

class SessionStorageException(cause: Throwable? = null) : Exception("Session storage unavailable", cause)

class EncryptedSessionStore(context: Context) : SessionStore {
    private val preferences = context.getSharedPreferences("account-session", Context.MODE_PRIVATE)
    private val alias = "seasonvar-session-v1"
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw SessionStorageException()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun read(): List<Cookie> {
        val sealed = preferences.getString("sealed", null) ?: return emptyList()
        val bytes = Base64.getDecoder().decode(sealed)
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return CookieCodec.decode(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
    override fun save(cookies: List<Cookie>) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            val sealed = Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(CookieCodec.encode(cookies).toByteArray(Charsets.UTF_8)))
            if (!preferences.edit().putString("sealed", sealed).commit()) throw SessionStorageException()
        } catch (_: Exception) { throw SessionStorageException() }
    }
    override fun clear() { preferences.edit().clear().commit() }
}
