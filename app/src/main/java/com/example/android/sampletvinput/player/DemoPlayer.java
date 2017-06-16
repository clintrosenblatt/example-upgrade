/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sampletvinput.player;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodec.CryptoException;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.RequiresApi;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.audio.MediaCodecAudioRenderer;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer.DecoderInitializationException;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.audio.AudioTrack;
import com.google.android.exoplayer2.drm.DefaultDrmSessionManager;
import com.google.android.exoplayer2.metadata.MetadataRenderer;
import com.google.android.exoplayer2.metadata.id3.Id3Frame;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.TextRenderer;
import com.google.android.exoplayer2.upstream.BandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;

import com.google.android.media.tv.companionlibrary.TvPlayer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A wrapper around {@link ExoPlayer} that provides a higher level interface. It can be prepared
 * with one of a number of {@link RendererBuilder} classes to suit different use cases (e.g. DASH,
 * SmoothStreaming and so on).
 * <p/>
 * This code was originally taken from the ExoPlayer demo application.
 */
public class DemoPlayer implements TvPlayer {

    /**
     * Builds renderers for the player.
     */
    public interface RendererBuilder {
        /**
         * Builds renderers for playback.
         *
         * @param player The player for which renderers are being built. {@link
         *               DemoPlayer#onRenderers} should be invoked once the renderers have been
         *               built. If building fails, {@link DemoPlayer#onRenderersError} should be
         *               invoked.
         */
        void buildRenderers(DemoPlayer player);

        /**
         * Cancels the current build operation, if there is one. Else does nothing.
         * <p/>
         * A canceled build operation must not invoke {@link DemoPlayer#onRenderers} or {@link
         * DemoPlayer#onRenderersError} on the player, which may have been released.
         */
        void cancel();
    }

    /**
     * A listener for core events.
     */
    public interface Listener {
        void onStateChanged(boolean playWhenReady, int playbackState);

        void onError(Exception e);

