package ru.seasonvar.tv.probe

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.flow.MutableStateFlow
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.*
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
    val dns = mutableStateOf(DnsProvider.QUAD9_DOH)
    val toggles = mutableStateMapOf(R.string.show_actors_title to true, R.string.auto_next_setting_title to true,
        R.string.auto_next_season_setting_title to true, R.string.image_prefetch_title to false, R.string.bypass_title to true)
    val translations = TranslationSettingsViewModel(preferred, { preferred.value = it }, { throw IOException() })
    val update = UpdateViewModel("1.0.0", { GitHubRelease("99.0.0", ReleaseAsset("test.apk",
        "https://github.com/rshadow/seasonvar-tv/releases/download/v99.0.0/test.apk", 1, "0".repeat(64))) },
        { _, _ -> error("Download is outside this probe") })
    private val details = SeriesDetails(entry, "100", listOf("Синтетические данные для проверки интерфейса."), emptyList(), emptyList(),
        listOf(SeasonOption("123", entry.url, "Сезон 1", 1), SeasonOption("124", "https://seasonvar.ru/serial-124-example.html", "Сезон 2", 2)),
        listOf(Translation("0", "Оригинал", (1..30).map { Episode(it.toString(), it.toString()) }),
            Translation("1", "Другая озвучка", listOf(Episode("1", "1")))), "0")
    val detailsModel = SeriesDetailsViewModel { details }
    val actorModel = SeriesDetailsViewModel { details.copy(
        actors = listOf(SeriesActor("Первый актёр", "android.resource://ru.seasonvar.tv/drawable/actor_placeholder"),
            SeriesActor("Второй актёр", "android.resource://ru.seasonvar.tv/drawable/actor_placeholder")),
        paragraphs = List(12) { "Синтетическое описание для проверки прокрутки. ".repeat(20) }) }
    val filterController = UiProbeFilters()

    fun attach() { store.put("details", detailsModel); store.put("actors", actorModel); store.put("translations", translations); store.put("update", update) }
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
