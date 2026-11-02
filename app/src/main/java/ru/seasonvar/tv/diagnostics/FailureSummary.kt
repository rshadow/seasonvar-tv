package ru.seasonvar.tv.diagnostics

import ru.seasonvar.tv.data.SiteHttpException
import androidx.media3.datasource.HttpDataSource

internal fun failureSummary(error: Throwable): String = generateSequence(error) { it.cause }.take(6).joinToString(" <- ") { cause ->
    val location = cause.stackTrace.firstOrNull { it.className.startsWith("ru.seasonvar.tv.") }
    val status = when (cause) {
        is SiteHttpException -> " http=" + cause.status
        is HttpDataSource.InvalidResponseCodeException -> " http=" + cause.responseCode
        else -> ""
    }
    cause.javaClass.simpleName + status + (location?.let { " at=" + it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber } ?: "")
}
