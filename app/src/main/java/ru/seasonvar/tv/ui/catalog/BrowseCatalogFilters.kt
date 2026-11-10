package ru.seasonvar.tv.ui.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.CatalogFilters
import ru.seasonvar.tv.data.account.FilterOption
import ru.seasonvar.tv.ui.ThemeButton
import ru.seasonvar.tv.ui.filters.FilterRow
import ru.seasonvar.tv.ui.navigation.backNavigationKeys
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

private data class CatalogCondition(val id: String, val title: Int, val fields: List<Pair<String, Int>>)

private val conditions = listOf(
    CatalogCondition("country", R.string.filter_country, listOf("country" to R.string.filter_country,
        "countryNot" to R.string.catalog_country_exclude, "origin" to R.string.catalog_origin)),
    CatalogCondition("genres", R.string.filter_genre, emptyList()),
    CatalogCondition("year", R.string.catalog_year, listOf("year" to R.string.catalog_year, "yearOp" to R.string.catalog_comparison)),
    CatalogCondition("seasons", R.string.catalog_seasons, listOf("seasons" to R.string.catalog_seasons, "seasonsOp" to R.string.catalog_comparison)),
    CatalogCondition("rating", R.string.catalog_rating, listOf("ratingSource" to R.string.catalog_rating_source,
        "rating" to R.string.catalog_rating_group, "ratingOp" to R.string.catalog_comparison)),
    CatalogCondition("voice", R.string.catalog_voice, listOf("voice" to R.string.catalog_voice)),
    CatalogCondition("quality", R.string.catalog_quality, listOf("fhd" to R.string.best_filter_fhd,
        "hd" to R.string.best_filter_hd, "sub" to R.string.best_filter_subtitles)),
    CatalogCondition("personal", R.string.catalog_personal, listOf("mark" to R.string.best_filter_watching,
        "history" to R.string.best_filter_history, "nw" to R.string.best_filter_blacklist)),
    CatalogCondition("sort", R.string.catalog_sort, listOf("sort" to R.string.catalog_sort)),
)

private fun optionsSummary(state: BrowseCatalogState, key: String, values: Set<String>) =
    state.options?.choices?.get(key).orEmpty().filter { it.value in values }.joinToString(" · ") { it.label }

@Composable
internal fun BrowseCatalogFilters(state: BrowseCatalogState, controller: BrowseCatalogController,
    signedIn: Boolean, applyFocus: FocusRequester, firstFocus: FocusRequester, headerFocus: FocusRequester,
    menuFocus: FocusRequester, onResults: () -> Unit) {
    val d = SeasonvarTheme.dimensions
    var group by remember { mutableStateOf<CatalogCondition?>(null) }
    val focuses = remember(firstFocus) { conditions.associate { it.id to if (it.id == "country") firstFocus else FocusRequester() } }
    val resetFocus = remember { FocusRequester() }
    val rows = listOf(conditions.take(5), conditions.drop(5))
    fun summary(condition: CatalogCondition): String {
        val filters = state.draft
        fun label(key: String) = state.options?.choices?.get(key)?.find { it.value == filters[key] }?.label.orEmpty()
        return when (condition.id) {
            "genres" -> optionsSummary(state, "genres", filters.genres("genres")) +
                optionsSummary(state, "genres", filters.genres("excludedGenres")).takeIf { it.isNotEmpty() }?.let { " ≠ $it" }.orEmpty()
            "quality", "personal" -> condition.fields.count { filters[it.first] == "true" }.takeIf { it > 0 }?.toString().orEmpty()
            "country" -> if (filters["country"] != "all") (if (filters["countryNot"] == "true") "≠ " else "") + label("country")
                else if (filters["origin"].isNotEmpty()) label("origin") else ""
            "year", "seasons", "rating" -> if (filters[condition.id] == "all") "" else filters[condition.id + "Op"] + label(condition.id)
            else -> label(condition.id).takeIf { filters[condition.id] != "all" }.orEmpty()
        }
    }
    Column(Modifier.fillMaxWidth().testTag("catalog_inline_filters"), verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
        rows.forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                if (rowIndex == 1) ThemeButton(stringResource(R.string.filters_apply), controller::apply,
                    Modifier.weight(1f).height(d.headerButtonSize).focusRequester(applyFocus)
                        .focusProperties { left = menuFocus; right = focuses.getValue(row.first().id); up = focuses.getValue("country") }
                        .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) { onResults(); true } else false },
                    actionEnabled = state.options != null && !state.page.loading && state.changed,
                    contentPadding = PaddingValues(d.spacingSmall))
                row.forEachIndexed { index, condition ->
                    val value = summary(condition)
                    ThemeButton(if (condition.id == "sort" && value.isNotEmpty()) value else stringResource(condition.title) + if (value.isEmpty()) "" else ": $value",
                        { group = condition },
                        Modifier.weight(if (condition.id == "sort") 1.5f else 1f).height(d.headerButtonSize).focusRequester(focuses.getValue(condition.id))
                            .focusProperties {
                                left = row.getOrNull(index - 1)?.let { focuses.getValue(it.id) } ?: if (rowIndex == 1) applyFocus else menuFocus
                                right = row.getOrNull(index + 1)?.let { focuses.getValue(it.id) } ?: if (rowIndex == 1) resetFocus else FocusRequester.Cancel
                                if (rowIndex == 0) {
                                    up = headerFocus
                                    down = if (index == 0) applyFocus else focuses.getValue(rows[1][(index - 1).coerceAtMost(3)].id)
                                }
                                else up = focuses.getValue(rows[0][index + 1].id)
                            }.onPreviewKeyEvent {
                                if (rowIndex == 1 && it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) { onResults(); true } else false
                            }.testTag("catalog_condition_" + condition.id),
                        actionEnabled = state.options != null && !state.page.loading && (condition.id != "personal" || signedIn),
                        contentPadding = PaddingValues(d.spacingSmall))
                }
                if (rowIndex == 1) ThemeButton(stringResource(R.string.filters_reset), controller::reset,
                    Modifier.weight(1f).height(d.headerButtonSize).focusRequester(resetFocus)
                        .focusProperties { left = focuses.getValue("sort"); right = FocusRequester.Cancel; up = focuses.getValue("rating") }
                        .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) { onResults(); true } else false },
                    actionEnabled = !state.page.loading, contentPadding = PaddingValues(d.spacingSmall))
            }
        }
    }
    group?.let { condition ->
        CatalogConditionDialog(condition, state, controller) { group = null }
    }
}

