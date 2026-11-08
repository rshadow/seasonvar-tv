package ru.seasonvar.tv.ui.filters

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import ru.seasonvar.tv.data.account.FilterOption
import ru.seasonvar.tv.ui.catalog.LoadFailure

enum class ListFilterKind { SINGLE, TOGGLE }
data class ListFilterField(val id: String, @StringRes val title: Int, val options: List<FilterOption> = emptyList(),
    val defaultValue: String = "all", val kind: ListFilterKind = ListFilterKind.SINGLE, @StringRes val activeTitle: Int? = null) {
    fun accepts(value: String) = if (kind == ListFilterKind.TOGGLE) value in setOf("true", "false") else options.any { it.value == value }
}
data class FilteredListPage<P>(val page: P, val fields: List<ListFilterField>, val values: Map<String, String>)
data class ListFiltersState(
    val open: Boolean = false,
    val fields: List<ListFilterField> = emptyList(),
    val values: Map<String, String> = emptyMap(),
    val draft: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
    val applying: Boolean = false,
    val failure: LoadFailure? = null,
    val applyFailure: LoadFailure? = null,
) {
    val applyFailed get() = applyFailure != null
    val activeFields get() = fields.filter { (values[it.id] ?: it.defaultValue) != it.defaultValue }
    val activeCount get() = activeFields.size
    val summary get() = activeFields.filter { it.kind == ListFilterKind.SINGLE }.mapNotNull { field ->
        values[field.id]?.takeIf { it != field.defaultValue }?.let { selected -> field.options.find { it.value == selected }?.label }
    }.joinToString(" · ")
}

interface ListFiltersController {
    val state: StateFlow<ListFiltersState>
    fun open()
    fun dismiss()
    fun choose(field: String, value: String)
    fun reset()
    fun apply()
}

interface ListFilterSource<P> {
    val fields: List<ListFilterField>
    suspend fun options(): List<ListFilterField>
    suspend fun load(values: Map<String, String>): FilteredListPage<P>
    fun restore(owner: String): Map<String, String>
    fun save(owner: String, values: Map<String, String>)
}

class ListFilterPreferences(
    private val read: (String) -> String? = { null },
    private val write: (String, String) -> Unit = { _, _ -> },
) {
    fun read(owner: String, section: String): Map<String, String> = runCatching {
        val obj = JSONObject(read("$owner:$section") ?: return emptyMap())
        obj.keys().asSequence().associateWith { obj.getString(it) }.also { values ->
            require(values.size <= 32 && values.values.all { it.length <= 160 && it.none(Char::isISOControl) })
        }
    }.getOrDefault(emptyMap())
    fun write(owner: String, section: String, values: Map<String, String>) = write("$owner:$section", JSONObject(values).toString())
}
