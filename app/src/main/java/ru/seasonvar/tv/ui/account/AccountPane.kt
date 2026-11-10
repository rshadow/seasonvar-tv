package ru.seasonvar.tv.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.account.AccountSession
import ru.seasonvar.tv.data.account.AccountPremium
import ru.seasonvar.tv.ui.RemoteTextField
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
            RemoteTextField(login, { login = it }, R.string.account_login, false,
                loginFocus, passwordFocus, menuFocus, state.busy, onNext = {
                    passwordFocus.requestFocus(); scope.launch { withFrameNanos { }; keyboard?.show() }
                })
            RemoteTextField(password, { password = it }, R.string.account_password, true,
                passwordFocus, submitFocus, menuFocus, state.busy, previous = loginFocus, onNext = ::submit)
            ThemeButton(stringResource(if (state.busy) R.string.account_wait else R.string.account_sign_in), ::submit,
                Modifier.height(d.headerButtonSize).focusRequester(submitFocus).focusProperties { left = menuFocus; up = passwordFocus },
                loading = state.busy, loadingLabel = stringResource(R.string.account_wait))
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.spacingLarge)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
                    Text(user.name, style = MaterialTheme.typography.titleLarge, color = c.onBackground)
                    Text(stringResource(R.string.account_signed_in_hint), style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                    ThemeButton(stringResource(R.string.account_sign_out), onLogout,
                        Modifier.height(d.headerButtonSize).focusRequester(submitFocus).focusProperties { left = menuFocus },
                        loading = state.busy, loadingLabel = stringResource(R.string.account_wait))
                }
                val fallback = painterResource(R.drawable.ic_person)
                AsyncImage(
                    model = ImageRequest.Builder(context).data(user.avatarUrl)
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .memoryCacheKey("account-pane-avatar-${user.id}-${session.version}-${user.avatarUrl}").build(),
                    contentDescription = stringResource(R.string.account_avatar), contentScale = ContentScale.Crop,
                    placeholder = fallback, error = fallback, fallback = fallback,
                    modifier = Modifier.size(d.accountAvatarSize).clip(RoundedCornerShape(d.cardCornerRadius))
                        .background(c.surfaceVariant))
            }
            Column(Modifier.padding(top = d.spacingLarge), verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
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
            }
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
