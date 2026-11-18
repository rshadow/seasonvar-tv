package ru.seasonvar.tv.data.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

class StoredSession(val cookies: List<Cookie>, val owner: String? = null)

internal fun siteOrigin(url: HttpUrl) = url.scheme == "https" && url.host in setOf("seasonvar.ru", "www.seasonvar.ru") && url.port == 443

interface SessionStore {
    fun read(): List<Cookie>
    fun save(cookies: List<Cookie>)
    fun clear()
    fun readSession(): StoredSession = StoredSession(read())
    fun saveSession(session: StoredSession) = save(session.cookies)
    fun saveRestoredOwner(session: StoredSession) {}
}

class SessionCookies(initial: List<Cookie> = emptyList(), private val changed: (List<Cookie>) -> Unit = {}) : CookieJar {
    private val cookies = initial.filter { it.domain in setOf("seasonvar.ru", "www.seasonvar.ru") }.toMutableList()
    fun snapshot(): List<Cookie> = synchronized(this) { cookies.toList() }
    fun scriptCookieNameIndex(url: HttpUrl, name: String): Int = loadForRequest(url)
        .filterNot { it.httpOnly }.joinToString("; ") { it.name + "=" + it.value }.indexOf(name)
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

class SessionManager(private val store: SessionStore, private val onChanged: () -> Unit = {}) {
    private val resume = PersonalFeed.RESUME.url.toHttpUrl()
    private var current: SessionCookies? = null
    private var privateSession = false
    private val savedOwner = MutableStateFlow<String?>(null)
    internal val personalCacheOwner = savedOwner.asStateFlow()
    private var ownerCookies = emptySet<Triple<String, String, String>>()
    private var generation = 0L
    @Volatile private var cacheScope: ru.seasonvar.tv.data.cache.CacheScope? = null
    private fun create(initial: List<Cookie> = emptyList()): SessionCookies {
        lateinit var jar: SessionCookies
        jar = SessionCookies(initial) { synchronized(this) {
            if (current === jar && privateSession) {
                val cookies = jar.snapshot()
                if (authCookies(cookies) != ownerCookies) bindOwner(cookies, null)
                runCatching { store.saveSession(StoredSession(cookies, savedOwner.value)) }
            }
        } }
        return jar
    }
    @Synchronized fun current(): SessionCookies {
        if (current == null) {
            val saved = try {
                store.readSession()
            } catch (error: CancellationException) {
                throw error
            } catch (error: SessionStorageException) {
                throw error
            } catch (error: Exception) {
                throw SessionStorageException(error)
            }
            privateSession = saved.cookies.isNotEmpty()
            current = create(saved.cookies).also { bindOwner(it.snapshot(), saved.owner) }
            cacheScope = ru.seasonvar.tv.data.cache.CacheScope(privateSession, generation)
            if (!privateSession) onChanged()
        }
        return current!!
    }
    @Synchronized fun hasSession(): Boolean { current(); return privateSession }
    @Synchronized fun cacheToken(): Long? {
        try { current() } catch (_: SessionStorageException) { return null }
        return if (privateSession) null else generation
    }
    fun cacheScope(): ru.seasonvar.tv.data.cache.CacheScope? {
        try { current() } catch (_: SessionStorageException) { return null }
        return cacheScope
    }
    fun currentCacheScope(): ru.seasonvar.tv.data.cache.CacheScope? = cacheScope
    fun candidate() = create()
    private fun authCookies(cookies: List<Cookie>) = cookies.asSequence().filter {
        it.name == "svid1" && it.expiresAt > System.currentTimeMillis() && it.matches(resume)
    }.map { Triple(it.domain, it.path, it.value) }.toSet()
    private fun bindOwner(cookies: List<Cookie>, owner: String?) {
        ownerCookies = authCookies(cookies)
        savedOwner.value = owner?.takeIf { ownerCookies.isNotEmpty() && it.matches(Regex("[1-9][0-9]*")) }
    }
    @Synchronized internal fun startupOwner(): String? {
        val jar = current()
        return savedOwner.value?.takeIf { privateSession && authCookies(jar.snapshot()) == ownerCookies }
    }
    @Synchronized fun confirmAccount(owner: String) {
        val identity = owner.takeUnless { it == "session" }
        if (privateSession) {
            val cookies = current!!.snapshot()
            val previous = savedOwner.value
            bindOwner(cookies, identity)
            if (previous != savedOwner.value) runCatching { store.saveRestoredOwner(StoredSession(cookies, savedOwner.value)) }
        }
        if (privateSession && cacheScope?.owner != identity) {
            generation++
            cacheScope = ru.seasonvar.tv.data.cache.CacheScope(true, generation, identity)
            onChanged()
        }
    }
    @Synchronized fun commit(jar: SessionCookies, owner: String? = null) {
        val cookies = jar.snapshot()
        val verifiedOwner = owner?.takeIf { authCookies(cookies).isNotEmpty() && it.matches(Regex("[1-9][0-9]*")) }
        store.saveSession(StoredSession(cookies, verifiedOwner))
        bindOwner(cookies, verifiedOwner)
        current = jar
        privateSession = true
        generation++
        cacheScope = ru.seasonvar.tv.data.cache.CacheScope(true, generation, owner?.takeUnless { it == "session" })
        onChanged()
    }
    @Synchronized fun clear() {
        store.clear()
        current = create()
        savedOwner.value = null
        ownerCookies = emptySet()
        privateSession = false
        generation++
        cacheScope = ru.seasonvar.tv.data.cache.CacheScope(false, generation)
        onChanged()
    }
}
