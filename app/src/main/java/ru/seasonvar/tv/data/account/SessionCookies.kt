package ru.seasonvar.tv.data.account

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

internal fun siteOrigin(url: HttpUrl) = url.scheme == "https" && url.host in setOf("seasonvar.ru", "www.seasonvar.ru") && url.port == 443

interface SessionStore {
    fun read(): List<Cookie>
    fun save(cookies: List<Cookie>)
    fun clear()
}

class SessionCookies(initial: List<Cookie> = emptyList(), private val changed: (List<Cookie>) -> Unit = {}) : CookieJar {
    private val cookies = initial.filter { it.domain in setOf("seasonvar.ru", "www.seasonvar.ru") }.toMutableList()
    fun snapshot(): List<Cookie> = synchronized(this) { cookies.toList() }
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!siteOrigin(url)) return
        val snapshot = synchronized(this) {
            for (cookie in cookies.filter { it.domain in setOf("seasonvar.ru", "www.seasonvar.ru") }) {
                this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                if (cookie.expiresAt > System.currentTimeMillis()) this.cookies += cookie
            }
            this.cookies.toList()
        }
        changed(snapshot)
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(this) {
        if (!siteOrigin(url)) emptyList() else cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
    }
}

class SessionManager(private val store: SessionStore) {
    private var current: SessionCookies? = null
    private var privateSession = false
    private var generation = 0L
    private fun create(initial: List<Cookie> = emptyList()): SessionCookies {
        lateinit var jar: SessionCookies
        jar = SessionCookies(initial) { cookies -> synchronized(this) {
            if (current === jar && privateSession) runCatching { store.save(cookies) }
        } }
        return jar
    }
    @Synchronized fun current(): SessionCookies {
        if (current == null) {
            val saved = runCatching { store.read() }.getOrElse { store.clear(); emptyList() }
            privateSession = saved.isNotEmpty()
            current = create(saved)
        }
        return current!!
    }
    @Synchronized fun hasSession(): Boolean { current(); return privateSession }
    @Synchronized fun cacheToken(): Long? { current(); return if (privateSession) null else generation }
    fun candidate() = create()
    @Synchronized fun commit(jar: SessionCookies) {
        store.save(jar.snapshot())
        current = jar
        privateSession = true
        generation++
    }
    @Synchronized fun clear() {
        store.clear()
        current = create()
        privateSession = false
        generation++
    }
}
