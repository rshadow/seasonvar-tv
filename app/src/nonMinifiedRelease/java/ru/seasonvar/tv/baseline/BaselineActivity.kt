package ru.seasonvar.tv.baseline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import ru.seasonvar.tv.data.*
import ru.seasonvar.tv.data.account.AccountSession
import ru.seasonvar.tv.data.account.AccountUser
import ru.seasonvar.tv.data.account.PersonalFeed
import ru.seasonvar.tv.ui.SeasonvarApp
import ru.seasonvar.tv.ui.catalog.CatalogLayout
import ru.seasonvar.tv.ui.catalog.CatalogUiState
import ru.seasonvar.tv.ui.details.SeriesDetailsViewModel
import ru.seasonvar.tv.ui.navigation.Section
import ru.seasonvar.tv.ui.settings.InterfaceTheme
import java.time.LocalDate
import java.util.Locale

class BaselineActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(android.os.Build.MODEL.contains("sdk", true))
        check(packageName.endsWith(".baseline"))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        val theme = if (intent.getBooleanExtra("glass", false)) InterfaceTheme.GLASS else InterfaceTheme.CLASSIC
        val layout = if (intent.getBooleanExtra("grid", false)) CatalogLayout.GRID else CatalogLayout.STRIP
        val rows = (1..250).map { index ->
            SeriesEntry(index.toString(), "https://seasonvar.ru/serial-$index-Fixture.html",
                "Карточка " + String.format(Locale.ROOT, "%03d", index), 1, "",
                "android.resource://$packageName/drawable/poster_placeholder?card=$index",
                previewDescription = "Описание для навигации Baseline Profile.")
        }
        val page = CatalogUiState(date = LocalDate.of(2026, 11, 16), entries = rows, loaded = true, loading = false)
        val details = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(SeriesDetailsViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return SeriesDetailsViewModel { entry ->
                    SeriesDetails(entry, entry.id, listOf("Описание карточки Baseline Profile."),
                        listOf("Год" to "2026"), emptyList(),
                        listOf(SeasonOption(entry.id, entry.url, "Сезон 1", 1)),
                        listOf(Translation("0", "Стандартный", (1..12).map { Episode(it.toString(), it.toString()) })), "0")
                } as T
            }
        })[SeriesDetailsViewModel::class.java]
        setContent {
            var layouts by remember { mutableStateOf(Section.entries.associateWith { layout }) }
            SeasonvarApp(PublicFeed.entries.associateWith { page }, {}, startPage = Section.TOP,
                interfaceTheme = theme, imagePrefetchEnabled = true,
                catalogLayouts = layouts, onCatalogLayoutChange = { section, value -> layouts = layouts + (section to value) },
                accountSession = AccountSession(AccountUser("fixture", "Проверка", null), 1),
                personalStates = PersonalFeed.entries.associateWith { page }, smartState = page,
                detailsModel = details, loadDescription = { SeriesDescription(listOf("Описание для навигации Baseline Profile.")) })
        }
    }
}
