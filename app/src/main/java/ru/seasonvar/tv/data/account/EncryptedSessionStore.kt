package ru.seasonvar.tv.data.account

import android.content.Context
import okhttp3.Cookie
import org.json.JSONArray
import org.json.JSONObject
import ru.seasonvar.tv.data.security.KeystoreCipher

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
    private val cipher = KeystoreCipher("seasonvar-session-v1")
    override fun read(): List<Cookie> {
        val sealed = preferences.getString("sealed", null) ?: return emptyList()
        return CookieCodec.decode(cipher.open(sealed))
    }
    override fun save(cookies: List<Cookie>) {
        try {
            val sealed = cipher.seal(CookieCodec.encode(cookies))
            if (!preferences.edit().putString("sealed", sealed).commit()) throw SessionStorageException()
        } catch (_: Exception) { throw SessionStorageException() }
    }
    override fun clear() { preferences.edit().clear().commit() }
}