@Composable
private fun CatalogConditionDialog(condition: CatalogCondition, state: BrowseCatalogState,
    controller: BrowseCatalogController, onClose: () -> Unit) {
    val d = SeasonvarTheme.dimensions
    val options = state.options ?: return
    var choosing by remember(condition.id) { mutableStateOf<String?>(null) }
    var returnField by remember { mutableStateOf<String?>(null) }
    val genres = condition.id == "genres"
    val fields = if (genres) options.choices.getValue("genres").filter { it.value != "all" }.map { it.value to it.label }
        else condition.fields.map { it.first to stringResource(it.second) }
    val direct = condition.fields.size == 1
    val selectedKey = if (direct) condition.fields.first().first else choosing
    val include = stringResource(R.string.catalog_genre_include)
    val exclude = stringResource(R.string.catalog_genre_exclude)
    val ignore = stringResource(R.string.catalog_genre_ignore)
    val choices = if (selectedKey == null) emptyList() else if (genres) listOf(FilterOption("all", ignore), FilterOption("include", include), FilterOption("exclude", exclude))
        else options.choices[selectedKey].orEmpty()
    val selectedValue = if (selectedKey == null) "" else if (genres) when (selectedKey) {
        in state.draft.genres("genres") -> "include"
        in state.draft.genres("excludedGenres") -> "exclude"
        else -> "all"
    } else state.draft[selectedKey]
    val closeFocus = remember { FocusRequester() }
    fun back() { if (!direct && choosing != null) { returnField = choosing; choosing = null } else onClose() }
    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = ::back)
        Column(Modifier.width(d.dialogWidth).height(d.dialogHeight)
            .backNavigationKeys(::back, {})
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(d.cardCornerRadius)).padding(d.spacingLarge),
            verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(condition.title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground)
                ThemeButton(stringResource(R.string.filters_back), ::back, Modifier.focusRequester(closeFocus).height(d.headerButtonSize))
            }
            if (condition.id == "rating") Text(stringResource(R.string.catalog_rating_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            key(selectedKey) {
                val rows = if (selectedKey != null) choices.map { it.value to it.label } else fields
                val initial = rows.indexOfFirst { it.first == if (selectedKey != null) selectedValue else returnField }.coerceAtLeast(0)
                val list = rememberLazyListState(initialFirstVisibleItemIndex = initial)
                val focuses = remember(rows) { rows.associate { it.first to FocusRequester() } }
                LazyColumn(Modifier.fillMaxWidth().weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
                    itemsIndexed(rows, key = { _, row -> row.first }) { index, (key, label) ->
                        val toggle = selectedKey == null && key in CatalogFilters.toggles
                        val value = if (genres) when (key) {
                            in state.draft.genres("genres") -> include
                            in state.draft.genres("excludedGenres") -> exclude
                            else -> ignore
                        } else options.choices[key]?.find { it.value == state.draft[key] }?.label.orEmpty()
                        FilterRow(label, value, Modifier.focusRequester(focuses.getValue(key)).focusProperties {
                            if (index == 0) up = closeFocus
                            if (index == rows.lastIndex) down = FocusRequester.Cancel
                        }, selected = if (selectedKey != null) key == selectedValue else null,
                            checkboxChecked = if (toggle) state.draft[key] == "true" else null) {
                            if (selectedKey != null) {
                                if (genres) {
                                    for ((name, mode) in listOf("genres" to "include", "excludedGenres" to "exclude")) {
                                        val set = state.draft.genres(name) - selectedKey
                                        controller.choose(name, (if (key == mode) set + selectedKey else set).sorted().joinToString(","))
                                    }
                                } else controller.choose(selectedKey, key)
                                if (direct) onClose() else { returnField = selectedKey; choosing = null }
                            } else if (toggle) controller.choose(key, (state.draft[key] != "true").toString())
                            else choosing = key
                        }
                    }
                }
                LaunchedEffect(selectedKey) {
                    repeat(2) { withFrameNanos { } }
                    rows.getOrNull(initial)?.let { focuses.getValue(it.first).requestFocus() } ?: closeFocus.requestFocus()
                }
            }
        }
    }
}
