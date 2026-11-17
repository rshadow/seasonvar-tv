package ru.seasonvar.tv.probe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import ru.seasonvar.tv.data.Episode
import ru.seasonvar.tv.data.SeasonOption
import ru.seasonvar.tv.data.SeriesDetails
import ru.seasonvar.tv.data.SeriesEntry
import ru.seasonvar.tv.data.Translation
import ru.seasonvar.tv.ui.details.SeriesDetailsViewModel
import java.io.File

/** Локальные данные без сети; модель принадлежит прогону и закрывается его сессией. */
internal class BackdropFixtures(artifacts: File) : AutoCloseable {
    val poster = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888).also { bitmap ->
        Canvas(bitmap).apply {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 320f, 480f,
                    intArrayOf(Color.rgb(18, 40, 90), Color.rgb(32, 160, 180), Color.rgb(225, 90, 55)), null, Shader.TileMode.CLAMP)
            }
            drawPaint(paint)
            paint.shader = null; paint.color = Color.rgb(240, 190, 100)
            drawCircle(250f, 150f, 110f, paint)
        }
    }
    private val image = File(artifacts, "fixture.png").also { file ->
        file.outputStream().use { poster.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private val blue = File(artifacts, "blue.png").also { file ->
        Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(20, 90, 210))
            file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
    }
    val entry = SeriesEntry("123", "https://seasonvar.ru/serial-123-Test.html", "Проверка оформления", 1, "", image.toURI().toString())
    private val details = SeriesDetails(entry, "12", listOf("Описание тестового сериала. Текст и кнопки сохраняют свои места при смене фона."),
        listOf("Жанр" to "Приключения", "Страна" to "Тест"), emptyList(), listOf(SeasonOption("123", entry.url, "Сезон 1", 1)),
        listOf(Translation("0", "Оригинал", (1..30).map { Episode(it.toString(), it.toString()) })), "0", reviewCount = 0)
    val model = SeriesDetailsViewModel { details }
    val rows = listOf(entry.copy(title = "Первый сериал"),
        entry.copy(id = "124", url = "/serial-124-Test.html", title = "Второй сериал", posterUrl = blue.toURI().toString()),
        entry.copy(id = "125", url = "/serial-125-Test.html", title = "Нет постера", posterUrl = File(artifacts, "missing.png").toURI().toString()))

    /** Вызывается на главном потоке после закрытия тестовой Activity. */
    override fun close() { model.close() }
}
