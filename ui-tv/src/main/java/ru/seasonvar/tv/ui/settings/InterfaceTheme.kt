package ru.seasonvar.tv.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import org.xmlpull.v1.XmlPullParser
import ru.seasonvar.tv.R

internal data class InterfaceTheme(val storageId: String, @get:StringRes val label: Int, @get:StyleRes val styleRes: Int) {
    companion object {
        val CLASSIC = InterfaceTheme("plain", R.string.interface_theme_classic, R.style.SeasonvarInterfaceTheme_Classic)
        val GLASS = InterfaceTheme("glass", R.string.interface_theme_glass, R.style.SeasonvarInterfaceTheme_Glass)
    }
}

internal class InterfaceThemeCatalog(val entries: List<InterfaceTheme>, defaultId: String) {
    init {
        require(entries.isNotEmpty() && entries.map { it.storageId }.distinct().size == entries.size)
        require(entries.all {
            it.storageId != "blurred_poster" && it.storageId.matches(Regex("[a-z][a-z0-9_]{0,63}")) &&
                it.label != 0 && it.styleRes != 0
        })
    }
    val default = requireNotNull(entries.firstOrNull { it.storageId == defaultId })
    fun restore(value: String?): InterfaceTheme = entries.firstOrNull {
        it.storageId == if (value == "blurred_poster") "glass" else value
    } ?: default
}

internal fun loadInterfaceThemes(context: Context, @androidx.annotation.XmlRes registry: Int = R.xml.interface_themes): InterfaceThemeCatalog {
    val xml = context.resources.getXml(registry)
    try {
        val entries = mutableListOf<InterfaceTheme>()
        var defaultId: String? = null
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            if (xml.eventType != XmlPullParser.START_TAG) continue
            when (xml.name) {
                "themes" -> { require(xml.depth == 1); defaultId = xml.getAttributeValue(null, "defaultTheme") }
                "theme" -> {
                    require(xml.depth == 2)
                    val label = xml.getAttributeResourceValue(null, "label", 0)
                    val style = xml.getAttributeResourceValue(null, "style", 0)
                    require(label != 0 && style != 0 && context.getString(label).isNotBlank())
                    require(context.resources.getResourceTypeName(label) == "string" && context.resources.getResourceTypeName(style) == "style")
                    entries += InterfaceTheme(requireNotNull(xml.getAttributeValue(null, "id")), label, style)
                }
                else -> error("Unknown interface theme registry element")
            }
        }
        return InterfaceThemeCatalog(entries.toList(), requireNotNull(defaultId))
    } finally { xml.close() }
}
