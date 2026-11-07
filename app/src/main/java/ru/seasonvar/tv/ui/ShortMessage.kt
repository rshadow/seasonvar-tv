package ru.seasonvar.tv.ui

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

private var currentToast: Toast? = null

internal fun showShortMessage(context: Context, @StringRes message: Int) =
    showShortMessage(context, context.getString(message))

internal fun showShortMessage(context: Context, message: String) {
    currentToast?.cancel()
    currentToast = Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).also { it.show() }
}

@Composable
internal fun ShortMessageEffect(message: String?) {
    val context = LocalContext.current
    LaunchedEffect(message) {
        if (message != null) showShortMessage(context, message)
    }
}
