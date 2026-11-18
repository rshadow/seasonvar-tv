package ru.seasonvar.tv.baselineprofile

import android.content.Intent
import android.os.Build
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val profile = BaselineProfileRule()
    private val packageName = "ru.seasonvar.tv.baseline"
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test fun startup() {
        check(Build.MODEL.contains("sdk", true))
        profile.collect(packageName, maxIterations = 5, stableIterations = 2, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait(Intent().setClassName(packageName, "ru.seasonvar.tv.MainActivity"))
        }
    }

    @Test fun cachedPersonalStartup() {
        check(Build.MODEL.contains("sdk", true))
        profile.collect(packageName, maxIterations = 5, stableIterations = 2, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait(Intent().setClassName(packageName, "ru.seasonvar.tv.baseline.BaselineActivity")
                .putExtra("cached_personal_start", true))
            check(device.wait(Until.hasObject(By.text("Карточка 001")), 10_000)) { "Cached personal cards are missing" }
            check(device.wait(Until.hasObject(By.focused(true).hasDescendant(By.text("На чём остановились"))), 10_000))
        }
    }

    @Test fun navigation() {
        check(Build.MODEL.contains("sdk", true))
        profile.collect(packageName, maxIterations = 5, stableIterations = 2, includeInStartupProfile = false) {
            for (glass in listOf(false, true)) for (grid in listOf(false, true)) {
                pressHome()
                startActivityAndWait(Intent().setClassName(packageName, "ru.seasonvar.tv.baseline.BaselineActivity")
                    .putExtra("glass", glass).putExtra("grid", grid))
                check(device.wait(Until.hasObject(By.text("Карточка 001")), 10_000))
                check(device.wait(Until.hasObject(By.focused(true).hasDescendant(By.text("Топ"))), 10_000))
                device.pressDPadRight()
                check(device.wait(Until.hasObject(By.focused(true).hasDescendant(By.text("Карточка 001"))), 10_000))
                device.pressDPadCenter()
                check(device.wait(Until.hasObject(By.textStartsWith("Прогресс:")), 10_000)) {
                    "Details missing: glass=$glass grid=$grid focus=" + device.findObject(By.focused(true))?.text
                }
                device.pressBack()
                check(device.wait(Until.gone(By.textStartsWith("Прогресс:")), 10_000))
                check(device.wait(Until.hasObject(By.focused(true).hasDescendant(By.text("Карточка 001"))), 10_000))
                repeat(8) { if (grid) device.pressDPadDown() else device.pressDPadRight(); device.waitForIdle(1_000) }
                repeat(8) { if (grid) device.pressDPadUp() else device.pressDPadLeft(); device.waitForIdle(1_000) }
                device.pressBack()
                repeat(4) { device.pressDPadDown(); device.waitForIdle(1_000) }
                repeat(4) { device.pressDPadUp(); device.waitForIdle(1_000) }
            }
        }
    }
}
