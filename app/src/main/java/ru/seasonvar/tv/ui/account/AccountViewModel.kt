package ru.seasonvar.tv.ui.account

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.seasonvar.tv.data.SiteHttpException
import ru.seasonvar.tv.data.account.*
import java.io.IOException

enum class AccountFailure { CREDENTIALS, EXPIRED, NETWORK, HTTP, MARKUP, STORAGE }
data class AccountUiState(val busy: Boolean = true, val failure: AccountFailure? = null, val storageFailureCode: String? = null,
    val awaitingSessionStorageChoice: Boolean = false)

class AccountViewModel(private val repository: AccountRepository, private val context: Context? = null) : ViewModel() {
    private val mutableState = MutableStateFlow(AccountUiState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var pendingLogin: AccountRepository.PendingLogin? = null
    init { restore() }
    fun restore() = work(SessionOperation.RESTORE) { repository.restore(); null }
    fun login(login: String, password: String) = work(SessionOperation.SAVE) { repository.login(login, password) }
    fun logout() = work { repository.logout(); null }

    fun chooseSessionStorage(remember: Boolean) {
        val login = pendingLogin ?: return
        work(SessionOperation.SAVE) { repository.finishLogin(login, remember); null }
    }

    private fun work(sessionOperation: SessionOperation? = null, action: suspend () -> AccountRepository.PendingLogin?) {
        job?.cancel()
        pendingLogin = null
        mutableState.value = AccountUiState()
        job = viewModelScope.launch {
            try {
                val pending = action()
                recordFailure(sessionOperation, pending?.failure)
                pendingLogin = pending
                mutableState.value = AccountUiState(busy = false, awaitingSessionStorageChoice = pending != null)
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val storageError = e as? SessionStorageException
                recordFailure(sessionOperation, storageError)
                mutableState.value = AccountUiState(false, when (e) {
                    is LoginRejectedException -> AccountFailure.CREDENTIALS
                    is SessionExpiredException -> AccountFailure.EXPIRED
                    is SiteHttpException -> AccountFailure.HTTP
                    is IOException -> AccountFailure.NETWORK
                    is SessionStorageException -> AccountFailure.STORAGE
                    else -> AccountFailure.MARKUP
                }, storageError?.diagnostic?.code)
            }
        }
    }

    private suspend fun recordFailure(operation: SessionOperation?, error: SessionStorageException?) {
        val failure = error?.diagnostic ?: return
        if (context != null && operation != null) withContext(Dispatchers.IO) {
            SessionDiagnosticHistory.record(context, SessionDiagnosticRecord(operation, failure))
        }
    }
    class Factory(private val repository: AccountRepository, private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AccountViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return AccountViewModel(repository, context.applicationContext) as T
        }
    }
}
