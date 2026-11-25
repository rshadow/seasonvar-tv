package ru.seasonvar.tv.ui.details

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.comments.CommentPart
import ru.seasonvar.tv.data.comments.SeasonComment
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.showShortMessage
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommentEditorDialog(parent: SeasonComment?, draft: CommentDraft, busy: Boolean, failure: String?,
    onClose: () -> Unit, onSend: () -> Unit) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val micFocus = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }
    val sendFocus = remember { FocusRequester() }
    val fields = remember(draft.blocks.size) { List(draft.blocks.size) { FocusRequester() } }
    val activeField = fields.getOrElse(draft.active) { fields.first() }
    var focusBlock by remember { mutableIntStateOf(draft.active) }
    var focusRevision by remember { mutableIntStateOf(0) }
    var voiceBlock by remember { mutableIntStateOf(0) }
    val voiceTitle = stringResource(R.string.comment_voice)
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
            if (spoken == null) showShortMessage(context, R.string.comment_voice_failed)
            else draft.insertVoice(voiceBlock, spoken)
        } else if (result.resultCode != Activity.RESULT_CANCELED) {
            showShortMessage(context, R.string.comment_voice_failed)
        }
        scope.launch { withFrameNanos { }; micFocus.requestFocus() }
    }
    Dialog(onDismissRequest = { if (!busy) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        val ime = WindowInsets.isImeVisible
        val shape = RoundedCornerShape(d.cardCornerRadius)
        Column(Modifier.widthIn(max = d.dialogWidth).fillMaxWidth(0.9f).fillMaxHeight(0.82f)
            .background(c.background, shape).padding(d.spacingLarge),
            verticalArrangement = Arrangement.spacedBy(d.spacingMedium)) {
            Text(if (parent == null) stringResource(R.string.comment_new_title) else stringResource(R.string.comment_reply_title,
                parent.author ?: stringResource(R.string.reviews_author_unknown)),
                style = MaterialTheme.typography.headlineSmall, color = c.onBackground)
            parent?.parts?.filterIsInstance<CommentPart.Text>()?.firstOrNull()?.value?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                draft.blocks.forEachIndexed { index, block ->
                    val label = stringResource(if (block.spoiler) R.string.comment_spoiler_text else R.string.comment_text)
                    var focused by remember(index) { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth()
                        .background(if (block.spoiler) c.surfaceVariant else c.surface, shape)
                        .border(d.focusBorderWidth, if (focused) c.primary else c.onSurfaceVariant, shape)
                        .padding(d.spacingSmall)) {
                        if (block.spoiler) Text(stringResource(R.string.comment_spoiler),
                            color = c.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                        BasicTextField(block.value, { draft.edit(index, it) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = d.headerButtonSize)
                                .focusRequester(fields[index]).onFocusChanged {
                                    focused = it.isFocused
                                    if (focused) draft.active = index
                                }.focusProperties {
                                    up = fields.getOrNull(index - 1) ?: FocusRequester.Cancel
                                    down = fields.getOrNull(index + 1) ?: micFocus
                                    right = micFocus
                                }.onPreviewKeyEvent {
                                    if (it.type != KeyEventType.KeyDown || busy) false else when (it.key) {
                                        Key.DirectionCenter -> { keyboard?.show(); true }
                                        Key.DirectionRight -> !ime && micFocus.requestFocus()
                                        Key.DirectionUp -> !ime && (fields.getOrNull(index - 1)?.requestFocus() ?: true)
                                        Key.DirectionDown -> !ime && (fields.getOrNull(index + 1) ?: micFocus).requestFocus()
                                        else -> false
                                    }
                                }.semantics { contentDescription = label },
                            readOnly = busy, minLines = if (draft.blocks.size == 1) 4 else 1,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onBackground),
                            cursorBrush = SolidColor(c.primary),
                            decorationBox = { inner ->
                                Box {
                                    if (block.value.text.isEmpty()) Text(label, color = c.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyLarge)
                                    inner()
                                }
                            })
                    }
                }
            }
            failure?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                ThemeButton(voiceTitle, {
                    keyboard?.hide()
                    voiceBlock = draft.active
                    try {
                        val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                            putExtra(RecognizerIntent.EXTRA_PROMPT, voiceTitle)
                        }
                        val manager = context.packageManager
                        val default = request.resolveActivity(manager)
                        if (default?.packageName == "com.uapplication.search") {
                            val googleTv = Intent(request).setPackage("com.google.android.katniss")
                            if (googleTv.resolveActivity(manager) != null) request.setPackage(googleTv.`package`)
                        }
                        voice.launch(request)
                    } catch (_: ActivityNotFoundException) {
                        showShortMessage(context, R.string.comment_voice_unavailable)
                    } catch (_: SecurityException) {
                        showShortMessage(context, R.string.comment_voice_unavailable)
                    }
                }, Modifier.weight(1f).focusRequester(micFocus).focusProperties { right = addFocus; up = activeField },
                    showOutline = false, actionEnabled = !busy)
                val spoilerActive = draft.blocks.getOrNull(draft.active)?.spoiler == true
                ThemeButton(stringResource(if (spoilerActive) R.string.comment_remove_spoiler else R.string.comment_add_spoiler), {
                    focusBlock = if (spoilerActive) draft.removeSpoiler() else draft.insertSpoiler()
                    focusRevision++
                }, Modifier.weight(1f).focusRequester(addFocus).focusProperties {
                    left = micFocus; right = sendFocus; up = activeField
                }, showOutline = false, actionEnabled = !busy)
                ThemeButton(stringResource(R.string.comment_send), onSend,
                    Modifier.weight(1f).focusRequester(sendFocus).focusProperties { left = addFocus; up = activeField },
                    loading = busy, loadingLabel = stringResource(R.string.comment_sending),
                    actionEnabled = draft.text().isNotEmpty())
            }
        }
        val requestedFocus = fields.getOrNull(focusBlock)
        LaunchedEffect(requestedFocus, focusRevision) {
            withFrameNanos { }
            requestedFocus?.requestFocus()
        }
    }
}
