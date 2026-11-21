package ru.seasonvar.tv.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.seasonvar.tv.diagnostics.DiagnosticStep
import ru.seasonvar.tv.diagnostics.LocalDiagnosticFailure

class SessionDiagnosticHistoryTest {
    @Test fun stored_failure_expires_a_day_after_the_original_event() {
        val record = SessionDiagnosticRecord(SessionOperation.SAVE,
            LocalDiagnosticFailure(DiagnosticStep.SESSION_WRITE, "COMMIT"), occurredAtMillis = 90_000_000L)
        val restored = requireNotNull(SessionDiagnosticRecord.fromStorage(record.operation, record.toStorage()))
        val oneDay = 24 * 60 * 60 * 1000L

        assertEquals(record, restored)
        assertEquals(record.occurredAtMillis + oneDay, restored.expiresAtMillis)
        assertTrue(restored.isRecent(record.occurredAtMillis + oneDay - 1))
        assertFalse(restored.isRecent(record.occurredAtMillis + oneDay))
        assertFalse(restored.isRecent(record.occurredAtMillis - 1))
    }

    @Test fun hides_old_codes_without_a_timestamp() {
        assertNull(SessionDiagnosticRecord.fromStorage(SessionOperation.RESTORE, "K02-MISSING"))
    }
}
