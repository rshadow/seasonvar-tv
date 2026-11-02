package ru.seasonvar.tv.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import ru.seasonvar.tv.data.SiteHttpException

class FailureSummaryTest {
    @Test fun reports_types_and_status_without_exception_messages_or_urls() {
        val error = IOException("https://example.test/private?example=sensitive", SiteHttpException(503))
        val summary = failureSummary(error)
        assertTrue(summary.contains("IOException"))
        assertTrue(summary.contains("http=503"))
        assertFalse(summary.contains("https:"))
        assertFalse(summary.contains("sensitive"))
    }
    @Test fun limits_cyclic_cause_chains() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)
        assertTrue(failureSummary(first).length < 2000)
    }
}
