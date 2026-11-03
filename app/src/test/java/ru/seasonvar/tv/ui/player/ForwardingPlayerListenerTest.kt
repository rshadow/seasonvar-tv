package ru.seasonvar.tv.ui.player

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ForwardingPlayerListenerTest {
    @Test fun explicitly_overrides_every_java_default_callback_in_media3() {
        val callbacks = Player.Listener::class.java.methods.filter { it.returnType == Void.TYPE }
        for (callback in callbacks) {
            val forwarding = ForwardingPlayerListener::class.java.getMethod(callback.name, *callback.parameterTypes)
            assertEquals("Callback falls back to Java default: ${callback.name}",
                ForwardingPlayerListener::class.java, forwarding.declaringClass)
        }
    }

    @Test fun episode_adapter_keeps_rendered_frame_surface_and_playback_events() {
        var nativeListener: Player.Listener? = null
        var removedListener: Player.Listener? = null
        val native = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "addListener" -> nativeListener = args!![0] as Player.Listener
                "removeListener" -> removedListener = args!![0] as Player.Listener
            }
            null
        } as Player
        var frames = 0
        var surfaceWidth = 0
        var state = 0
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { frames++ }
            override fun onSurfaceSizeChanged(width: Int, height: Int) { surfaceWidth = width }
            override fun onPlaybackStateChanged(playbackState: Int) { state = playbackState }
        }
        val request = ru.seasonvar.tv.data.playback.PlaybackRequest("1", "https://seasonvar.ru/serial-1-test.html", "0", "1", "1", "Тест")
        val adapter = EpisodeControlsPlayer(native, request) {}
        adapter.addListener(listener)
        val registered = checkNotNull(nativeListener)
        registered.onRenderedFirstFrame()
        registered.onSurfaceSizeChanged(1280, 720)
        registered.onPlaybackStateChanged(Player.STATE_READY)
        assertEquals(1, frames)
        assertEquals(1280, surfaceWidth)
        assertEquals(Player.STATE_READY, state)
        adapter.removeListener(listener)
        assertSame(registered, removedListener)
    }
}
