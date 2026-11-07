package ru.seasonvar.tv.data.home

import android.content.ContentUris
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.tv.TvContract
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.seasonvar.tv.MainActivity
import ru.seasonvar.tv.R
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.ui.home.HomePlaybackActivity
import java.io.File

fun homeChannelTitle(channel: HomeChannel): Int = when (channel) {
    HomeChannel.CONTINUE -> R.string.home_continue
    HomeChannel.WANT -> R.string.home_want
    HomeChannel.NEWEST -> R.string.home_newest
}

class HomePublisher(private val context: Context, private val store: HomeStore) {
    private val resolver get() = context.contentResolver
    val supported get() = context.packageManager.resolveContentProvider(TvContract.AUTHORITY, 0) != null
    val canRequestBrowsing get() = context.packageManager.resolveActivity(Intent(TvContract.ACTION_REQUEST_CHANNEL_BROWSABLE), 0) != null

    fun registerForLauncher() {
        val flags = context.getSharedPreferences("home-channel-platform", Context.MODE_PRIVATE)
        val existing = channels()
        for (channel in HomeChannel.entries) {
            val key = "registered-native-" + channel.storageId
            if (!flags.getBoolean(key, false)) {
                if (channel !in existing) prepare(channel, requestDefault = false)
                flags.edit().putBoolean(key, true).commit()
            }
        }
    }

    fun channels(): Map<HomeChannel, Long> {
        val result = mutableMapOf<HomeChannel, Long>()
        resolver.query(TvContract.Channels.CONTENT_URI, arrayOf("_id", TvContract.Channels.COLUMN_PACKAGE_NAME,
            TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1) == context.packageName) {
                HomeChannel.entries.firstOrNull { "seasonvar.home." + it.storageId == cursor.getString(2) }
                    ?.let { result[it] = cursor.getLong(0) }
            }
        }
        return result
    }

    fun prepare(channel: HomeChannel): Long = prepare(channel, requestDefault = true)

    private fun prepare(channel: HomeChannel, requestDefault: Boolean): Long {
        check(supported)
        channels()[channel]?.let {
            resolver.update(TvContract.buildChannelUri(it), ContentValues().apply {
                put(TvContract.Channels.COLUMN_DISPLAY_NAME, context.getString(homeChannelTitle(channel)))
            }, null, null)
            return it
        }
        val existing = channels()
        val uri = checkNotNull(resolver.insert(TvContract.Channels.CONTENT_URI, ContentValues().apply {
            put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW)
            put(TvContract.Channels.COLUMN_DISPLAY_NAME, context.getString(homeChannelTitle(channel)))
            put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, "seasonvar.home." + channel.storageId)
            put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, Intent(context, MainActivity::class.java).toUri(Intent.URI_INTENT_SCHEME))
        }))
        val id = ContentUris.parseId(uri)
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(context.getColor(R.color.seasonvar_background))
        context.getDrawable(R.drawable.ic_launcher)?.let { logo -> logo.setBounds(0, 0, 160, 160); logo.draw(canvas) }
        resolver.openOutputStream(TvContract.buildChannelLogoUri(id))?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val flags = context.getSharedPreferences("home-channel-platform", Context.MODE_PRIVATE)
        if (requestDefault && existing.isEmpty() && !flags.getBoolean("created", false)) TvContract.requestChannelBrowsable(context, id)
        flags.edit().putBoolean("created", true).apply()
        return id
    }

    fun browsable(id: Long): Boolean = resolver.query(TvContract.buildChannelUri(id),
        arrayOf(TvContract.Channels.COLUMN_BROWSABLE), null, null, null)?.use { it.moveToFirst() && it.getInt(0) != 0 } ?: false

    fun programs(channel: HomeChannel): Map<String, Long> {
        val id = channels()[channel] ?: return emptyMap()
        val result = mutableMapOf<String, Long>()
        resolver.query(TvContract.buildPreviewProgramsUriForChannel(id), arrayOf("_id", TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID),
            null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) result[cursor.getString(1).orEmpty()] = cursor.getLong(0)
        }
        return result
    }

    suspend fun publish(channel: HomeChannel, entries: List<SeriesEntry>, owner: String?, valid: () -> Boolean) {
        val id = channels()[channel] ?: return
        val old = programs(channel).toMutableMap()
        val staged = entries.distinctBy { it.id }.take(20).map { it to poster(it.posterUrl) }
        currentCoroutineContext().ensureActive()
        check(valid())
        val operations = mutableListOf<ContentProviderOperation>()
        for ((index, stagedCard) in staged.withIndex()) {
            val (entry, poster) = stagedCard
            val token = HomeLink.token(channel, entry.id)
            store.write(token, HomeCard(channel, entry, if (channel.personal) owner else null))
            val values = ContentValues().apply {
                put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, id)
                put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, token)
                put(TvContract.PreviewPrograms.COLUMN_TYPE, TvContract.PreviewPrograms.TYPE_TV_SERIES)
                put(TvContract.PreviewPrograms.COLUMN_TITLE, entry.title)
                put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION, entry.update.ifBlank { entry.season?.let { context.getString(R.string.home_season, it) }.orEmpty() })
                put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, poster.toString())
                put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.PreviewPrograms.ASPECT_RATIO_2_3)
                put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, HomePlaybackActivity.intent(context, token).toUri(Intent.URI_INTENT_SCHEME))
                put(TvContract.PreviewPrograms.COLUMN_WEIGHT, 1000 - index)
            }
            val previous = old.remove(token)
            operations += if (previous == null) ContentProviderOperation.newInsert(TvContract.PreviewPrograms.CONTENT_URI).withValues(values).build()
                else ContentProviderOperation.newUpdate(TvContract.buildPreviewProgramUri(previous)).withValues(values).build()
        }
        currentCoroutineContext().ensureActive()
        check(valid())
        old.values.forEach { operations += ContentProviderOperation.newDelete(TvContract.buildPreviewProgramUri(it)).build() }
        if (operations.isNotEmpty()) resolver.applyBatch(TvContract.AUTHORITY, ArrayList(operations))
        old.keys.forEach(store::remove)
    }

    fun clear(channel: HomeChannel) {
        programs(channel).forEach { (_, id) -> resolver.delete(TvContract.buildPreviewProgramUri(id), null, null) }
        store.cards(channel).keys.forEach(store::remove)
    }

    fun prunePosters() {
        val keep = HomeChannel.entries.flatMap { store.cards(it).values }.map {
            HomeLink.token(HomeChannel.NEWEST, it.entry.posterUrl) + ".jpg"
        }.toSet()
        File(context.filesDir, "home-posters").listFiles()?.forEach {
            if (it.name !in keep && System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete()
        }
    }

    private suspend fun poster(source: String): Uri {
        val name = HomeLink.token(HomeChannel.NEWEST, source) + ".jpg"
        val directory = File(context.filesDir, "home-posters").apply { mkdirs() }
        val target = File(directory, name)
        if (!target.isFile) {
            val result = context.imageLoader.execute(ImageRequest.Builder(context).data(source).size(320, 480).allowHardware(false).build())
            val drawable = result.drawable ?: throw java.io.IOException("Poster unavailable")
            val bitmap = drawable.toBitmap(320, 480)
            val temporary = File.createTempFile("poster-", ".jpg", directory)
            try {
                temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                check(temporary.renameTo(target))
            } finally { temporary.delete() }
        }
        return Uri.parse("content://${context.packageName}.home-posters/$name")
    }
}
