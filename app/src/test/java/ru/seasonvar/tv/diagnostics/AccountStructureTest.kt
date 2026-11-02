package ru.seasonvar.tv.diagnostics

import org.junit.Assert.*
import org.junit.Test

class AccountStructureTest {
    @Test fun summary_contains_structure_but_no_user_values_or_urls() {
        val html = """<div class="headblock-menu"><a href="/profile/123">private-user-name</a>
            <img src="https://example.test/private-avatar" alt="private-display-name"></div>
            <div class="pgs-marks-bn"><a href="/serial-123-private.html">private-title</a></div>"""
        val summary = accountStructure(html)
        assertTrue(summary.contains("headerImages=1"))
        assertTrue(summary.contains("bnSerialLinks=1"))
        assertFalse(summary.contains("private"))
        assertFalse(summary.contains("123"))
        assertFalse(summary.contains("https:"))
    }
    @Test fun history_summary_never_contains_account_titles_dates_or_links() {
        val html = """<div class="pgs-history"><div data-result="history"><div class="pgs-history-c">
            <a href="/serial-987-private-title.html"><span>private-date</span><b>private-name</b> (7 сезон)</a>
            </div><div class="pgs-history-p"><a href="/?mod=history&page=2">2</a></div></div></div>"""
        val summary = historyStructure(html)
        assertTrue(summary.contains("historyRows=1"))
        assertFalse(summary.contains("private"))
        assertFalse(summary.contains("987"))
        assertFalse(summary.contains("href="))
        assertFalse(summary.contains("https:"))
    }

}
