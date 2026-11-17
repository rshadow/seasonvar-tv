package ru.seasonvar.tv.probe

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import ru.seasonvar.tv.data.network.NetworkSettings
import ru.seasonvar.tv.data.network.ProxyConfiguration

/** Настройки изолированы от пользовательских; выбранный прокси хранится только в тестовом UI. */
internal class ProxySettingsFixtures(context: Context) : AutoCloseable {
    val storeName = "proxy-fixture-${android.os.SystemClock.uptimeMillis()}"
    val preferences = context.getSharedPreferences(storeName, Context.MODE_PRIVATE)
    val settings = NetworkSettings(context, storeName)
    val proxy = mutableStateOf<ProxyConfiguration>(ProxyConfiguration.Disabled)
    var saves = 0
    override fun close() { preferences.edit().clear().commit() }
}
