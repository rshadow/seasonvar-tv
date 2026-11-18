package ru.seasonvar.tv.data.account

import android.content.Context
import okhttp3.Cookie
import org.json.JSONArray
import org.json.JSONObject
import ru.seasonvar.tv.data.security.KeystoreCipher
import java.security.MessageDigest

object CookieCodec {
    fun encode(cookies: List<Cookie>): String = JSONArray(cookies.map { cookie -> JSONObject()
        .put("name", cookie.name).put("value", cookie.value).put("domain", cookie.domain)
        .put("path", cookie.path).put("expires", cookie.expiresAt).put("persistent", cookie.persistent)
        .put("secure", cookie.secure).put("httpOnly", cookie.httpOnly).put("hostOnly", cookie.hostOnly)
    }).toString()
    fun decode(value: String): List<Cookie> = try {
        val array = if (value.trimStart().startsWith('{')) JSONObject(value).getJSONArray("cookies") else JSONArray(value)
        (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            Cookie.Builder().name(row.getString("name")).value(row.getString("value")).apply {
                if (row.getBoolean("hostOnly")) hostOnlyDomain(row.getString("domain")) else domain(row.getString("domain"))
                path(row.getString("path"))
                if (row.getBoolean("persistent")) expiresAt(row.getLong("expires"))
                if (row.getBoolean("secure")) secure()
                if (row.getBoolean("httpOnly")) httpOnly()
            }.build()
        }
    } catch (_: Exception) { throw SessionStorageException() }
}

class SessionStorageException(cause: Throwable? = null) : Exception("Session storage unavailable", cause)

internal object StoredSessionOwnerCodec {
    private fun fingerprint(sealed: String) = MessageDigest.getInstance("SHA-256")
        .digest(sealed.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun encode(owner: String, sealed: String): String = JSONObject()
        .put("owner", owner).put("session", fingerprint(sealed)).toString()

    fun decode(value: String, sealed: String): String? {
        val binding = JSONObject(value)
        return binding.optString("owner").takeIf {
            it.matches(Regex("[1-9][0-9]*")) && binding.optString("session") == fingerprint(sealed)
        }
    }
}

class EncryptedSessionStore(context: Context) : SessionStore {
    private val preferences = context.getSharedPreferences("account-session", Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher("seasonvar-session-v1")
    override fun read(): List<Cookie> = readSession().cookies
    override fun readSession(): StoredSession {
        val sealed = preferences.getString("sealed", null) ?: return StoredSession(emptyList())
        val cookies = CookieCodec.decode(cipher.open(sealed))
        val owner = runCatching {
            preferences.getString("sealed-owner", null)?.let { StoredSessionOwnerCodec.decode(cipher.open(it), sealed) }
        }.getOrNull()
        return StoredSession(cookies, owner)
    }
    override fun save(cookies: List<Cookie>) = saveSession(StoredSession(cookies))
    override fun saveRestoredOwner(session: StoredSession) = saveSession(session)
    override fun saveSession(session: StoredSession) {
        try {
            val sealed = cipher.seal(CookieCodec.encode(session.cookies))
            val owner = session.owner?.let { cipher.seal(StoredSessionOwnerCodec.encode(it, sealed)) }
            if (!preferences.edit().putString("sealed", sealed).putString("sealed-owner", owner).commit())
                throw SessionStorageException()
        } catch (_: Exception) { throw SessionStorageException() }
    }
    override fun clear() { preferences.edit().clear().commit() }
}
