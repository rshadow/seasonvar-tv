package ru.seasonvar.tv.data.home

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

internal fun homePosterFile(directory: File, key: String): File {
    if (!key.matches(Regex("[0-9a-f]{64}\\.jpg"))) throw FileNotFoundException()
    val path = File(directory, key).canonicalPath
    if (!path.startsWith(directory.canonicalPath + File.separator)) throw FileNotFoundException()
    return File(path)
}

class HomePosterProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/jpeg"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val key = uri.lastPathSegment.orEmpty()
        if (mode != "r" || uri.pathSegments.size != 1) throw FileNotFoundException()
        val file = homePosterFile(File(requireNotNull(context).filesDir, "home-posters"), key)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
