package ru.seasonvar.tv.data.cache

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class CacheScope(val private: Boolean, val generation: Long, val owner: String? = null)
data class CacheLease(val scope: CacheScope?, val revision: Long)
data class CachedHtml(val html: String, val savedAt: Long, val fromDisk: Boolean = false)

class HtmlResponseCache(
    private val directory: File,
    private val scope: () -> CacheScope? = { CacheScope(false, 0) },
    private val currentScope: () -> CacheScope? = scope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val monitor = Any()
    private var revision = 0L

    fun capture(): CacheLease {
        val captured = scope()
        return synchronized(monitor) { CacheLease(captured, revision) }
    }

    fun isCurrent(lease: CacheLease): Boolean = currentScope() == lease.scope

    fun read(name: String, lease: CacheLease, lifetime: Long = DAY,
        fresh: ((Long) -> Boolean)? = null): CachedHtml? = synchronized(monitor) {
        val file = file(name, lease) ?: return@synchronized null
        if (!isCurrent(lease) || lease.revision != revision || !file.isFile) return@synchronized null
        val timestamp = file.lastModified()
        if (!(fresh?.invoke(timestamp) ?: (now() - timestamp in 0 until lifetime))) return@synchronized null
        runCatching { CachedHtml(file.readText(), timestamp, fromDisk = true) }.getOrNull()
            ?.takeIf { isCurrent(lease) && lease.revision == revision }
    }

    fun save(name: String, html: String, lease: CacheLease) = synchronized(monitor) {
        val destination = file(name, lease) ?: return@synchronized
        if (!isCurrent(lease) || lease.revision != revision) return@synchronized
        runCatching {
            check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
            val temporary = File.createTempFile("response-", ".html", destination.parentFile)
            try {
                temporary.writeText(html)
                if (isCurrent(lease) && lease.revision == revision) {
                    check(temporary.renameTo(destination))
                    destination.setLastModified(now())
                }
            } finally { temporary.delete() }
        }
        Unit
    }

    fun openReader(name: String, lease: CacheLease, lifetime: Long = DAY): java.io.Reader? = synchronized(monitor) {
        val file = file(name, lease) ?: return@synchronized null
        if (!isCurrent(lease) || lease.revision != revision || !file.isFile ||
            now() - file.lastModified() !in 0 until lifetime) return@synchronized null
        runCatching { file.reader(Charsets.UTF_8) }.getOrNull()
    }

    fun invalidate(prefix: String, lease: CacheLease = capture()) = synchronized(monitor) {
        if (!isCurrent(lease)) return@synchronized
        revision++
        val parent = if (lease.scope?.private == true) File(directory, "private") else directory
        parent.listFiles()?.filter { it.isFile && it.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    fun clearPrivate() = synchronized(monitor) {
        revision++
        File(directory, "private").deleteRecursively()
        Unit
    }

    fun selectPrivateOwner(owner: String) = synchronized(monitor) {
        revision++
        val parent = File(directory, "private")
        val marker = File(parent, "owner")
        val previous = runCatching { marker.takeIf { it.isFile }?.readText() }.getOrNull()
        if (previous != owner) {
            parent.deleteRecursively()
            check(parent.isDirectory || parent.mkdirs())
            marker.writeText(owner)
        }
    }

    private fun file(name: String, lease: CacheLease): File? {
        require(name.matches(Regex("[A-Za-z0-9._-]+")))
        val selected = lease.scope ?: return null
        if (selected.private && selected.owner == null) return null
        if (selected.private && runCatching { File(directory, "private/owner").readText() }.getOrNull() != selected.owner) return null
        return File(if (selected.private) File(directory, "private") else directory, name)
    }

    companion object {
        const val DAY = 86_400_000L
        fun requestKey(prefix: String, method: String, url: String, values: Map<String, String> = emptyMap()): String {
            val fields = listOf(method, url) + values.toSortedMap().flatMap { listOf(it.key, it.value) }
            val digest = MessageDigest.getInstance("SHA-256")
            for (value in fields) {
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
                digest.update(':'.code.toByte()); digest.update(bytes)
            }
            return prefix + digest.digest().joinToString("") { "%02x".format(it) } + ".html"
        }
    }
}
