package ru.seasonvar.tv.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.*
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.account.AccountSession
import ru.seasonvar.tv.data.account.AccountPremium
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.ShortMessageEffect
import ru.seasonvar.tv.ui.showShortMessage
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
internal fun AccountPane(session: AccountSession, state: AccountUiState, menuFocus: FocusRequester,
    onLogin: (String, String) -> Unit, onLogout: () -> Unit, onCheck: () -> Unit) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val loginFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val submitFocus = remember { FocusRequester() }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val context = LocalContext.current
    fun submit() {
        if (state.busy) return
        if (login.isBlank() || password.isEmpty()) { showShortMessage(context, R.string.account_required); return }
        val submitted = password
        password = ""
        keyboard?.hide()
        submitFocus.requestFocus()
        onLogin(login, submitted)
    }
    LaunchedEffect(session.version) { login = ""; password = "" }
    LaunchedEffect(session.user?.id, state.busy) {
        if (!state.busy) { repeat(2) { withFrameNanos { } }; if (session.user == null) loginFocus.requestFocus() else submitFocus.requestFocus() }
    }
    DisposableEffect(Unit) { onDispose { password = ""; login = "" } }
    LaunchedEffect(Unit) { if (session.user != null && !state.busy) onCheck() }
    Column(Modifier.fillMaxWidth().widthIn(max = d.dialogWidth), verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
        Text(stringResource(R.string.account_title), style = MaterialTheme.typography.headlineSmall, color = c.onBackground)
        val user = session.user
        if (user == null) {
            Text(stringResource(R.string.account_login_hint), style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
            AccountField(login, { login = it }, R.string.account_login, false,
                loginFocus, passwordFocus, menuFocus, state.busy, onNext = {
                    passwordFocus.requestFocus(); scope.launch { withFrameNanos { }; keyboard?.show() }
                })
            AccountField(password, { password = it }, R.string.account_password, true,
                passwordFocus, submitFocus, menuFocus, state.busy, previous = loginFocus, onNext = ::submit)
            ThemeButton(stringResource(if (state.busy) R.string.account_wait else R.string.account_sign_in), ::submit,
                Modifier.height(d.headerButtonSize).focusRequester(submitFocus).focusProperties { left = menuFocus; up = passwordFocus },
                loading = state.busy, loadingLabel = stringResource(R.string.account_wait))
        } else {
            Text(user.name, style = MaterialTheme.typography.titleLarge, color = c.onBackground)
            Text(stringResource(R.string.account_signed_in_hint), style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
            val premiumText = when (val premium = user.premium) {
                is AccountPremium.Until -> stringResource(R.string.account_premium_until,
                    premium.date.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.uuuu", java.util.Locale.ROOT)))
                AccountPremium.Inactive -> stringResource(R.string.account_premium_inactive)
                AccountPremium.Unknown -> stringResource(R.string.account_premium_unknown)
            }
            Text(premiumText, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
            Text(if (user.premium is AccountPremium.Until) stringResource(R.string.account_premium_hd_hint) else "",
                style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant,
                minLines = 1, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            ThemeButton(stringResource(R.string.account_sign_out), onLogout,
                Modifier.height(d.headerButtonSize).focusRequester(submitFocus).focusProperties { left = menuFocus },
                loading = state.busy, loadingLabel = stringResource(R.string.account_wait))
        }
        val error = when {
            state.failure == AccountFailure.CREDENTIALS -> R.string.account_bad_credentials
            state.failure == AccountFailure.EXPIRED || session.expired -> R.string.account_expired
            state.failure == AccountFailure.NETWORK -> R.string.account_network_error
            state.failure == AccountFailure.HTTP -> R.string.account_http_error
            state.failure == AccountFailure.STORAGE -> R.string.account_storage_error
            state.failure != null -> R.string.account_markup_error
            else -> null
        }
        ShortMessageEffect(error?.let { stringResource(it) })
        if (state.failure in listOf(AccountFailure.NETWORK, AccountFailure.HTTP, AccountFailure.MARKUP, AccountFailure.STORAGE)) {
            ThemeButton(stringResource(R.string.account_check), onCheck,
                Modifier.height(d.headerButtonSize).focusProperties { left = menuFocus }, loading = state.busy)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountField(value: String, onValue: (String) -> Unit, label: Int, password: Boolean,
    focus: FocusRequester, next: FocusRequester, menu: FocusRequester, busy: Boolean,
    previous: FocusRequester = FocusRequester.Cancel, onNext: () -> Unit) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val title = stringResource(label)
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val ime = WindowInsets.isImeVisible
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        BasicTextField(value, onValue, enabled = !busy, singleLine = true,
            modifier = Modifier.fillMaxWidth().height(d.navigationButtonHeight).focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .focusProperties { left = menu; down = next; up = previous }
                .onPreviewKeyEvent {
                    when {
                        it.key == Key.DirectionCenter -> {
                            if (it.type == KeyEventType.KeyDown) {
                                focus.requestFocus(); scope.launch { withFrameNanos { }; keyboard?.show() }
                            }; true
                        }
                        !ime && it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown -> { next.requestFocus(); true }
                        !ime && it.key == Key.DirectionUp && it.type == KeyEventType.KeyDown -> {
                            if (previous != FocusRequester.Cancel) previous.requestFocus()
                            true
                        }
                        !ime && it.key == Key.DirectionLeft && it.type == KeyEventType.KeyDown -> { menu.requestFocus(); true }
                        else -> false
                    }
                }.semantics { contentDescription = title },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onBackground), cursorBrush = SolidColor(c.primary),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Email,
                imeAction = if (password) ImeAction.Done else ImeAction.Next, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onNext = { onNext() }, onDone = { onNext() }),
            decorationBox = { inner -> Box(Modifier.fillMaxSize()
                .background(c.background, RoundedCornerShape(d.buttonCornerRadius))
                .border(d.focusBorderWidth, if (focused) c.primary else c.onSurfaceVariant, RoundedCornerShape(d.buttonCornerRadius))
                .padding(d.spacingSmall), contentAlignment = androidx.compose.ui.Alignment.CenterStart) { inner() } })
    }
}