        void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees,
                float pixelWidthHeightRatio);
    }

    /**
     * A listener for internal errors.
     * <p/>
     * These errors are not visible to the user, and hence this listener is provided for
     * informational purposes only. Note however that an internal error may cause a fatal error if
     * the player fails to recover. If this happens, {@link Listener#onError(Exception)} will be
     * invoked.
     */
    public interface InternalErrorListener {
        void onRendererInitializationError(Exception e);

        void onAudioTrackInitializationError(AudioTrack.InitializationException e);

        void onAudioTrackWriteError(AudioTrack.WriteException e);

        void onAudioTrackUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs);

        void onDecoderInitializationError(DecoderInitializationException e);

        void onCryptoError(CryptoException e);

        void onLoadError(int sourceId, IOException e);

        void onDrmSessionManagerError(Exception e);
    }

    /**
     * A listener for debugging information.
     */
    public interface InfoListener {
        void onVideoFormatEnabled(Format format, int trigger, long mediaTimeMs);

        void onAudioFormatEnabled(Format format, int trigger, long mediaTimeMs);

        void onDroppedFrames(int count, long elapsed);

        void onBandwidthSample(int elapsedMs, long bytes, long bitrateEstimate);

        void onLoadStarted(int sourceId, long length, int type, int trigger, Format format,
                long mediaStartTimeMs, long mediaEndTimeMs);

        void onLoadCompleted(int sourceId, long bytesLoaded, int type, int trigger, Format format,
                long mediaStartTimeMs, long mediaEndTimeMs, long elapsedRealtimeMs,
                long loadDurationMs);

        void onDecoderInitialized(String decoderName, long elapsedRealtimeMs,
                long initializationDurationMs);

    }

    /**
     * A listener for receiving notifications of timed text.
     */
    public interface CaptionListener {
        void onCues(List<Cue> cues);
    }

    /**
     * A listener for receiving ID3 metadata parsed from the media stream.
     */
    public interface Id3MetadataListener {
        void onId3Metadata(List<Id3Frame> id3Frames);
    }

    // Constants pulled into this class for convenience.
    public static final int STATE_IDLE = ExoPlayer.STATE_IDLE;
    public static final int STATE_BUFFERING = ExoPlayer.STATE_BUFFERING;
    public static final int STATE_READY = ExoPlayer.STATE_READY;
    public static final int STATE_ENDED = ExoPlayer.STATE_ENDED;
    public static final int TRACK_DEFAULT = C.TRACK_TYPE_DEFAULT;

    public static final int RENDERER_COUNT = 4;

    public static final int TYPE_AUDIO = 0;
    public static final int TYPE_VIDEO = 1;
    public static final int TYPE_TEXT = 2;
    public static final int TYPE_METADATA = 3;

    private static final int RENDERER_BUILDING_STATE_IDLE = 1;
    private static final int RENDERER_BUILDING_STATE_BUILDING = 2;
    private static final int RENDERER_BUILDING_STATE_BUILT = 3;

    public static final int STATE_PREPARING = 2;


    private static final float DEFAULT_PLAYBACK_SPEED = 1.0f;

    private final ExoPlayer player;
    private final Handler mainHandler;
    private final CopyOnWriteArrayList<Listener> listeners;
    private final List<TvPlayer.Callback> mTvPlayerCallbacks;

    private int rendererBuildingState;
    private int lastReportedPlaybackState;
    private boolean lastReportedPlayWhenReady;

    private Surface surface;
    private Renderer videoRenderer;
    private Renderer audioRenderer;
    private Format videoFormat;
    private int videoTrackToRestore;

    private BandwidthMeter bandwidthMeter;
    private boolean backgrounded;

    private CaptionListener captionListener;
    private Id3MetadataListener id3MetadataListener;
    private InternalErrorListener internalErrorListener;
    private InfoListener infoListener;

    private static final DefaultBandwidthMeter BANDWIDTH_METER = new DefaultBandwidthMeter();
    private EventLogger eventLogger;
    private DefaultTrackSelector trackSelector;
    private Context mContext;

    private DataSource.Factory mediaDataSourceFactory;

    private Renderer[] mRenderers;

    private PlaybackParams playbackParams;



    public DemoPlayer(Context context, int videoType, Uri uri){
        mContext = context;

        mediaDataSourceFactory = buildDataSourceFactory(BANDWIDTH_METER);

        @DefaultRenderersFactory.ExtensionRendererMode int extensionRendererMode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON;
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context, null, extensionRendererMode);

        TrackSelection.Factory adaptiveTrackSelectionFactory = new AdaptiveTrackSelection.Factory(BANDWIDTH_METER);
        trackSelector = new DefaultTrackSelector(adaptiveTrackSelectionFactory);

        ArrayList<Renderer> renderersList = new ArrayList<>();

        videoRenderer = new MediaCodecVideoRenderer(mContext, MediaCodecSelector.DEFAULT, MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        renderersList.add(videoRenderer);

        audioRenderer = new MediaCodecAudioRenderer(MediaCodecSelector.DEFAULT);
        renderersList.add(audioRenderer);

        mRenderers = renderersList.toArray(new Renderer[renderersList.size()]);

        // Create the player
        player = ExoPlayerFactory.newInstance(mRenderers,trackSelector);

        //player = ExoPlayerFactory.newSimpleInstance(renderersFactory, trackSelector);

        //player.clearVideoSurface();

        MediaSource mediaSource = buildMediaSource(uri, null);
        //player.prepare(mediaSource, true, false);
        player.prepare(mediaSource, false, false);


        eventLogger = new EventLogger(trackSelector);
        player.addListener(eventLogger);
        //player.setAudioDebugListener(eventLogger);
        //player.setVideoDebugListener(eventLogger);
        //player.setMetadataOutput(eventLogger);

        Log.d("DemoPlayer", "We just initialized the player");
        Log.d("DemoPlayer", "**************************");

        player.setPlayWhenReady(true);

        //player.setVideoSurface(surface);

        mainHandler = new Handler();
        listeners = new CopyOnWriteArrayList<>();
        mTvPlayerCallbacks = new CopyOnWriteArrayList<>();
        lastReportedPlaybackState = STATE_IDLE;
        rendererBuildingState = RENDERER_BUILDING_STATE_IDLE;
    }

    private MediaSource buildMediaSource(Uri uri, String overrideExtension) {
        int type = TextUtils.isEmpty(overrideExtension) ? Util.inferContentType(uri)
                : Util.inferContentType("." + overrideExtension);

        switch (type) {
            case C.TYPE_SS:
                return new SsMediaSource(uri, buildDataSourceFactory(BANDWIDTH_METER),
                        new DefaultSsChunkSource.Factory(mediaDataSourceFactory), mainHandler, eventLogger);

            case C.TYPE_DASH:
                Log.d("DemoPlayer", "Created DASH media source");
                Log.d("DemoPlayer", "**************************");

                return new DashMediaSource(uri, buildDataSourceFactory(BANDWIDTH_METER),
                        new DefaultDashChunkSource.Factory(mediaDataSourceFactory), mainHandler, eventLogger);

            case C.TYPE_HLS:
                return new HlsMediaSource(uri, mediaDataSourceFactory, mainHandler, eventLogger);

            case C.TYPE_OTHER:
                return new ExtractorMediaSource(uri, mediaDataSourceFactory, new DefaultExtractorsFactory(),mainHandler, eventLogger);

            default: {
                throw new IllegalStateException("Unsupported type: " + type);
            }
        }
    }


    public DataSource.Factory buildDataSourceFactory(DefaultBandwidthMeter bandwidthMeter) {
        return new DefaultDataSourceFactory(mContext, bandwidthMeter, buildHttpDataSourceFactory(bandwidthMeter));
    }


    public HttpDataSource.Factory buildHttpDataSourceFactory(DefaultBandwidthMeter bandwidthMeter) {
        String userAgent = Util.getUserAgent(mContext, "ProjectJack-v3");
        return new DefaultHttpDataSourceFactory(userAgent, bandwidthMeter);
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    @Override
    public void registerCallback(TvPlayer.Callback callback) {
        mTvPlayerCallbacks.add(callback);
    }

    @Override
    public void unregisterCallback(TvPlayer.Callback callback) {
        mTvPlayerCallbacks.remove(callback);
    }

    public void setInternalErrorListener(InternalErrorListener listener) {
        internalErrorListener = listener;
    }

    public void setInfoListener(InfoListener listener) {
        infoListener = listener;
    }

    public void setCaptionListener(CaptionListener listener) {
        captionListener = listener;
    }

    public void setMetadataListener(Id3MetadataListener listener) {
        id3MetadataListener = listener;
    }

    public void setSurface(Surface surface) {
        this.surface = surface;
        pushSurface(false);
    }

    public Surface getSurface() {
        return surface;
    }

    public void blockingClearSurface() {
        surface = null;
        pushSurface(true);
    }

    public boolean getBackgrounded() {
        return backgrounded;
    }


    public int getPlaybackState() {
        if (rendererBuildingState == RENDERER_BUILDING_STATE_BUILDING) {
            return STATE_PREPARING;
        }
        int playerState = player.getPlaybackState();
        if (rendererBuildingState == RENDERER_BUILDING_STATE_BUILT && playerState == STATE_IDLE) {
            // This is an edge case where the renderers are built, but are still being passed to the
            // player's playback thread.
            return STATE_PREPARING;
        }
        return playerState;
    }



    public void prepare() {
        if (rendererBuildingState == RENDERER_BUILDING_STATE_BUILT) {
            player.stop();
        }

        //rendererBuilder.cancel();

        videoFormat = null;
        videoRenderer = null;
        rendererBuildingState = RENDERER_BUILDING_STATE_BUILDING;
        maybeReportPlayerState();
    }

    /**
     * Invoked with the results from a {@link RendererBuilder}.
     *
     * @param renderers      Renderers indexed by {@link DemoPlayer} TYPE_* constants. An individual
     *                       element may be null if there do not exist tracks of the corresponding
     *                       type.
     * @param bandwidthMeter Provides an estimate of the currently available bandwidth. May be
     *                       null.
     */
  /* package */ void onRenderers(Renderer[] renderers, BandwidthMeter bandwidthMeter) {



        for (int i = 0; i < RENDERER_COUNT; i++) {
            if (renderers[i] == null) {
                // Convert a null renderer to a dummy renderer.
            }
        }
        // Complete preparation.
        this.videoRenderer = renderers[TYPE_VIDEO];
        this.audioRenderer = renderers[TYPE_AUDIO];

        this.bandwidthMeter = bandwidthMeter;
        pushSurface(false);

        //player.prepare(renderers);

        rendererBuildingState = RENDERER_BUILDING_STATE_BUILT;
    }

    /**
     * Invoked if a {@link RendererBuilder} encounters an error.
     *
     * @param e Describes the error.
     */
  /* package */ void onRenderersError(Exception e) {
        if (internalErrorListener != null) {
            internalErrorListener.onRendererInitializationError(e);
        }
        for (Listener listener : listeners) {
            listener.onError(e);
        }
        rendererBuildingState = RENDERER_BUILDING_STATE_IDLE;
    }

    public void setPlayWhenReady(boolean playWhenReady) {
        player.setPlayWhenReady(playWhenReady);
    }

    public void seekTo(long positionMs) {
        player.seekTo(positionMs);
    }

    @Override
    public void setPlaybackParams(PlaybackParams params) {

    }

    public void release() {
        rendererBuildingState = RENDERER_BUILDING_STATE_IDLE;
        surface = null;
        player.release();
    }



    @TargetApi(Build.VERSION_CODES.M)
    public float getPlaybackSpeed() {
        return playbackParams == null ? DEFAULT_PLAYBACK_SPEED : playbackParams.getSpeed();
    }

    @Override
    public long getCurrentPosition() {
        return player.getCurrentPosition();
    }

    @Override
    public long getDuration() {
        return player.getDuration();
    }

    public int getBufferedPercentage() {
        return player.getBufferedPercentage();
    }

    public boolean getPlayWhenReady() {
        return player.getPlayWhenReady();
    }


    /* package */ Handler getMainHandler() {
        return mainHandler;
    }

    public void setVolume(float volume) {
        player.sendMessages(new ExoPlayer.ExoPlayerMessage(audioRenderer, C.MSG_SET_VOLUME,
                volume));
    }

    public void play() {
        player.setPlayWhenReady(true);
    }

    public void pause() {
        player.setPlayWhenReady(false);
    }

    public void stop() {
        player.stop();
    }

    private void maybeReportPlayerState() {
        boolean playWhenReady = player.getPlayWhenReady();
        int playbackState = getPlaybackState();
        if (lastReportedPlayWhenReady != playWhenReady ||
                lastReportedPlaybackState != playbackState) {
            for (Listener listener : listeners) {
                listener.onStateChanged(playWhenReady, playbackState);
            }
            lastReportedPlayWhenReady = playWhenReady;
            lastReportedPlaybackState = playbackState;
        }
    }


    private void pushSurface(boolean blockForSurfacePush) {
        if (videoRenderer == null) {
            Log.d("DemoPlayer","The video renderer is null!");
            Log.d("DemoPlayer", "**********************************************");
            return;
        } else{
            Log.d("DemoPlayer","The video renderer is GOOD TO GO!");
            Log.d("DemoPlayer", "**********************************************");
        }

        if (blockForSurfacePush) {
            player.blockingSendMessages(new ExoPlayer.ExoPlayerMessage(videoRenderer, C.MSG_SET_SURFACE, surface));
        } else {
            player.sendMessages(new ExoPlayer.ExoPlayerMessage(videoRenderer, C.MSG_SET_SURFACE, surface));
        }

    }

}
