package ru.seasonvar.tv.data.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import ru.seasonvar.tv.data.SiteHttpException
import ru.seasonvar.tv.data.account.AccountRepository
import ru.seasonvar.tv.data.account.AccountSession
import ru.seasonvar.tv.data.account.PlaybackMark
import ru.seasonvar.tv.data.account.PlaybackMarkSnapshot
import ru.seasonvar.tv.data.account.SessionExpiredException
import ru.seasonvar.tv.data.account.markSupersedes
import java.io.IOException

enum class MarkSyncPhase { IDLE, SAVING, RETRYING, FAILED }
data class MarkSyncState(val owner: String? = null, val phase: MarkSyncPhase = MarkSyncPhase.IDLE)

class AccountMarkWriter internal constructor(
    private val scope: CoroutineScope,
    private val session: StateFlow<AccountSession>,
    private val outbox: MarkOutbox,
    private val save: suspend (PlaybackMark, Long) -> Unit,
    private val retryMillis: Long = 15_000,
    private val log: (String) -> Unit = {},
) {
    constructor(scope: CoroutineScope, account: AccountRepository, outbox: MarkOutbox) :
        this(scope, account.session, outbox, { mark, version ->
            val saved = account.savePlaybackMark(mark, version, automatic = true)
            if (ru.seasonvar.tv.BuildConfig.DEBUG) android.util.Log.d("SeasonvarMark",
                if (saved != null) "write_verified" else "already_current")
        }, log = { phase -> if (ru.seasonvar.tv.BuildConfig.DEBUG) android.util.Log.d("SeasonvarMark", phase) })
    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow(MarkSyncState())
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            var previousOwner: String? = null
            session.collect { value ->
                val owner = markOwner(value.user)
                mutex.withLock {
                    if (value.expired) outbox.all().forEach(outbox::remove)
                    if (previousOwner != null && previousOwner != owner) {
                        outbox.all().filter { it.owner == previousOwner }.forEach(outbox::remove)
                    }
                    if (owner != null) outbox.all().filter { it.owner != owner }.forEach(outbox::remove)
                }
                previousOwner = owner
                mutableState.value = MarkSyncState(owner)
                retry()
            }
        }
        scope.launch {
            for (signal in wake) {
                var retryDelay = retryMillis
                while (currentCoroutineContext().isActive) {
                    val account = session.value
                    val owner = markOwner(account.user) ?: break
                    val pending = mutex.withLock { outbox.all().filter { it.owner == owner }.minByOrNull { it.revision } }
                    if (pending == null) {
                        mutableState.value = MarkSyncState(owner)
                        break
                    }
                    try {
                        mutableState.value = MarkSyncState(owner, MarkSyncPhase.SAVING)
                        save(pending.mark, account.version)
                        mutex.withLock { outbox.remove(pending) }
                        mutableState.value = MarkSyncState(markOwner(session.value.user))
                        retryDelay = retryMillis
                    } catch (error: CancellationException) {
                        if (!currentCoroutineContext().isActive) throw error
                        break
                    } catch (_: SessionExpiredException) {
                        mutex.withLock { outbox.all().filter { it.owner == owner }.forEach(outbox::remove) }
                        mutableState.value = MarkSyncState(owner, MarkSyncPhase.FAILED)
                        break
                    } catch (error: Exception) {
                        val retryable = error is IOException || error is SiteHttpException && (error.status >= 500 || error.status == 429)
                        log(if (retryable) "retry_pending" else "save_failed")
                        mutableState.value = MarkSyncState(owner, if (retryable) MarkSyncPhase.RETRYING else MarkSyncPhase.FAILED)
                        if (!retryable) break
                        withTimeoutOrNull(retryDelay) { wake.receive() }
                        retryDelay = minOf(retryDelay * 2, 60_000)
                    }
                }
            }
        }
    }

    fun enqueue(mark: PlaybackMark, ownerVersion: Long) {
        scope.launch {
            mutex.withLock {
                val account = session.value
                val owner = markOwner(account.user) ?: return@withLock
                if (account.version != ownerVersion) return@withLock
                val stored = outbox.all()
                val previous = stored.firstOrNull { it.owner == owner && it.mark.seasonId == mark.seasonId }
                if (previous == null || !markSupersedes(PlaybackMarkSnapshot(previous.mark), mark)) {
                    val revision = maxOf(System.currentTimeMillis(), stored.maxOfOrNull { it.revision } ?: 0) + 1
                    outbox.put(PendingMark(owner, mark, revision))
                    log(if (mark.positionSeconds == 0L) "completed_queued" else "position_queued")
                }
                retry()
            }
        }
    }

    fun retry() { wake.trySend(Unit) }
}
