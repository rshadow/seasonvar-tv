package ru.seasonvar.tv.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.seasonvar.tv.data.network.*
import ru.seasonvar.tv.data.settings.ConnectionCheckSettings

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionCheckViewModelTest {
    @Before fun mainDispatcher() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun resetDispatcher() { Dispatchers.resetMain() }

    @Test fun deadline_finishes_every_pending_indicator_and_preserves_completed_results() = runTest {
        lateinit var lateReport: (ConnectionTarget, ConnectionResult) -> Unit
        val model = ConnectionCheckViewModel(MutableStateFlow(NetworkConfiguration())) { _, report ->
            lateReport = report
            report(ConnectionTarget.SITE, ConnectionResult.available)
            awaitCancellation()
        }
        model.setVisible(true)
        runCurrent()
        advanceTimeBy(ConnectionCheckSettings.CYCLE_TIMEOUT_MILLIS)
        runCurrent()
        assertFalse(model.state.value.checking)
        assertEquals(ConnectionResult.available, model.state.value.results[ConnectionTarget.SITE])
        assertEquals(listOf(ConnectionTarget.POSTERS, ConnectionTarget.VIDEO), model.state.value.failed)
        assertEquals(ConnectionFailure.TIMEOUT, model.state.value.results[ConnectionTarget.VIDEO]?.failure)
        lateReport(ConnectionTarget.VIDEO, ConnectionResult.available)
        assertEquals(ConnectionFailure.TIMEOUT, model.state.value.results[ConnectionTarget.VIDEO]?.failure)
        model.setVisible(false)
    }

    @Test fun refresh_is_immediate_and_late_callbacks_cannot_overwrite_the_next_run() = runTest {
        val reports = mutableListOf<(ConnectionTarget, ConnectionResult) -> Unit>()
        var cancellations = 0
        val model = ConnectionCheckViewModel(MutableStateFlow(NetworkConfiguration())) { _, report ->
            reports += report
            try { awaitCancellation() } finally { cancellations++ }
        }
        model.setVisible(true); runCurrent()
        reports[0](ConnectionTarget.SITE, ConnectionResult.available)
        model.refresh()
        assertTrue(model.state.value.results.values.all { it.status == ConnectionStatus.CHECKING })
        runCurrent()
        assertEquals(1, cancellations)
        reports[0](ConnectionTarget.POSTERS, ConnectionResult.available)
        assertEquals(ConnectionStatus.CHECKING, model.state.value.results[ConnectionTarget.POSTERS]?.status)
        reports[1](ConnectionTarget.POSTERS, ConnectionResult.available)
        assertEquals(ConnectionStatus.AVAILABLE, model.state.value.results[ConnectionTarget.POSTERS]?.status)
        model.setVisible(false); runCurrent()
        assertEquals(2, cancellations)
        assertFalse(model.state.value.checking)
    }

    @Test fun applied_configuration_restarts_active_checks_but_hidden_settings_make_no_requests() = runTest {
        val configuration = MutableStateFlow(NetworkConfiguration())
        val selected = mutableListOf<NetworkConfiguration>()
        val model = ConnectionCheckViewModel(configuration) { value, _ -> selected += value; awaitCancellation() }
        runCurrent()
        assertTrue(selected.isEmpty())
        model.setVisible(true); runCurrent()
        configuration.value = configuration.value.copy(dnsProvider = DnsProvider.CLOUDFLARE_DOH)
        runCurrent()
        configuration.value = configuration.value.copy(bypassEnabled = false)
        runCurrent()
        configuration.value = configuration.value.copy(proxy = ProxyConfiguration.Unavailable)
        runCurrent()
        assertEquals(4, selected.size)
        assertEquals(configuration.value, selected.last())
        model.setVisible(false)
        configuration.value = NetworkConfiguration()
        runCurrent()
        assertEquals(4, selected.size)
        model.setVisible(true); runCurrent()
        assertEquals(NetworkConfiguration(), selected.last())
        model.setVisible(false)
    }

    @Test fun exception_or_missing_reports_cannot_leave_gray_indicators() = runTest {
        for (throws in listOf(false, true)) {
            val model = ConnectionCheckViewModel(MutableStateFlow(NetworkConfiguration())) { _, report ->
                report(ConnectionTarget.POSTERS, ConnectionResult.failed(ConnectionFailure.CONTENT))
                if (throws) throw IllegalStateException()
            }
            model.setVisible(true); runCurrent()
            assertFalse(model.state.value.checking)
            assertEquals(ConnectionTarget.entries, model.state.value.failed)
            model.setVisible(false)
        }
    }
}
