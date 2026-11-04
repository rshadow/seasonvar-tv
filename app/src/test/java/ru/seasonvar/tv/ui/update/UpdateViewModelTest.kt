package ru.seasonvar.tv.ui.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.seasonvar.tv.data.update.*
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val temporary = TemporaryFolder()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private val release = GitHubRelease("0.4.0", ReleaseAsset("test.apk", "https://github.com/test", 1, "0".repeat(64)))

    @Test fun entering_checks_automatically_coalesces_requests_and_rechecks_on_return() = runTest(dispatcher) {
        var checks = 0
        val model = UpdateViewModel("0.3.1", { checks++; delay(1000); release }, { _, _ -> error("Not clicked") })
        model.enter(); model.enter(); runCurrent()
        assertEquals(1, checks)
        assertTrue(model.state.value.checking)
        advanceUntilIdle()
        assertTrue(model.state.value.available)
        assertFalse(model.state.value.installRequested)
        model.enter(); advanceUntilIdle()
        assertEquals(2, checks)
    }

    @Test fun same_older_and_missing_release_do_not_offer_installation() = runTest(dispatcher) {
        listOf(null, release.copy(version = "0.3.1"), release.copy(version = "0.2.0")).forEach { latest ->
            val model = UpdateViewModel("0.3.1", { latest }, { _, _ -> error("Unexpected download") })
            model.enter(); advanceUntilIdle(); model.install()
            assertTrue(model.state.value.checked)
            assertFalse(model.state.value.available)
            assertFalse(model.state.value.installRequested)
        }
    }

    @Test fun missing_asset_does_not_start_download() = runTest(dispatcher) {
        val model = UpdateViewModel("0.3.1", { release.copy(asset = null) }, { _, _ -> error("Unexpected download") })
        model.enter(); advanceUntilIdle(); model.install()
        assertTrue(model.state.value.available)
        assertFalse(model.state.value.downloading)
    }

    @Test fun installation_requires_click_download_completion_and_no_duplicate_download() = runTest(dispatcher) {
        var downloads = 0
        val ready = temporary.newFile("update.apk")
        val model = UpdateViewModel("0.3.1", { release }, { _, progress ->
            downloads++; progress(42); delay(1000); ready
        })
        model.enter(); advanceUntilIdle()
        assertEquals(0, downloads)
        model.install(); model.install(); runCurrent()
        assertTrue(model.state.value.downloading)
        assertEquals(42, model.state.value.percent)
        assertFalse(model.state.value.installRequested)
        model.enter(); advanceUntilIdle()
        assertEquals(1, downloads)
        assertTrue(model.state.value.installRequested)
        model.installRequestHandled(); model.enter()
        assertTrue(model.state.value.installing)
        assertEquals(ready, model.state.value.ready)
        model.installationReturned(); model.install()
        assertTrue(model.state.value.installRequested)
        assertEquals(1, downloads)
    }

    @Test fun check_failures_are_visible_and_reentering_retries() = runTest(dispatcher) {
        var error: Exception? = IOException()
        val model = UpdateViewModel("0.3.1", { error?.let { throw it }; release }, { _, _ -> error("unused") })
        model.enter(); advanceUntilIdle()
        assertEquals(UpdateFailure.NETWORK, model.state.value.failure)
        assertFalse(model.state.value.available)
        error = null; model.enter(); advanceUntilIdle()
        assertTrue(model.state.value.available)
        assertNull(model.state.value.failure)
    }

    @Test fun damaged_wrong_signature_and_incompatible_apks_never_request_installation() = runTest(dispatcher) {
        listOf(UpdateIntegrityException() to UpdateFailure.INTEGRITY,
            UpdateSignatureException() to UpdateFailure.SIGNATURE,
            UpdatePackageException() to UpdateFailure.PACKAGE,
            UpdateHttpException(403) to UpdateFailure.HTTP).forEach { (exception, failure) ->
            val model = UpdateViewModel("0.3.1", { release }, { _, _ -> throw exception })
            model.enter(); advanceUntilIdle(); model.install(); advanceUntilIdle()
            assertEquals(failure, model.state.value.failure)
            assertNull(model.state.value.ready)
            assertFalse(model.state.value.installRequested)
            assertFalse(model.state.value.downloading)
        }
    }

    @Test fun denied_permission_and_cancelled_installer_allow_retry_without_downloading() = runTest(dispatcher) {
        var downloads = 0
        val model = UpdateViewModel("0.3.1", { release }, { _, _ -> downloads++; temporary.newFile() })
        model.enter(); advanceUntilIdle(); model.install(); advanceUntilIdle(); model.installRequestHandled()
        model.installationFailed(UpdateFailure.PERMISSION)
        assertFalse(model.state.value.installing)
        model.install()
        assertTrue(model.state.value.installRequested)
        assertNull(model.state.value.failure)
        assertEquals(1, downloads)
    }
}
