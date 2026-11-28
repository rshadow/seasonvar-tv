package ru.seasonvar.tv.data.settings

import android.content.Context
import android.os.StatFs
import java.io.File
import java.security.KeyStore
import ru.seasonvar.tv.data.account.DeviceSessionStore
import ru.seasonvar.tv.data.account.SessionDiagnosticHistory
import ru.seasonvar.tv.data.account.SessionDiagnosticRecord
import ru.seasonvar.tv.data.account.SessionStorageException
import ru.seasonvar.tv.data.security.KeystoreCipher
import ru.seasonvar.tv.data.update.UpdateInstaller
import ru.seasonvar.tv.diagnostics.DiagnosticStep
import ru.seasonvar.tv.diagnostics.LocalDiagnosticException
import ru.seasonvar.tv.diagnostics.LocalDiagnosticFailure
import ru.seasonvar.tv.diagnostics.diagnosticStep
import ru.seasonvar.tv.diagnostics.failDiagnostic

sealed interface DiagnosticResult {
    data object OK : DiagnosticResult
    data object NO_SESSION : DiagnosticResult
    data object UNENCRYPTED_SESSION : DiagnosticResult
    data object PERMISSION_REQUIRED : DiagnosticResult
    data class Failed(val failure: LocalDiagnosticFailure) : DiagnosticResult
}

data class DeviceDiagnosticResults(
    val storage: DiagnosticResult,
    val freeBytes: Long?,
    val freeSpace: DiagnosticResult,
    val cache: DiagnosticResult,
    val encryption: DiagnosticResult,
    val savedSession: DiagnosticResult,
    val installPermission: DiagnosticResult,
    val sessionFailures: List<SessionDiagnosticRecord>,
)

object DeviceDiagnostics {
    private const val PROBE_NAME = "device-diagnostics"
    private const val PROBE_ALIAS = "seasonvar-diagnostics-v1"

    @Synchronized
    fun check(context: Context): DeviceDiagnosticResults {
        var freeBytes: Long? = null
        val freeSpace = checkOperation(DiagnosticStep.FREE_SPACE) { freeBytes = StatFs(context.dataDir.path).availableBytes }
        return DeviceDiagnosticResults(
            storage = checkOperation(DiagnosticStep.PREFERENCES_OPEN) { checkPreferences(context) },
            freeBytes = freeBytes,
            freeSpace = freeSpace,
            cache = checkOperation(DiagnosticStep.CACHE_CREATE) { checkCache(context.cacheDir) },
            encryption = checkOperation(DiagnosticStep.KEYSTORE_OPEN) { checkEncryption() },
            savedSession = capture(DiagnosticStep.SESSION_READ) {
                val session = DeviceSessionStore(context).readSession()
                when {
                    session.cookies.isEmpty() -> DiagnosticResult.NO_SESSION
                    session.unencrypted -> DiagnosticResult.UNENCRYPTED_SESSION
                    else -> DiagnosticResult.OK
                }
            },
            installPermission = capture(DiagnosticStep.INSTALL_PERMISSION) {
                if (UpdateInstaller(context).permissionGranted()) DiagnosticResult.OK
                else DiagnosticResult.PERMISSION_REQUIRED
            },
            sessionFailures = SessionDiagnosticHistory.read(context),
        )
    }

    private inline fun checkOperation(step: DiagnosticStep, operation: () -> Unit): DiagnosticResult = capture(step) {
        operation()
        DiagnosticResult.OK
    }

    private inline fun capture(step: DiagnosticStep, operation: () -> DiagnosticResult): DiagnosticResult = try {
        operation()
    } catch (error: SessionStorageException) {
        DiagnosticResult.Failed(error.diagnostic ?: LocalDiagnosticFailure.fromException(step, error))
    } catch (error: LocalDiagnosticException) {
        DiagnosticResult.Failed(error.failure)
    } catch (error: java.util.concurrent.CancellationException) {
        throw error
    } catch (error: Exception) {
        DiagnosticResult.Failed(LocalDiagnosticFailure.fromException(step, error))
    }

    private fun checkPreferences(context: Context) {
        val preferences = context.getSharedPreferences(PROBE_NAME, Context.MODE_PRIVATE)
        try {
            val value = diagnosticStep(DiagnosticStep.PREFERENCES_READ) { !preferences.getBoolean(PROBE_NAME, false) }
            diagnosticStep(DiagnosticStep.PREFERENCES_WRITE) {
                if (!preferences.edit().putBoolean(PROBE_NAME, value).commit()) {
                    failDiagnostic(DiagnosticStep.PREFERENCES_WRITE, "COMMIT")
                }
            }
            diagnosticStep(DiagnosticStep.PREFERENCES_READ) {
                if (preferences.getBoolean(PROBE_NAME, !value) != value) failDiagnostic(DiagnosticStep.PREFERENCES_READ, "MISMATCH")
            }
        } finally {
            runCatching { context.deleteSharedPreferences(PROBE_NAME) }
        }
    }

    private fun checkCache(directory: File) {
        val file = File.createTempFile(PROBE_NAME, null, directory)
        try {
            diagnosticStep(DiagnosticStep.CACHE_WRITE) { file.writeText(PROBE_NAME) }
            diagnosticStep(DiagnosticStep.CACHE_READ) {
                if (file.readText() != PROBE_NAME) failDiagnostic(DiagnosticStep.CACHE_READ, "MISMATCH")
            }
        } finally {
            runCatching { file.delete() }
        }
    }

    private fun checkEncryption() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        diagnosticStep(DiagnosticStep.KEY_CLEANUP) {
            if (store.containsAlias(PROBE_ALIAS)) store.deleteEntry(PROBE_ALIAS)
        }
        try {
            val cipher = KeystoreCipher(PROBE_ALIAS)
            if (cipher.open(cipher.seal(PROBE_NAME)) != PROBE_NAME) failDiagnostic(DiagnosticStep.CRYPTO_DECRYPT, "MISMATCH")
        } catch (error: Exception) {
            runCatching { store.deleteEntry(PROBE_ALIAS) }
            throw error
        }
        diagnosticStep(DiagnosticStep.KEY_CLEANUP) { store.deleteEntry(PROBE_ALIAS) }
    }
}
