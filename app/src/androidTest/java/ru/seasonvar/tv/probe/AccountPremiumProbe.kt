package ru.seasonvar.tv.probe

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.account.AccountParser
import ru.seasonvar.tv.data.account.AccountPremium

object AccountPremiumProbe {
    fun run(probe: Instrumentation) {
        val result = Bundle()
        var phase = "restore"
        try {
            val app = probe.targetContext.applicationContext as SeasonvarApplication
            runBlocking {
                app.account.restore()
                val user = checkNotNull(app.account.session.value.user)
                result.putString("premium_kind", user.premium.javaClass.simpleName)
                val version = app.account.session.value.version
                phase = "resume"
                val resume = app.siteClient.accountGet("https://seasonvar.ru/resume", app.sessions.current())
                val menu = Jsoup.parse(resume, "https://seasonvar.ru/")
                val paths = linkedMapOf("resume" to "/resume", "home" to "/")
                for (node in menu.select(".headblock-menu a[href]")) {
                    val url = node.absUrl("href").toHttpUrlOrNull() ?: continue
                    if (url.isHttps && url.host == "seasonvar.ru" && url.port == 443 && url.query == null) {
                        if (url.encodedPath == "/premium") {
                            paths["premium"] = url.encodedPath
                            val label = node.text()
                            if (label.startsWith("Премиум")) result.putString("menu_label", label.take(160))
                        }
                        if (url.encodedPath.trimEnd('/') == "/profile/${user.id}") paths["profile"] = url.encodedPath
                    }
                }
                val premium = user.premium as? AccountPremium.Until ?: error("No premium expiration")
                for ((source, path) in paths) {
                    phase = source
                    val html = if (source == "resume") resume else app.siteClient.accountGet("https://seasonvar.ru$path", app.sessions.current())
                    val parsed = checkNotNull(AccountParser.user(html))
                    check(parsed.id == user.id && parsed.premium == premium)
                    result.putBoolean(source + "_verified", true)
                }
                phase = "refresh"
                app.account.restore()
                check(app.account.session.value.version == version && app.account.session.value.user?.premium == premium)
                result.putBoolean("refresh_keeps_session", true)
                result.putString("premium_until", premium.date.toString())
            }
            result.putBoolean("probe_success", true)
            probe.finish(Activity.RESULT_OK, result)
        } catch (error: Exception) {
            result.putBoolean("probe_success", false)
            result.putString("phase", phase)
            result.putString("failure_type", error.javaClass.simpleName)
            probe.finish(Activity.RESULT_CANCELED, result)
        }
    }
}
