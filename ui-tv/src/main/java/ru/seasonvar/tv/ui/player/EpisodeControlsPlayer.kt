package ru.seasonvar.tv.ui.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import ru.seasonvar.tv.data.playback.PlaybackRequest

@androidx.annotation.OptIn(UnstableApi::class)
internal class EpisodeControlsPlayer(
    player: Player,
    private val request: PlaybackRequest,
    private val navigate: (Int) -> Unit,
) : ForwardingPlayer(player) {
    private val hasPrevious = request.adjacent(-1) != null
    private val hasNext = request.adjacent(1) != null
    private val listeners = mutableMapOf<Player.Listener, Player.Listener>()

    private fun commands(original: Player.Commands): Player.Commands = original.buildUpon()
        .remove(Player.COMMAND_SEEK_TO_PREVIOUS).remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        .remove(Player.COMMAND_SEEK_TO_NEXT).remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, hasPrevious)
        .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, hasPrevious)
        .addIf(Player.COMMAND_SEEK_TO_NEXT, hasNext)
        .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNext).build()

    override fun getAvailableCommands(): Player.Commands = commands(super.getAvailableCommands())
    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
    override fun seekToNext() { if (hasNext) navigate(1) }
    override fun seekToNextMediaItem() = seekToNext()
    override fun seekToPrevious() { if (hasPrevious) navigate(-1) }
    override fun seekToPreviousMediaItem() = seekToPrevious()

    override fun addListener(listener: Player.Listener) {
        val forwarding = listeners.getOrPut(listener) {
            object : ForwardingPlayerListener(listener) {
                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                    listener.onAvailableCommandsChanged(commands(availableCommands))
                }
            }
        }
        super.addListener(forwarding)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)?.let { super.removeListener(it) }
    }
}
