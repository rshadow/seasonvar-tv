package ru.seasonvar.tv.data.images

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import coil.size.Size

private val diskOnlyDecoder = Decoder.Factory { _, _, _ ->
    Decoder { DecodeResult(ColorDrawable(Color.TRANSPARENT), false) }
}

internal suspend fun prefetchImage(context: Context, loader: ImageLoader, image: PrefetchImage) {
    val request = ImageRequest.Builder(context).data(image.url)
        .diskCachePolicy(CachePolicy.ENABLED).networkCachePolicy(CachePolicy.ENABLED)
        .apply {
            if (image.kind == PrefetchKind.THUMBNAIL) {
                size(image.width, image.height)
                scale(Scale.FILL)
                memoryCachePolicy(CachePolicy.ENABLED)
            } else {
                size(Size.ORIGINAL)
                memoryCachePolicy(CachePolicy.DISABLED)
                decoderFactory(diskOnlyDecoder)
            }
        }.build()
    val result = loader.execute(request)
    if (ru.seasonvar.tv.BuildConfig.DEBUG) android.util.Log.d("SeasonvarPrefetch",
        "kind=" + image.kind + " success=" + (result is SuccessResult) +
            (if (result is SuccessResult) " source=" + result.dataSource else ""))
}
