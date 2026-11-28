package ru.seasonvar.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.*
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RemoteTextField(value: String, onValue: (String) -> Unit, label: Int, password: Boolean,
    focus: FocusRequester, next: FocusRequester, menu: FocusRequester, busy: Boolean,
    previous: FocusRequester = FocusRequester.Cancel,
    keyboardType: KeyboardType = if (password) KeyboardType.Password else KeyboardType.Email, onNext: () -> Unit) {
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
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType,
                imeAction = if (password) ImeAction.Done else ImeAction.Next, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onNext = { onNext() }, onDone = { onNext() }),
            decorationBox = { inner -> Box(Modifier.fillMaxSize()
                .background(c.background, RoundedCornerShape(d.buttonCornerRadius))
                .border(d.focusBorderWidth, if (focused) c.primary else c.onSurfaceVariant, RoundedCornerShape(d.buttonCornerRadius))
                .padding(d.spacingSmall), contentAlignment = androidx.compose.ui.Alignment.CenterStart) { inner() } })
    }
}
