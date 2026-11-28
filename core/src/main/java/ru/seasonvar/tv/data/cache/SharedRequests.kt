package ru.seasonvar.tv.data.cache

import kotlinx.coroutines.*

internal class SharedRequests<K, V>(private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private class Flight<V>(val result: Deferred<V>, var readers: Int = 0)
    private val monitor = Any()
    private val flights = mutableMapOf<K, Flight<V>>()

    suspend fun get(key: K, load: suspend () -> V): V {
        val flight = synchronized(monitor) {
            flights.getOrPut(key) {
                Flight(scope.async(start = CoroutineStart.LAZY) { load().also { ensureActive() } })
            }.also { it.readers++ }
        }
        flight.result.start()
        try { return flight.result.await() }
        finally { synchronized(monitor) {
            flight.readers--
            if (flight.readers == 0 && flights[key] === flight) {
                flights.remove(key)
                flight.result.cancel()
            }
        } }
    }

    fun cancel() = synchronized(monitor) {
        val previous = flights.values.toList()
        flights.clear()
        previous.forEach { it.result.cancel() }
    }
}
