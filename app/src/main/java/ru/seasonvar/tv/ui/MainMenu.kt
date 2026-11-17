package ru.seasonvar.tv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.account.AccountSession
import ru.seasonvar.tv.ui.navigation.MainFocus
import ru.seasonvar.tv.ui.navigation.MenuGroup
import ru.seasonvar.tv.ui.navigation.Section
import ru.seasonvar.tv.ui.theme.SeasonvarTheme

@Composable
internal fun MainMenu(groups: List<Pair<MenuGroup, List<Section>>>, pages: List<Section>, selected: Section,
    accountSession: AccountSession, focus: MainFocus, browseEnabled: Boolean,
    onMenuFocus: () -> Unit, onSearch: () -> Unit, onBrowse: () -> Unit, onAccount: () -> Unit,
    onChoose: (Section) -> Unit, onFocused: (Section) -> Unit, onEnter: (Section) -> Unit) {
    val d = SeasonvarTheme.dimensions
    Column(Modifier.width(d.menuWidth).fillMaxHeight()
        .onFocusChanged { if (it.hasFocus) {
            onMenuFocus()
        } }.focusGroup()) {
        Row(horizontalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
            ThemeButton(stringResource(R.string.search_title), onClick = onSearch, modifier = Modifier.focusRequester(focus.searchFocus).focusProperties { down = focus.sectionFocus(pages.first()); right = focus.browseFocus },
                icon = R.drawable.ic_search)
            ThemeButton(stringResource(R.string.browse_catalog_title), onBrowse, Modifier.focusRequester(focus.browseFocus).focusProperties { left = focus.searchFocus; right = focus.accountFocus; down = focus.sectionFocus(pages.first()) },
                icon = R.drawable.ic_catalog, actionEnabled = browseEnabled)
            ThemeButton(stringResource(if (accountSession.user == null) R.string.account_sign_in else R.string.account_title),
                onClick = onAccount,
                modifier = Modifier.focusRequester(focus.accountFocus).focusProperties { left = focus.browseFocus; right = FocusRequester.Cancel; down = focus.sectionFocus(pages.first()) },
                icon = R.drawable.ic_person, avatarUrl = accountSession.user?.avatarUrl, avatarKey = accountSession.version)
        }
        Spacer(Modifier.height(d.menuHeaderSpacing))
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(d.menuItemSpacing),
        ) {
            groups.forEachIndexed { index, (group, sections) ->
                key(group) {
                    Text(
                        stringResource(menuGroupTitle(group)),
                        modifier = Modifier.fillMaxWidth()
                            .then(if (index > 0) Modifier.padding(top = d.spacingSmall) else Modifier)
                            .padding(horizontal = d.spacingSmall, vertical = d.menuItemSpacing)
                            .semantics { heading() },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    sections.forEach { item ->
                        key(item) {
                            val itemFocus = focus.sectionFocus(item)
                            val label = sectionTitle(item)
                            ThemeButton(
                                stringResource(label),
                                onClick = { onChoose(item) },
                                modifier = Modifier.fillMaxWidth().height(d.menuItemHeight)
                                    .bringIntoViewRequester(focus.menuBringIntoView.getValue(item))
                                    .focusRequester(itemFocus).focusProperties { if (item == pages.first()) up = focus.searchFocus }
                                    .onFocusChanged {
                                        if (it.isFocused) onFocused(item)
                                    }
                                    .onPreviewKeyEvent {
                                        if (it.key == Key.DirectionRight && it.type == KeyEventType.KeyDown) {
                                            onEnter(item)
                                            true
                                        } else false
                                    },
                                active = selected == item,
                                showOutline = SeasonvarTheme.menuButtonOutline,
                                contentAlignment = Alignment.CenterStart,
                                contentPadding = PaddingValues(horizontal = d.menuItemPaddingHorizontal, vertical = d.spacingSmall),
                            )
                        }
                    }
                }
            }
        }
    }
}
