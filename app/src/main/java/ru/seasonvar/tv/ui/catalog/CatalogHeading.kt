package ru.seasonvar.tv.ui.catalog

import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import ru.seasonvar.tv.R

@Composable
internal fun catalogHeaderMetadata(count: Int?, hasMore: Boolean, date: String?, status: String?): String = buildString {
    if (date != null) append(stringResource(R.string.catalog_header_detail, date))
    if (count != null) append(stringResource(R.string.catalog_header_count, count, if (hasMore) "+" else ""))
    if (status != null) append(stringResource(R.string.catalog_header_detail, status))
}

@Composable
internal fun CatalogHeading(title: String, metadata: String, modifier: Modifier = Modifier) {
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val text = buildAnnotatedString {
        append(title)
        withStyle(typography.bodySmall.toSpanStyle().copy(color = colors.onSurfaceVariant)) {
            append(metadata)
        }
    }
    Text(text, modifier.semantics { heading() }, style = typography.headlineSmall,
        color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
