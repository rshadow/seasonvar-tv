package ru.seasonvar.tv.ui.search

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import ru.seasonvar.tv.R
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.ShortMessageEffect
import ru.seasonvar.tv.ui.showShortMessage
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchControls(
    query: String,
    onQuery: (String) -> Unit,
    inputFocus: FocusRequester,
    menuFocus: FocusRequester,
    editRequest: Int,
    onSubmit: () -> Unit,
    onResults: () -> Unit,
    showHistory: Boolean = false,
    historyLoading: Boolean = false,
    historyFailed: Boolean = false,
    onCommit: () -> Unit = {},
) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val inputMode = LocalInputModeManager.current
    val scope = rememberCoroutineScope()
    val microphoneFocus = remember { FocusRequester() }
    var handledEditRequest by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val context = LocalContext.current
    ShortMessageEffect(if (historyFailed) stringResource(R.string.search_history_error) else null)
    val title = stringResource(R.string.search_title)
    val prompt = stringResource(R.string.search_voice_prompt)
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        if (result.resultCode == Activity.RESULT_OK) {
            if (text != null) { onQuery(text); onCommit() }
            else showShortMessage(context, R.string.search_voice_failed)
        }
        inputMode.requestInputMode(InputMode.Keyboard)
        microphoneFocus.requestFocus()
    }
    fun edit() {
        inputMode.requestInputMode(InputMode.Keyboard)
        inputFocus.requestFocus()
        scope.launch { withFrameNanos { }; keyboard?.show() }
    }
    LaunchedEffect(editRequest, historyLoading) {
        if (editRequest > 0 && editRequest != handledEditRequest && !historyLoading) {
            handledEditRequest = editRequest
            if (!showHistory) { withFrameNanos { }; edit() }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
            ThemeButton(
                stringResource(R.string.search_voice),
                onClick = {
                    keyboard?.hide()
                    try {
                        voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                        })
                    } catch (_: ActivityNotFoundException) {
                        showShortMessage(context, R.string.search_voice_unavailable)
                    } catch (_: SecurityException) {
                        showShortMessage(context, R.string.search_voice_unavailable)
                    }
                },
                modifier = Modifier.focusRequester(microphoneFocus)
                    .focusProperties { left = menuFocus; right = inputFocus }
                    .onPreviewKeyEvent {
                        if (it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown) {
                            onResults(); true
                        } else false
                    },
                icon = R.drawable.ic_mic,
            )
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f).height(d.navigationButtonHeight)
                    .focusRequester(inputFocus)
                    .focusProperties { left = microphoneFocus }
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent {
                        when {
                            it.key == Key.DirectionLeft && !imeVisible && it.type == KeyEventType.KeyDown -> {
                                microphoneFocus.requestFocus(); true
                            }
                            it.key == Key.DirectionCenter -> {
                                if (it.type == KeyEventType.KeyDown) edit()
                                true
                            }
                            it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown -> {
                                onResults(); true
                            }
                            else -> false
                        }
                    }
                    .semantics { contentDescription = title },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onBackground),
                cursorBrush = SolidColor(c.primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize()
                        .background(c.background, RoundedCornerShape(d.buttonCornerRadius))
                        .border(d.focusBorderWidth, if (focused) c.primary else c.onSurfaceVariant,
                            RoundedCornerShape(d.buttonCornerRadius))
                        .padding(horizontal = d.spacingSmall), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(stringResource(R.string.search_input_hint),
                            style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                        inner()
                    }
                },
            )
        }
    }
}
