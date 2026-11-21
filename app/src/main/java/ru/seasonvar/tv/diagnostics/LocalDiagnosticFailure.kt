package ru.seasonvar.tv.diagnostics

import android.os.Build
import android.system.ErrnoException
import java.io.IOException
import java.security.InvalidKeyException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

internal enum class DiagnosticStep(val code: String) {
    FREE_SPACE("D01"), PREFERENCES_OPEN("D02"), PREFERENCES_WRITE("D03"), PREFERENCES_READ("D04"),
    CACHE_CREATE("D05"), CACHE_WRITE("D06"), CACHE_READ("D07"),
    KEYSTORE_OPEN("K01"), KEY_LOOKUP("K02"), KEY_CREATE("K03"), KEY_CLEANUP("K04"),
    CRYPTO_ENCRYPT("K05"), CRYPTO_DECRYPT("K06"), CRYPTO_FORMAT("K07"),
    SESSION_READ("S01"), SESSION_DECODE("S02"), SESSION_ENCODE("S03"), SESSION_WRITE("S04"),
    INSTALL_PERMISSION("D08"),
}

internal data class LocalDiagnosticFailure(val step: DiagnosticStep, val reason: String) {
    val code get() = "${step.code}-$reason"

    companion object {
        private val fixedReasons = setOf("MISSING", "COMMIT", "MISMATCH", "AUTH", "KEY", "ACCESS", "FORMAT", "IO", "UNKNOWN")
        private val platformReason = Regex("(?:KS|OS)[1-9][0-9]{0,9}")

        fun fromCode(code: String): LocalDiagnosticFailure? {
            val step = DiagnosticStep.entries.firstOrNull { it.code == code.substringBefore('-') } ?: return null
            val reason = code.substringAfter('-', "")
            return reason.takeIf { it in fixedReasons || it.matches(platformReason) }?.let { LocalDiagnosticFailure(step, it) }
        }

        fun fromException(step: DiagnosticStep, error: Exception): LocalDiagnosticFailure {
            val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
            val errno = causes.filterIsInstance<ErrnoException>().firstOrNull()
            val keystore = if (Build.VERSION.SDK_INT >= 33)
                causes.filterIsInstance<android.security.KeyStoreException>().firstOrNull()?.numericErrorCode else null
            val reason = when {
                errno != null && errno.errno > 0 -> "OS${errno.errno}"
                keystore != null && keystore > 0 -> "KS$keystore"
                causes.any { it is AEADBadTagException } -> "AUTH"
                causes.any { it is InvalidKeyException || it is UnrecoverableKeyException } -> "KEY"
                causes.any { it is SecurityException } -> "ACCESS"
                causes.any { it is IllegalArgumentException || it is ClassCastException } -> "FORMAT"
                causes.any { it is IOException } -> "IO"
                else -> "UNKNOWN"
            }
            return LocalDiagnosticFailure(step, reason)
        }
    }
}

internal class LocalDiagnosticException(val failure: LocalDiagnosticFailure) : Exception(failure.code)

internal fun failDiagnostic(step: DiagnosticStep, reason: String): Nothing =
    throw LocalDiagnosticException(LocalDiagnosticFailure(step, reason))

internal inline fun <T> diagnosticStep(step: DiagnosticStep, operation: () -> T): T = try {
    operation()
} catch (error: LocalDiagnosticException) {
    throw error
} catch (error: java.util.concurrent.CancellationException) {
    throw error
} catch (error: Exception) {
    throw LocalDiagnosticException(LocalDiagnosticFailure.fromException(step, error))
}
