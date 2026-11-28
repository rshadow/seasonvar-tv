package ru.seasonvar.tv.ui.player;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Metadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import java.util.List;

/** Explicit forwarding preserves Java default callbacks so video events reach PlayerView. */
@UnstableApi
@SuppressWarnings("deprecation")
public class ForwardingPlayerListener implements Player.Listener {
    private final Player.Listener delegate;

    public ForwardingPlayerListener(Player.Listener delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onEvents(Player player, Player.Events events) {
        delegate.onEvents(player, events);
    }

    @Override
    public void onTimelineChanged(Timeline timeline, int reason) {
        delegate.onTimelineChanged(timeline, reason);
    }

    @Override
    public void onMediaItemTransition(MediaItem mediaItem, int reason) {
        delegate.onMediaItemTransition(mediaItem, reason);
    }

    @Override
    public void onTracksChanged(Tracks tracks) {
        delegate.onTracksChanged(tracks);
    }

    @Override
    public void onMediaMetadataChanged(MediaMetadata mediaMetadata) {
        delegate.onMediaMetadataChanged(mediaMetadata);
    }

    @Override
    public void onPlaylistMetadataChanged(MediaMetadata playlistMetadata) {
        delegate.onPlaylistMetadataChanged(playlistMetadata);
    }

    @Override
    public void onIsLoadingChanged(boolean isLoading) {
        delegate.onIsLoadingChanged(isLoading);
    }

    @Override
    public void onLoadingChanged(boolean isLoading) {
        delegate.onLoadingChanged(isLoading);
    }

    @Override
    public void onAvailableCommandsChanged(Player.Commands commands) {
        delegate.onAvailableCommandsChanged(commands);
    }

    @Override
    public void onTrackSelectionParametersChanged(TrackSelectionParameters parameters) {
        delegate.onTrackSelectionParametersChanged(parameters);
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        delegate.onPlayerStateChanged(playWhenReady, playbackState);
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        delegate.onPlaybackStateChanged(playbackState);
    }

    @Override
    public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
        delegate.onPlayWhenReadyChanged(playWhenReady, reason);
    }

    @Override
    public void onPlaybackSuppressionReasonChanged(int reason) {
        delegate.onPlaybackSuppressionReasonChanged(reason);
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        delegate.onIsPlayingChanged(isPlaying);
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {
        delegate.onRepeatModeChanged(repeatMode);
    }

    @Override
    public void onShuffleModeEnabledChanged(boolean enabled) {
        delegate.onShuffleModeEnabledChanged(enabled);
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        delegate.onPlayerError(error);
    }

    @Override
    public void onPlayerErrorChanged(PlaybackException error) {
        delegate.onPlayerErrorChanged(error);
    }

    @Override
    public void onPositionDiscontinuity(int reason) {
        delegate.onPositionDiscontinuity(reason);
    }

    @Override
    public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
        delegate.onPositionDiscontinuity(oldPosition, newPosition, reason);
    }

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters parameters) {
        delegate.onPlaybackParametersChanged(parameters);
    }

    @Override
    public void onSeekBackIncrementChanged(long seekBackIncrementMs) {
        delegate.onSeekBackIncrementChanged(seekBackIncrementMs);
    }

    @Override
    public void onSeekForwardIncrementChanged(long seekForwardIncrementMs) {
        delegate.onSeekForwardIncrementChanged(seekForwardIncrementMs);
    }

    @Override
    public void onMaxSeekToPreviousPositionChanged(long maxSeekToPreviousPositionMs) {
        delegate.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs);
    }

    @Override
    public void onAudioSessionIdChanged(int audioSessionId) {
        delegate.onAudioSessionIdChanged(audioSessionId);
    }

    @Override
    public void onAudioAttributesChanged(AudioAttributes attributes) {
        delegate.onAudioAttributesChanged(attributes);
    }

    @Override
    public void onVolumeChanged(float volume) {
        delegate.onVolumeChanged(volume);
    }

    @Override
    public void onSkipSilenceEnabledChanged(boolean enabled) {
        delegate.onSkipSilenceEnabledChanged(enabled);
    }

    @Override
    public void onDeviceInfoChanged(DeviceInfo deviceInfo) {
        delegate.onDeviceInfoChanged(deviceInfo);
    }

    @Override
    public void onDeviceVolumeChanged(int volume, boolean muted) {
        delegate.onDeviceVolumeChanged(volume, muted);
    }

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
        delegate.onVideoSizeChanged(videoSize);
    }

    @Override
    public void onSurfaceSizeChanged(int width, int height) {
        delegate.onSurfaceSizeChanged(width, height);
    }

    @Override
    public void onRenderedFirstFrame() {
        delegate.onRenderedFirstFrame();
    }

    @Override
    public void onCues(List<Cue> cues) {
        delegate.onCues(cues);
    }

    @Override
    public void onCues(CueGroup cueGroup) {
        delegate.onCues(cueGroup);
    }

    @Override
    public void onMetadata(Metadata metadata) {
        delegate.onMetadata(metadata);
    }
}
