package ru.seasonvar.tv.probe

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.flow.MutableStateFlow
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.*
import ru.seasonvar.tv.data.comments.CommentPart
import ru.seasonvar.tv.data.comments.CommentsPage
import ru.seasonvar.tv.data.comments.SeasonComment
import ru.seasonvar.tv.data.network.DnsProvider
import ru.seasonvar.tv.data.update.*
import ru.seasonvar.tv.ui.catalog.CatalogUiState
import ru.seasonvar.tv.ui.details.SeriesDetailsViewModel
import ru.seasonvar.tv.ui.filters.*
import ru.seasonvar.tv.ui.settings.TranslationSettingsViewModel
import ru.seasonvar.tv.ui.update.UpdateViewModel
import java.io.IOException

/** Синтетические ответы; attach и close принадлежат главному потоку. */
internal class UiScreensFixtures : AutoCloseable {
    private val store = ViewModelStore()
    val user = AccountUser("12", "Тестовый пользователь", null)
    val account = AccountSession(user, 1)
    val entry = SeriesEntry("123", "https://seasonvar.ru/serial-123-example.html", "Проверка интерфейса", 1, "", "")
    val catalog = CatalogUiState(loading = false, loaded = true, entries = listOf(entry))
    private val preferred = MutableStateFlow(listOf("AniDub", "LostFilm"))
    val playbackSpeed = mutableStateOf(ru.seasonvar.tv.data.playback.PlaybackSpeed.NORMAL)
    val fontSize = mutableStateOf(ru.seasonvar.tv.ui.settings.InterfaceFontSize.NORMAL)
    val dns = mutableStateOf(DnsProvider.QUAD9_DOH)
    val toggles = mutableStateMapOf(R.string.show_actors_title to true, R.string.comments_setting_title to true,
        R.string.auto_next_setting_title to true,
        R.string.auto_next_season_setting_title to true, R.string.image_prefetch_title to false,
        R.string.list_prefetch_title to false, R.string.bypass_title to true, R.string.show_player_controls_title to true)
    val translations = TranslationSettingsViewModel(preferred, { preferred.value = it }, { throw IOException() })
    val update = UpdateViewModel("1.0.0", { GitHubRelease("99.0.0", ReleaseAsset("test.apk",
        "https://github.com/rshadow/seasonvar-tv/releases/download/v99.0.0/test.apk", 1, "0".repeat(64))) },
        { _, _ -> error("Download is outside this probe") })
    private val details = SeriesDetails(entry, "100", listOf("Синтетические данные для проверки интерфейса."), emptyList(), emptyList(),
        listOf(SeasonOption("123", entry.url, "Сезон 1", 1), SeasonOption("124", "https://seasonvar.ru/serial-124-example.html", "Сезон 2", 2)),
        listOf(Translation("0", "Оригинал", (1..30).map { Episode(it.toString(), it.toString()) }),
            Translation("1", "Другая озвучка", listOf(Episode("1", "1")))), "0")
    val detailsModel = SeriesDetailsViewModel { details }
    val commentPages = java.util.concurrent.CopyOnWriteArrayList<Int>()
    val sentComments = java.util.concurrent.CopyOnWriteArrayList<Pair<String?, String>>()
    private val commentsPage: suspend (SeriesEntry, Int) -> CommentsPage = { _, page ->
        commentPages += page
        if (page == 1) CommentsPage(
            listOf(
                SeasonComment("1", false, "Первый зритель", "Сегодня", listOf(CommentPart.Text(
                    "Посмотрел первые две серии. Во второй стало интереснее, теперь хочется узнать, чем закончится сезон."
                ))),
                SeasonComment("2", true, "Автор ответа", null, listOf(CommentPart.Spoiler(listOf(CommentPart.Text("Скрытый ответ."))))),
                SeasonComment("3", true, "Второй ответ", "Вчера", listOf(CommentPart.Text("Ответ в той же ветке."))),
            ) + (4..12).map { id ->
                SeasonComment(id.toString(), false, "Зритель $id", null, listOf(CommentPart.Text("Комментарий $id.")))
            }, next = 2,
        ) else {
            check(page == 2)
            CommentsPage(listOf(SeasonComment("13", false, "Последний зритель", null, listOf(CommentPart.Text("Вторая страница.")))))
        }
    }
    private val commentDetails = details.copy(seasons = details.seasons.take(1), translations = details.translations.takeLast(1),
        defaultTranslationId = "1")
    val commentsModel = SeriesDetailsViewModel(loadComments = commentsPage) { commentDetails }
    val commentsWriterModel = SeriesDetailsViewModel(loadComments = commentsPage, commentAccount = MutableStateFlow(account),
        publishComment = { target, version, parent, body ->
            check(target.id == entry.id && version == account.version)
            sentComments += parent to body
        }, refreshComments = { target, page ->
            val existing = commentsPage(target, page)
            existing.copy(entries = existing.entries + SeasonComment("101", true, user.name, null,
                listOf(CommentPart.Text("Новый ответ в обсуждении."))))
        }) { commentDetails }
    val actorModel = SeriesDetailsViewModel { details.copy(
        actors = listOf(SeriesActor("Первый актёр", "android.resource://ru.seasonvar.tv/drawable/actor_placeholder"),
            SeriesActor("Второй актёр", "android.resource://ru.seasonvar.tv/drawable/actor_placeholder")),
        paragraphs = List(12) { "Синтетическое описание для проверки прокрутки. ".repeat(20) }) }
    val filterController = UiProbeFilters()

    fun attach() {
        store.put("details", detailsModel); store.put("actors", actorModel); store.put("comments", commentsModel)
        store.put("commentsWriter", commentsWriterModel)
        store.put("translations", translations); store.put("update", update)
    }
    override fun close() { store.clear() }
}

internal class UiProbeFilters : ListFiltersController {
    private val fields = listOf(ListFilterField("genre", R.string.filter_genre,
        listOf(FilterOption("all", "Все жанры"), FilterOption("comedy", "Комедия"))))
    override val state = MutableStateFlow(ListFiltersState(fields = fields, values = mapOf("genre" to "all"), draft = mapOf("genre" to "all")))
    override fun open() { state.value = state.value.copy(open = true) }
    override fun dismiss() { state.value = state.value.copy(open = false) }
    override fun choose(field: String, value: String) { state.value = state.value.copy(draft = state.value.draft + (field to value)) }
    override fun reset() { state.value = state.value.copy(values = mapOf("genre" to "all"), draft = mapOf("genre" to "all")) }
    override fun apply() { state.value = state.value.copy(values = state.value.draft) }
}
