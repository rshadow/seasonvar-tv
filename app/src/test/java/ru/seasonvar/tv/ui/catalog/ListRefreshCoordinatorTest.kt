package ru.seasonvar.tv.ui.catalog

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Test
import ru.seasonvar.tv.data.network.NetworkConfiguration
import ru.seasonvar.tv.ui.navigation.Section
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ListRefreshCoordinatorTest {
    private val initial = NetworkConfiguration()
    private val changed = initial.copy(bypassEnabled = false)

    @Test fun cancels_old_work_then_prepares_before_refreshing_visible_and_hidden_targets_once() = runTest {
        val events = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val coordinator = ListRefreshCoordinator(this, initial, { events += "prepare"; gate.await() },
            linkedMapOf(Section.TOP to ListRefreshCoordinator.Target({ events += "cancel-top" }, { events += "top" }),
                Section.WANT to ListRefreshCoordinator.Target({ events += "cancel-want" }, { events += "want" })))
        coordinator.setVisible(Section.WANT)
        coordinator.networkChanged(changed); runCurrent()
        assertEquals(listOf("cancel-top", "cancel-want", "prepare"), events)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf("cancel-top", "cancel-want", "prepare", "want", "top"), events)
        coordinator.setVisible(null)
        coordinator.networkChanged(initial); runCurrent()
        assertEquals(2, events.count { it == "top" }); assertEquals(2, events.count { it == "want" })
    }

    @Test fun repeated_configuration_and_visibility_changes_do_not_restart_a_refresh() = runTest {
        var requests = 0; var cancels = 0; var preparations = 0
        val coordinator = ListRefreshCoordinator(this, initial, { preparations++ }, mapOf(
            Section.TOP to ListRefreshCoordinator.Target({ cancels++ }, { requests++ })))
        coordinator.networkChanged(initial)
        coordinator.setVisible(Section.TOP); coordinator.setVisible(null)
        assertEquals(0, requests); assertEquals(0, cancels)
        coordinator.networkChanged(changed); coordinator.networkChanged(changed); runCurrent()
        assertEquals(1, requests); assertEquals(1, cancels); assertEquals(1, preparations)
    }

    @Test fun late_old_preparation_cannot_start_an_obsolete_cycle() = runTest {
        val old = CompletableDeferred<Unit>(); var requests = 0
        val coordinator = ListRefreshCoordinator(this, initial, {
            if (it == changed) withContext(NonCancellable) { old.await() }
        }, mapOf(Section.TOP to ListRefreshCoordinator.Target({}, { requests++ })))
        coordinator.networkChanged(changed); runCurrent()
        coordinator.networkChanged(initial); runCurrent()
        assertEquals(1, requests)
        old.complete(Unit); runCurrent()
        assertEquals(1, requests)
    }

    @Test fun failed_preparation_still_allows_lists_to_report_their_own_connection_errors() = runTest {
        var requests = 0
        val coordinator = ListRefreshCoordinator(this, initial, { throw IOException() }, mapOf(
            Section.TOP to ListRefreshCoordinator.Target({}, { requests++ })))
        coordinator.networkChanged(changed); runCurrent()
        assertEquals(1, requests)
    }

    @Test fun changing_visibility_during_dispatch_never_duplicates_or_skips_a_target() = runTest {
        val calls = mutableListOf<Section>()
        lateinit var coordinator: ListRefreshCoordinator
        coordinator = ListRefreshCoordinator(this, initial, {}, linkedMapOf(
            Section.TOP to ListRefreshCoordinator.Target({}, { calls += Section.TOP; coordinator.setVisible(Section.WANT) }),
            Section.WANT to ListRefreshCoordinator.Target({}, { calls += Section.WANT })))
        coordinator.setVisible(Section.TOP)
        coordinator.networkChanged(changed); runCurrent()
        assertEquals(listOf(Section.TOP, Section.WANT), calls)
    }

    @Test fun recreating_the_activity_owner_keeps_one_pending_cycle_and_clearing_it_cancels_the_cycle() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); val gate = CompletableDeferred<Unit>(); var requests = 0
        val targets = mapOf(Section.TOP to ListRefreshCoordinator.Target({}, { requests++ }))
        try {
            val first = ViewModelProvider(store, ListRefreshCoordinator.Factory(initial, { gate.await() }, targets))[ListRefreshCoordinator::class.java]
            first.networkChanged(changed); runCurrent()
            val recreated = ViewModelProvider(store, ListRefreshCoordinator.Factory(changed, {}, targets))[ListRefreshCoordinator::class.java]
            assertSame(first, recreated)
            gate.complete(Unit); runCurrent(); assertEquals(1, requests)
            recreated.networkChanged(changed); runCurrent(); assertEquals(1, requests)
            recreated.networkChanged(initial); store.clear(); runCurrent()
            assertEquals(1, requests)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
