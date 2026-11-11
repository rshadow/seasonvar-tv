package ru.seasonvar.tv.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
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

    @Test fun applying_proxy_cancels_old_checks_and_failed_activation_checks_the_previous_configuration() = runTest {
        val configuration = MutableStateFlow(NetworkConfiguration())
        val events = mutableListOf<NetworkConfiguration>()
        val model = ConnectionCheckViewModel(configuration) { cfg, report ->
            events += cfg
            ConnectionTarget.entries.forEach { report(it, ConnectionResult.available) }
        }
        model.setVisible(true); runCurrent()
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        val applying = launch { assertFalse(model.reconfigure { ready.await(); false }) }
        runCurrent()
        assertEquals(1, events.size)
        assertTrue(model.state.value.checking)
        model.refresh(); runCurrent()
        assertEquals(1, events.size)
        ready.complete(Unit); applying.join(); runCurrent()
        assertEquals(listOf(configuration.value, configuration.value), events)
        assertFalse(model.state.value.checking)
        model.setVisible(false)
    }

    @Test fun probes_wait_for_network_preparation_and_old_preparations_cannot_start_them() = runTest {
        val configuration = MutableStateFlow(NetworkConfiguration())
        val gates = mutableListOf<kotlinx.coroutines.CompletableDeferred<Unit>>()
        val checked = mutableListOf<NetworkConfiguration>()
        val changes = MutableStateFlow(0L)
        val model = ConnectionCheckViewModel(configuration, prepare = {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>(); gates += gate; gate.await()
        }, changes = changes) { cfg, report ->
            checked += cfg
            ConnectionTarget.entries.forEach { report(it, ConnectionResult.available) }
        }
        runCurrent()
        model.setVisible(true); runCurrent()
        assertTrue(checked.isEmpty())
        configuration.value = configuration.value.copy(dnsProvider = DnsProvider.SYSTEM)
        runCurrent()
        gates.first().complete(Unit); runCurrent()
        assertTrue(checked.isEmpty())
        gates.last().complete(Unit); runCurrent()
        assertEquals(listOf(configuration.value), checked)
        changes.value++; runCurrent()
        assertTrue(model.state.value.checking)
        assertEquals(1, checked.size)
        gates.last().complete(Unit); runCurrent()
        assertEquals(2, checked.size)
        model.refresh(); runCurrent()
        advanceTimeBy(ConnectionCheckSettings.CYCLE_TIMEOUT_MILLIS); runCurrent()
        assertEquals(2, checked.size)
        assertFalse(model.state.value.checking)
        assertEquals(ConnectionTarget.entries, model.state.value.failed)
        model.setVisible(false)
    }

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
