package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import coil.imageLoader
import kotlinx.coroutines.runBlocking
import ru.seasonvar.tv.MainActivity
import ru.seasonvar.tv.SeasonvarApplication
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object CacheMemoryProbe {
    fun run(probe: Instrumentation) = with(probe) {
        val result = Bundle()
        var activity: Activity? = null
        var phase = "start"
        var fixture: SharedCardCacheFixture? = null
        val artifacts = File(checkNotNull(targetContext.getExternalFilesDir("cache-memory-probe")), "run-" + System.currentTimeMillis())
        fun stage(value: String) { phase = value; sendStatus(Activity.RESULT_OK, Bundle().apply { putString("phase", value) }) }
        try {
            check(android.os.Build.MODEL.contains("sdk", true) || android.os.Build.FINGERPRINT.contains("generic"))
            val app = targetContext.applicationContext as SeasonvarApplication
            stage("warm_lists")
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(2500)
            for (key in List(6) { KeyEvent.KEYCODE_DPAD_DOWN } + List(6) { KeyEvent.KEYCODE_DPAD_UP }) {
                sendKeyDownUpSync(key); Thread.sleep(400)
            }
            val end = SystemClock.elapsedRealtime() + 20000
            var quietSince = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() < end) {
                if (app.listResourcePermits.availablePermits != 3) quietSince = SystemClock.elapsedRealtime()
                if (SystemClock.elapsedRealtime() - quietSince >= 1000) break
                Thread.sleep(100)
            }
            check(artifacts.isDirectory || artifacts.mkdirs())
            val image = checkNotNull(uiAutomation.takeScreenshot())
            File(artifacts, "warm-lists.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            val opened = checkNotNull(activity)
            runOnMainSync { opened.finish() }; activity = null
            Thread.sleep(1000)
            val posters = app.imageLoader.memoryCache
            result.putString("poster_kib", ((posters?.size ?: 0) / 1024).toString())
            result.putString("poster_limit_kib", ((posters?.maxSize ?: 0) / 1024).toString())
            result.putString("poster_keys", (posters?.keys?.size ?: 0).toString())
            result.putString("live_card_records", app.cardData.size.toString())
            stage("memory_capacity")
            val measured = SharedCardCacheFixture(targetContext, ConcurrentHashMap())
            fixture = measured
            val measurements = runBlocking { measured.measureNativeMemory() }
            for ((key, value) in measurements) result.putString(key, value.toString())
            result.putBoolean("memory_measured", true); result.putBoolean("read_only", true)
            result.putBoolean("script_success", true); result.putString("artifacts", artifacts.absolutePath)
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putBoolean("script_success", false); result.putString("failure_type", error.javaClass.simpleName)
            result.putString("failure_phase", phase)
            result.putString("failure_line", error.stackTrace.firstOrNull { it.fileName == "CacheMemoryProbe.kt" }?.lineNumber?.toString().orEmpty())
            finish(Activity.RESULT_CANCELED, result)
        } finally { activity?.let { runOnMainSync { it.finish() } }; fixture?.close() }
    }
}
