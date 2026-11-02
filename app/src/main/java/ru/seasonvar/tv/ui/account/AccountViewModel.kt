package ru.seasonvar.tv.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.seasonvar.tv.data.SiteHttpException
import ru.seasonvar.tv.data.account.*
import java.io.IOException

enum class AccountFailure { CREDENTIALS, EXPIRED, NETWORK, HTTP, MARKUP, STORAGE }
data class AccountUiState(val busy: Boolean = true, val failure: AccountFailure? = null)

class AccountViewModel(private val repository: AccountRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(AccountUiState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    init { restore() }
    fun restore() = work { repository.restore() }
    fun login(login: String, password: String) = work { repository.login(login, password) }
    fun logout() = work { repository.logout() }
    private fun work(action: suspend () -> Unit) {
        job?.cancel()
        mutableState.value = AccountUiState()
        job = viewModelScope.launch {
            try { action(); mutableState.value = AccountUiState(busy = false) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutableState.value = AccountUiState(false, when (e) {
                    is LoginRejectedException -> AccountFailure.CREDENTIALS
                    is SessionExpiredException -> AccountFailure.EXPIRED
                    is SiteHttpException -> AccountFailure.HTTP
                    is IOException -> AccountFailure.NETWORK
                    is SessionStorageException -> AccountFailure.STORAGE
                    else -> AccountFailure.MARKUP
                })
            }
        }
    }
    class Factory(private val repository: AccountRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AccountViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return AccountViewModel(repository) as T
        }
    }
}
