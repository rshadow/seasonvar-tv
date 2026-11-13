package ru.seasonvar.tv.ui.actor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.launch
import ru.seasonvar.tv.SeasonvarApplication
import ru.seasonvar.tv.data.ActorPages
import ru.seasonvar.tv.data.SeriesActor
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.playback.PlaybackRequest
import ru.seasonvar.tv.ui.CatalogPane
import ru.seasonvar.tv.ui.account.MarkRemovalDialog
import ru.seasonvar.tv.ui.account.MarkRemovalViewModel
import ru.seasonvar.tv.ui.catalog.CatalogLayout
import ru.seasonvar.tv.ui.catalog.parsedCatalogState
import ru.seasonvar.tv.ui.catalog.rememberCatalogCardMetrics
import ru.seasonvar.tv.ui.details.SeriesDetailsPane
import ru.seasonvar.tv.ui.details.SeriesDetailsViewModel
import ru.seasonvar.tv.ui.navigation.backNavigationKeys
import ru.seasonvar.tv.ui.player.PlayerActivity
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

class ActorSeriesActivity : ComponentActivity() {
    private lateinit var details: SeriesDetailsViewModel
    private val playback = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.let {
            details.onPlaybackFinished(it.getStringExtra("season"), it.getStringExtra("translation"), it.getStringExtra("episode"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("actor_name")?.takeIf { it.isNotBlank() }
        val url = intent.getStringExtra("actor_url")?.let(ActorPages::url)
        if (name == null || url == null) { finish(); return }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val container = application as SeasonvarApplication
        val model = ViewModelProvider(this, ActorSeriesViewModel.Factory(container.repository, url))[ActorSeriesViewModel::class.java]
        details = ViewModelProvider(this, SeriesDetailsViewModel.Factory(container.repository, container.playbackProgress,
            container.account, { container.interfaceSettings.preferredTranslations.value }))[SeriesDetailsViewModel::class.java]
        val actions = ViewModelProvider(this, MarkRemovalViewModel.Factory(container.account, container.accountMarks,
            onRemoved = { details.markRemoved(it); model.refresh() },
            onChanged = { entry, _ -> details.markChanged(entry); model.refresh() }))[MarkRemovalViewModel::class.java]
        setContent {
            SeasonvarTheme {
                ActorSeriesScreen(name, url, container, model, details, actions,
                    onPlay = { playback.launch(PlayerActivity.intent(this, it)) }, onExit = ::finish)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::details.isInitialized) details.refreshResume()
    }

    companion object {
        fun open(context: Context, actor: SeriesActor) {
            val url = actor.url?.let(ActorPages::url) ?: return
            context.startActivity(Intent(context, ActorSeriesActivity::class.java)
                .putExtra("actor_name", actor.name).putExtra("actor_url", url))
        }
    }
}

@Composable
private fun ActorSeriesScreen(name: String, url: String, container: SeasonvarApplication,
    model: ActorSeriesViewModel, details: SeriesDetailsViewModel, actions: MarkRemovalViewModel,
    onPlay: (PlaybackRequest) -> Unit, onExit: () -> Unit) {
    val base by model.state.collectAsStateWithLifecycle()
    val session by container.account.session.collectAsStateWithLifecycle()
    val network by container.networkSettings.configuration.collectAsStateWithLifecycle()
    val marks by container.account.marksRevision.collectAsStateWithLifecycle()
    val prefetch by container.interfaceSettings.imagePrefetchEnabled.collectAsStateWithLifecycle()
    val showActors by container.interfaceSettings.showActors.collectAsStateWithLifecycle()
    val removal by actions.state.collectAsStateWithLifecycle()
    var previousAccount by remember { mutableLongStateOf(session.version) }
    var selectedKey by rememberSaveable(url) { mutableStateOf<String?>(null) }
    val state = parsedCatalogState(base, selectedKey, url to session.version)
    val selected = state.entries.firstOrNull { it.key == selectedKey } ?: state.entries.firstOrNull()
    var entry by remember { mutableStateOf<SeriesEntry?>(null) }
    val list = rememberLazyListState()
    val grid = rememberLazyGridState()
    var layout by rememberSaveable { mutableStateOf(CatalogLayout.STRIP) }
    val cards = remember { mutableMapOf<String, FocusRequester>() }
    val header = remember { FocusRequester() }
    val refresh = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val holder = remember { FocusRequester() }
    var holdingFocus by remember { mutableStateOf(true) }
    LaunchedEffect(session.version, network, marks) {
        val accountChanged = previousAccount != session.version
        if (accountChanged) { entry = null; selectedKey = null; holdingFocus = true; actions.dismiss() }
        model.refresh(clear = accountChanged)
        previousAccount = session.version
    }
    val scope = rememberCoroutineScope()
    val latestState by rememberUpdatedState(state)
    val latestSelected by rememberUpdatedState(selected)
    val latestLayout by rememberUpdatedState(layout)
    val catalogKey = "ACTOR|$url"
    fun focusList() {
        scope.launch {
            repeat(2) { withFrameNanos { } }
            if (entry != null) return@launch
            val chosen = latestSelected
            if (chosen == null) {
                (if (latestState.failure != null) retry else refresh).requestFocus()
            } else {
                val index = latestState.entries.indexOfFirst { it.key == chosen.key }.coerceAtLeast(0)
                val key = "$catalogKey|${chosen.key}"
                val requestedLayout = latestLayout
                if (requestedLayout == CatalogLayout.GRID) {
                    if (grid.layoutInfo.visibleItemsInfo.none { it.key == key }) grid.scrollToItem(index)
                } else if (list.layoutInfo.visibleItemsInfo.none { it.key == key }) list.scrollToItem(index)
                repeat(2) { withFrameNanos { } }
                if (entry == null && latestLayout == requestedLayout && latestSelected?.key == chosen.key) {
                    cards[key]?.requestFocus()
                }
            }
            holdingFocus = false
        }
    }
    fun back() {
        if (entry == null) onExit() else {
            holdingFocus = true
            scope.launch {
                withFrameNanos { }
                holder.requestFocus()
                entry = null
                focusList()
            }
        }
    }
    BackHandler { back() }
    LaunchedEffect(Unit) { holder.requestFocus() }
    LaunchedEffect(state.loaded, state.failure) {
        if (holdingFocus && entry == null && (state.loaded || state.failure != null)) focusList()
    }
    var actionsWereOpen by remember { mutableStateOf(false) }
    LaunchedEffect(removal.visible) {
        if (actionsWereOpen && !removal.visible && entry == null) focusList()
        actionsWereOpen = removal.visible
    }
    val d = SeasonvarTheme.dimensions
    BoxWithConstraints(Modifier.fillMaxSize().backNavigationKeys(::back, {})
        .background(MaterialTheme.colorScheme.background).padding(d.screenPaddingHorizontal, d.screenPaddingVertical)
        .focusRequester(holder).focusProperties { canFocus = holdingFocus }.focusTarget().focusGroup()) {
        val metrics = rememberCatalogCardMetrics(maxWidth)
        val detailEntry = entry
        if (detailEntry != null) SeriesDetailsPane(detailEntry, details, FocusRequester.Cancel, {}, onPlay,
            onActions = if (session.user == null) null else ({ actions.show(it) }), removalDialogOpen = removal.visible,
            showActors = showActors)
        else CatalogPane(state, selected, list, cards, null, FocusRequester.Cancel, refresh, retry,
            onRefresh = { model.refresh() }, onFocused = { selectedKey = it.key },
            onOpen = { selectedKey = it.key; entry = it }, actorName = name, catalogKey = catalogKey,
            cardMetrics = metrics, imagePrefetchEnabled = prefetch, gridState = grid, layout = layout,
            layoutFocus = header, onLayoutChange = { layout = layout.other() }, onEnterContent = ::focusList,
            allowAccountActions = session.user != null, onActions = { actions.show(it) },
            loadDescription = container.repository::description, cardData = container.repository.cardData,
            preparationContext = session.version to network)
    }
    MarkRemovalDialog(removal, onDismiss = actions::dismiss, onAsk = actions::ask, onConfirm = actions::confirm,
        onInteraction = {}, onRefresh = actions::refreshStatus, onSelectStatus = actions::selectStatus)
}
