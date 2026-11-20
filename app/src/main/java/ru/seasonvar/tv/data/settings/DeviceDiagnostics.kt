package ru.seasonvar.tv.data.settings

import android.content.Context
import android.os.StatFs
import java.io.File
import java.security.KeyStore
import ru.seasonvar.tv.data.account.EncryptedSessionStore
import ru.seasonvar.tv.data.security.KeystoreCipher
import ru.seasonvar.tv.data.update.UpdateInstaller

internal enum class DiagnosticResult { OK, FAILED, NO_SESSION, PERMISSION_REQUIRED }

internal data class DeviceDiagnosticResults(
    val storage: DiagnosticResult,
    val freeBytes: Long?,
    val cache: DiagnosticResult,
    val encryption: DiagnosticResult,
    val savedSession: DiagnosticResult,
    val installPermission: DiagnosticResult,
)

internal object DeviceDiagnostics {
    private const val PROBE_NAME = "device-diagnostics"
    private const val PROBE_ALIAS = "seasonvar-diagnostics-v1"

    @Synchronized
    fun check(context: Context): DeviceDiagnosticResults = DeviceDiagnosticResults(
        storage = checkOperation { checkPreferences(context) },
        freeBytes = try { StatFs(context.dataDir.path).availableBytes } catch (_: Exception) { null },
        cache = checkOperation { checkCache(context.cacheDir) },
        encryption = checkOperation { checkEncryption() },
        savedSession = try {
            if (EncryptedSessionStore(context).readSession().cookies.isEmpty()) DiagnosticResult.NO_SESSION
            else DiagnosticResult.OK
        } catch (_: Exception) { DiagnosticResult.FAILED },
        installPermission = try {
            if (UpdateInstaller(context).permissionGranted()) DiagnosticResult.OK
            else DiagnosticResult.PERMISSION_REQUIRED
        } catch (_: Exception) { DiagnosticResult.FAILED },
    )

    private inline fun checkOperation(operation: () -> Unit): DiagnosticResult = try {
        operation()
        DiagnosticResult.OK
    } catch (_: Exception) { DiagnosticResult.FAILED }

    private fun checkPreferences(context: Context) {
        val preferences = context.getSharedPreferences(PROBE_NAME, Context.MODE_PRIVATE)
        try {
            val value = !preferences.getBoolean(PROBE_NAME, false)
            check(preferences.edit().putBoolean(PROBE_NAME, value).commit())
            check(preferences.getBoolean(PROBE_NAME, !value) == value)
        } finally {
            context.deleteSharedPreferences(PROBE_NAME)
        }
    }

    private fun checkCache(directory: File) {
        val file = File.createTempFile(PROBE_NAME, null, directory)
        try {
            file.writeText(PROBE_NAME)
            check(file.readText() == PROBE_NAME)
        } finally {
            file.delete()
        }
    }

    private fun checkEncryption() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.deleteEntry(PROBE_ALIAS)
        try {
            val cipher = KeystoreCipher(PROBE_ALIAS)
            check(cipher.open(cipher.seal(PROBE_NAME)) == PROBE_NAME)
        } finally {
            store.deleteEntry(PROBE_ALIAS)
        }
    }
}
