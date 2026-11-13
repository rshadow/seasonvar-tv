package ru.seasonvar.tv.ui.details

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.SeriesActor
import ru.seasonvar.tv.ui.theme.SeasonvarTheme
import ru.seasonvar.tv.ui.actor.ActorSeriesActivity

@Composable
internal fun SeriesActorsRow(
    actors: List<SeriesActor>, listState: LazyListState, focus: List<FocusRequester>,
    returnFocus: FocusRequester, menuFocus: FocusRequester, onFocused: (Int) -> Unit,
) {
    val d = SeasonvarTheme.dimensions
    Column(verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
        Text(if (actors.isEmpty()) "" else stringResource(R.string.details_cast), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(state = listState, modifier = Modifier.fillMaxWidth()
            .height(d.actorPhotoSize + d.actorNameHeight + d.spacingSmall + d.focusBorderWidth * 2),
            horizontalArrangement = Arrangement.spacedBy(d.spacingSmall), contentPadding = PaddingValues(d.focusBorderWidth)) {
            itemsIndexed(actors, key = { _, actor -> actor.name }) { index, actor ->
                ActorTile(actor, Modifier.focusRequester(focus[index]).focusProperties {
                    up = FocusRequester.Cancel
                    down = returnFocus
                    if (index == 0) left = menuFocus
                    if (index == actors.lastIndex) right = FocusRequester.Cancel
                }, onFocused = { onFocused(index) })
            }
        }
    }
}

@Composable
private fun ActorTile(actor: SeriesActor, modifier: Modifier, onFocused: () -> Unit) {
    val d = SeasonvarTheme.dimensions
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val request = remember(actor.photoUrl, context) {
        ImageRequest.Builder(context).data(actor.photoUrl).memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED).build()
    }
    Column(modifier.width(d.actorTileWidth).semantics(mergeDescendants = true) {}
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
        .then(if (actor.url == null) Modifier.focusable() else Modifier.clickable(role = Role.Button) {
            ActorSeriesActivity.open(context, actor)
        }),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(d.spacingSmall)) {
        AsyncImage(request, null, Modifier.size(d.actorPhotoSize).clip(RoundedCornerShape(d.buttonCornerRadius))
            .border(d.focusBorderWidth, if (focused) c.primary else Color.Transparent, RoundedCornerShape(d.buttonCornerRadius)),
            contentScale = ContentScale.Crop, placeholder = painterResource(R.drawable.actor_placeholder),
            error = painterResource(R.drawable.actor_placeholder))
        Text(actor.name, Modifier.fillMaxWidth().height(d.actorNameHeight),
            style = MaterialTheme.typography.bodySmall, color = if (focused) c.primary else c.onSurfaceVariant,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
