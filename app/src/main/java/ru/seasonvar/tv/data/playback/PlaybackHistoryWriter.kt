package ru.seasonvar.tv.data.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.seasonvar.tv.data.SiteHttpException
import ru.seasonvar.tv.data.account.AccountRepository
import ru.seasonvar.tv.data.account.AccountSession
import java.io.IOException

class PlaybackHistoryWriter internal constructor(
    scope: CoroutineScope,
    private val session: StateFlow<AccountSession>,
    private val save: suspend (PlaybackHistoryEvent, Long) -> Unit,
    private val retryMillis: Long = 2_000,
    private val log: (String) -> Unit = {},
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    constructor(scope: CoroutineScope, account: AccountRepository) : this(scope, account.session, account::recordHistory,
        log = { phase -> if (ru.seasonvar.tv.BuildConfig.DEBUG) android.util.Log.d("SeasonvarHistory", phase) })

    private data class Pending(val event: PlaybackHistoryEvent, val version: Long, val deadline: Long)
    private val pending = Channel<Pending>(64)

    init { scope.launch {
        session.collectLatest { account ->
            if (account.user == null) return@collectLatest
            for (item in pending) {
                if (item.version != account.version || session.value != account) continue
                val remaining = item.deadline - nowMillis()
                if (remaining > 0) withTimeoutOrNull(remaining) { send(item) }
            }
        }
    } }

    fun enqueue(event: PlaybackHistoryEvent, ownerVersion: Long) {
        val account = session.value
        if (account.user != null && account.version == ownerVersion) {
            if (pending.trySend(Pending(event, ownerVersion, nowMillis() + 30_000)).isFailure) log("queue_full")
        }
    }

    private suspend fun send(item: Pending) {
        repeat(3) { attempt ->
            if (session.value.user == null || session.value.version != item.version) return
            try {
                save(item.event, item.version)
                log("write_success")
                return
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val retryable = error is IOException || error is SiteHttpException && (error.status == 429 || error.status >= 500)
                if (!retryable || attempt == 2) { log("write_failed"); return }
                log("retry_pending")
                delay(retryMillis * (attempt + 1))
            }
        }
    }
}
