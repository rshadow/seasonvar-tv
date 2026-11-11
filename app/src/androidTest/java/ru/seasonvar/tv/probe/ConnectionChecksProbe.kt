package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.PublicFeed
import ru.seasonvar.tv.data.network.*
import ru.seasonvar.tv.ui.SeasonvarApp
import ru.seasonvar.tv.ui.catalog.CatalogUiState
import ru.seasonvar.tv.ui.navigation.Section
import ru.seasonvar.tv.ui.settings.ConnectionCheckViewModel
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ConnectionChecksProbe {
    fun run(probe: Instrumentation) = with(probe) {
        val result = Bundle()
        val artifacts = File(targetContext.getExternalFilesDir(null), "connection-checks-probe/run-${SystemClock.uptimeMillis()}")
        val models = ViewModelStore()
        var activity: MarkMenuPreviewActivity? = null
        var phase = "start"
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            fun settle() {
                val frames = CountDownLatch(1)
                runOnMainSync {
                    val view = checkNotNull(activity).window.decorView
                    view.postOnAnimation { view.postOnAnimation { frames.countDown() } }
                }
                check(frames.await(3, TimeUnit.SECONDS))
                waitForIdleSync()
                uiAutomation.waitForIdle(250, 3_000)
                if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
            }
            fun key(code: Int) { sendKeyDownUpSync(code); settle() }
            fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
                node ?: return null
                if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
                for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
                return null
            }
            fun node(id: Int) = checkNotNull(find(uiAutomation.rootInActiveWindow, targetContext.getString(id)))
            fun bounds(id: Int) = Rect().also { node(id).getBoundsInScreen(it) }
            fun sameGeometry(expected: Rect, actual: Rect) =
                kotlin.math.abs(expected.left - actual.left) <= 1 &&
                    kotlin.math.abs(expected.top - actual.top) <= 1 &&
                    kotlin.math.abs(expected.right - actual.right) <= 1 &&
                    kotlin.math.abs(expected.bottom - actual.bottom) <= 1
            fun capture(name: String) {
                check(artifacts.isDirectory || artifacts.mkdirs())
                val image = checkNotNull(uiAutomation.takeScreenshot())
                File(artifacts, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            val configuration = MutableStateFlow(NetworkConfiguration())
            var fixtureReport: ((ConnectionTarget, ConnectionResult) -> Unit)? = null
            var fixtureGate = CompletableDeferred<Unit>()
            var runs = 0
            lateinit var model: ConnectionCheckViewModel
            runOnMainSync {
                model = ConnectionCheckViewModel(configuration) { _, report ->
                    runs++; fixtureReport = report; fixtureGate.await()
                }
                models.put("fixture", model)
            }
            activity = startActivitySync(Intent(targetContext, MarkMenuPreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MarkMenuPreviewActivity
            fun display() = runOnMainSync { activity.setContent {
                val state by model.state.collectAsState()
                val network by configuration.collectAsState()
                SeasonvarApp(PublicFeed.entries.associateWith { CatalogUiState(loading = false) }, {},
                    startPage = Section.SETTINGS, connectionCheck = state,
                    onConnectionRefresh = model::refresh, onConnectionVisible = model::setVisible,
                    bypassEnabled = network.bypassEnabled,
                    onBypassChange = { configuration.value = configuration.value.copy(bypassEnabled = it) })
            } }
            display(); settle()
            phase = "fixture_pending"
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            repeat(9) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            node(R.string.connection_checking)
            val geometry = bounds(R.string.bypass_title)
            capture("checking")
            runOnMainSync {
                ConnectionTarget.entries.forEach { fixtureReport!!(it, ConnectionResult.available) }
                fixtureGate.complete(Unit)
            }
            settle(); node(R.string.connection_all_available)
            check(sameGeometry(geometry, bounds(R.string.bypass_title)))
            capture("available")
            key(KeyEvent.KEYCODE_DPAD_UP)
            var refreshNode = node(R.string.connection_refresh)
            while (!refreshNode.isFocusable && refreshNode.parent != null) refreshNode = refreshNode.parent
            check(refreshNode.isFocused)
            val refreshGeometry = bounds(R.string.bypass_title)
            val before = runs
            runOnMainSync { fixtureGate = CompletableDeferred() }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            check(runs == before + 1 && model.state.value.checking)
            node(R.string.connection_checking)
            runOnMainSync {
                ConnectionTarget.entries.forEach { fixtureReport!!(it, ConnectionResult.failed(ConnectionFailure.TIMEOUT)) }
                fixtureGate.complete(Unit)
            }
            settle()
            check(!model.state.value.checking && model.state.value.failed.size == 3)
            check(sameGeometry(refreshGeometry, bounds(R.string.bypass_title)))
            capture("failed")
            result.putBoolean("geometry_stable", true)
            result.putBoolean("refresh_focus_preserved", true)

            phase = "live"
            runOnMainSync {
                model.setVisible(false)
                model = ConnectionCheckViewModel(configuration, ConnectionChecker()::check)
                models.put("live", model)
            }
            // Пересоздание композиции запускает проверку реального транспорта с отдельными настройками.
            runOnMainSync { activity.setContent {} }
            settle(); display(); settle()
            fun awaitFinished() {
                val finished = CountDownLatch(1)
                lateinit var subscription: kotlinx.coroutines.Job
                runOnMainSync {
                    subscription = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        model.state.collect { if (!it.checking) finished.countDown() }
                    }
                }
                check(finished.await(16, TimeUnit.SECONDS))
                runOnMainSync { subscription.cancel() }
                settle()
                check(!model.state.value.checking)
            }
            awaitFinished()
            check(model.state.value.failed.isEmpty())
            result.putBoolean("live_available", true)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            repeat(9) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            capture("live-available")
            phase = "live_blocked"
            val closedPort = ServerSocket(0).use { it.localPort }
            val start = SystemClock.elapsedRealtime()
            runOnMainSync { configuration.value = NetworkConfiguration(false, DnsProvider.SYSTEM,
                ProxyConfiguration.Server("127.0.0.1", closedPort)) }
            settle(); awaitFinished()
            check(model.state.value.failed.size == 3)
            check(SystemClock.elapsedRealtime() - start < 14_000)
            capture("live-blocked")
            result.putBoolean("live_blocked", true)
            phase = "live_recovery"
            runOnMainSync { configuration.value = NetworkConfiguration() }
            settle(); awaitFinished()
            check(model.state.value.failed.isEmpty())
            capture("live-recovered")
            result.putBoolean("live_recovered", true)
            result.putBoolean("script_success", true)
        } catch (error: Throwable) {
            runCatching {
                check(artifacts.isDirectory || artifacts.mkdirs())
                val image = checkNotNull(uiAutomation.takeScreenshot())
                File(artifacts, "failure.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            result.putBoolean("script_success", false)
            result.putString("failure_phase", phase)
            result.putString("failure_type", error.javaClass.simpleName)
            result.putString("failure_line", error.stackTrace.firstOrNull { it.className.contains("ConnectionChecksProbe") }?.lineNumber?.toString())
        } finally {
            if (artifacts.exists()) result.putString("artifacts", artifacts.absolutePath)
            runOnMainSync { models.clear(); activity?.finish() }
            finish(Activity.RESULT_OK, result)
        }
    }
}
