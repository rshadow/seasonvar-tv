package ru.seasonvar.tv.data.account

import android.content.Context
import ru.seasonvar.tv.diagnostics.LocalDiagnosticFailure

private const val RETENTION_MILLIS = 24 * 60 * 60 * 1000L

internal enum class SessionOperation { RESTORE, SAVE }

internal data class SessionDiagnosticRecord(
    val operation: SessionOperation,
    val failure: LocalDiagnosticFailure,
    val occurredAtMillis: Long = System.currentTimeMillis(),
) {
    val expiresAtMillis get() = occurredAtMillis + RETENTION_MILLIS

    fun isRecent(nowMillis: Long): Boolean = occurredAtMillis in 1..nowMillis && nowMillis - occurredAtMillis < RETENTION_MILLIS

    fun toStorage(): String = "$occurredAtMillis ${failure.code}"

    companion object {
        fun fromStorage(operation: SessionOperation, value: String): SessionDiagnosticRecord? {
            val time = value.substringBefore(' ').toLongOrNull() ?: return null
            val failure = LocalDiagnosticFailure.fromCode(value.substringAfter(' ', "")) ?: return null
            return SessionDiagnosticRecord(operation, failure, time)
        }
    }
}

internal object SessionDiagnosticHistory {
    private const val STORE = "session-diagnostic"
    private val recent = mutableMapOf<SessionOperation, SessionDiagnosticRecord>()

    @Synchronized
    fun record(context: Context, failure: SessionDiagnosticRecord) {
        recent[failure.operation] = failure
        runCatching {
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
                .putString(failure.operation.name, failure.toStorage()).commit()
        }
    }

    @Synchronized
    fun read(context: Context): List<SessionDiagnosticRecord> {
        val now = System.currentTimeMillis()
        val preferences = runCatching { context.getSharedPreferences(STORE, Context.MODE_PRIVATE) }.getOrNull()
        return SessionOperation.entries.mapNotNull { operation ->
            val stored = runCatching { preferences?.getString(operation.name, null) }.getOrNull()
            val record = recent[operation] ?: stored?.let { SessionDiagnosticRecord.fromStorage(operation, it) }
            if (record?.isRecent(now) == true) return@mapNotNull record
            recent.remove(operation)
            if (stored != null) runCatching { preferences?.edit()?.remove(operation.name)?.apply() }
            null
        }
    }
}
