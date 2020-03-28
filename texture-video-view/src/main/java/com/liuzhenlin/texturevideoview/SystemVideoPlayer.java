/*
 * Created on 2019/11/24 4:10 PM.
 * Copyright © 2019–2020 刘振林. All rights reserved.
 */

package com.liuzhenlin.texturevideoview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Messenger;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RawRes;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;

import com.google.android.material.snackbar.Snackbar;
import com.liuzhenlin.texturevideoview.model.AudioTrackInfo;
import com.liuzhenlin.texturevideoview.model.SubtitleTrackInfo;
import com.liuzhenlin.texturevideoview.model.TrackInfo;
import com.liuzhenlin.texturevideoview.model.VideoTrackInfo;
import com.liuzhenlin.texturevideoview.receiver.HeadsetEventsReceiver;
import com.liuzhenlin.texturevideoview.receiver.MediaButtonEventHandler;
import com.liuzhenlin.texturevideoview.receiver.MediaButtonEventReceiver;
import com.liuzhenlin.texturevideoview.utils.Utils;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

/**
 * A sub implementation class of {@link VideoPlayer} to deal with the audio/video playback logic
 * related to the media player component through a {@link android.media.MediaPlayer} object.
 *
 * @author 刘振林
 */
public class SystemVideoPlayer extends VideoPlayer {

  private static final String TAG = "SystemVideoPlayer";

  /**
   * Flag used to indicate that the volume of the video is auto-turned down by the system
   * when the player temporarily loses the audio focus.
   */
  private static final int $FLAG_VIDEO_VOLUME_TURNED_DOWN_AUTOMATICALLY = 1 << 31;

  /**
   * Flag indicates that a position seek request happens when the video is not playing.
   */
  private static final int $FLAG_SEEK_POSITION_WHILE_VIDEO_PAUSED = 1 << 30;

  /**
   * If true, MediaPlayer is moving the media to some specified time position
   */
  private static final int $FLAG_SEEKING = 1 << 29;

  /**
   * If true, MediaPlayer is temporarily pausing playback internally in order to buffer more data.
   */
  private static final int $FLAG_BUFFERING = 1 << 28;

  private MediaPlayer mMediaPlayer;

  /**
   * How much of the network-based video has been buffered from the media stream received
   * through progressive HTTP download.
   */
  private int mBuffering;

  private final AudioManager.OnAudioFocusChangeListener mOnAudioFocusChangeListener
      = new AudioManager.OnAudioFocusChangeListener() {
    @Override
    public void onAudioFocusChange(int focusChange) {
      switch (focusChange) {
        // Audio focus gained
        case AudioManager.AUDIOFOCUS_GAIN:
          if ((mInternalFlags & $FLAG_VIDEO_VOLUME_TURNED_DOWN_AUTOMATICALLY) != 0) {
            mInternalFlags &= ~$FLAG_VIDEO_VOLUME_TURNED_DOWN_AUTOMATICALLY;
            mMediaPlayer.setVolume(1.0f, 1.0f);
          }
          play(false);
          break;

        // Loss of audio focus of unknown duration.
        // This usually happens when the user switches to another audio/video application that causes
        // our view to stop playing, so the video can be thought of as being paused/closed by the user.
        case AudioManager.AUDIOFOCUS_LOSS:
          if (mVideoView != null && mVideoView.isInForeground()) {
            // If the view is still in the foreground, pauses the video only.
            pause(true);
          } else {
            // But if this occurs during background playback, we must close the video
            // to release the resources associated with it.
            closeVideoInternal(true);
          }
          break;

        // Temporarily lose the audio focus and will probably gain it again soon.
        // Must stop the video playback but no need for releasing the MediaPlayer here.
        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
          pause(false);
          break;

        // Temporarily lose the audio focus but the playback can continue.
        // The volume of the playback needs to be turned down.
        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
          mInternalFlags |= $FLAG_VIDEO_VOLUME_TURNED_DOWN_AUTOMATICALLY;
          mMediaPlayer.setVolume(0.5f, 0.5f);
          break;
      }
    }
  };
  private final AudioFocusRequest mAudioFocusRequest =
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
          new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
              .setAudioAttributes(sDefaultAudioAttrs.getAudioAttributesV21())
              .setOnAudioFocusChangeListener(mOnAudioFocusChangeListener)
              .setAcceptsDelayedFocusGain(true)
              .build()
          : null;

//  private static HttpProxyCacheServer sCacheServer;

//  private HttpProxyCacheServer getCacheServer() {
//    if (sCacheServer == null) {
//      sCacheServer = new HttpProxyCacheServer.Builder(mContext)
//          .cacheDirectory(new File(getBaseVideoCacheDirectory(), "sm"))
//          .maxCacheSize(DEFAULT_MAXIMUM_CACHE_SIZE)
//          .build();
//    }
//    return sCacheServer;
//  }

  public SystemVideoPlayer(@NonNull Context context) {
    super(context);
  }

  @Override
  public void setVideoResourceId(@RawRes int resId) {
    setVideoPath(resId == 0 ? null : "android.resource://" + mContext.getPackageName() + "/" + resId);
  }

  @Override
  protected boolean isPlayerCreated() {
    return mMediaPlayer != null;
  }

  @Override
  protected void onVideoSurfaceChanged(@Nullable Surface surface) {
    if (mMediaPlayer != null) {
      mMediaPlayer.setSurface(surface);
    }
  }

  @Override
  protected void openVideoInternal(@Nullable Surface surface) {
    if (mMediaPlayer == null && mVideoUri != null
        && !(mVideoView != null && surface == null)
        && (mInternalFlags & $FLAG_VIDEO_PAUSED_BY_USER) == 0) {
      mMediaPlayer = new MediaPlayer();
      mMediaPlayer.setSurface(surface);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        mMediaPlayer.setAudioAttributes(sDefaultAudioAttrs.getAudioAttributesV21());
      } else {
        mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
      }
      mMediaPlayer.setOnPreparedListener(mp -> {
        if (mVideoView != null) {
          mVideoView.showLoadingView(false);
        }
        if ((mInternalFlags & $FLAG_VIDEO_DURATION_DETERMINED) == 0) {
          onVideoDurationChanged(mp.getDuration());
          mInternalFlags |= $FLAG_VIDEO_DURATION_DETERMINED;
        }
        restoreTrackSelections();
        setPlaybackState(PLAYBACK_STATE_PREPARED);
        play(false);
      });
      mMediaPlayer.setOnVideoSizeChangedListener(
          (mp, width, height) -> onVideoSizeChanged(width, height));
      mMediaPlayer.setOnSeekCompleteListener(mp -> {
        mInternalFlags &= ~$FLAG_SEEKING;
        if ((mInternalFlags & $FLAG_BUFFERING) == 0) {
          if (mVideoView != null)
            mVideoView.showLoadingView(false);
        }
        onVideoSeekProcessed();
      });
      mMediaPlayer.setOnInfoListener((mp, what, extra) -> {
        switch (what) {
          case MediaPlayer.MEDIA_INFO_BUFFERING_START:
            mInternalFlags |= $FLAG_BUFFERING;
            if ((mInternalFlags & $FLAG_SEEKING) == 0) {
              if (mVideoView != null)
                mVideoView.showLoadingView(true);
            }
            break;
          case MediaPlayer.MEDIA_INFO_BUFFERING_END:
            mInternalFlags &= ~$FLAG_BUFFERING;
            if ((mInternalFlags & $FLAG_SEEKING) == 0) {
              if (mVideoView != null)
                mVideoView.showLoadingView(false);
            }
            break;
        }
        return false;
      });
      mMediaPlayer.setOnBufferingUpdateListener(
          (mp, percent) -> mBuffering = (int) (mVideoDuration * percent / 100f + 0.5f));
      mMediaPlayer.setOnErrorListener((mp, what, extra) -> {
        if (InternalConsts.DEBUG) {
          Log.e(TAG, "Error occurred while playing video: what= " + what + "; extra= " + extra);
        }
        showVideoErrorMsg(extra);

        if (mVideoView != null) {
          mVideoView.showLoadingView(false);
        }
        final boolean playing = isPlaying();
        setPlaybackState(PLAYBACK_STATE_ERROR);
        if (playing) {
          pauseInternal(false);
        }
        return true;
      });
      mMediaPlayer.setOnCompletionListener(mp -> {
        if (isSingleVideoLoopPlayback()) {
          mp.start();
          onVideoRepeat();
        } else {
          onPlaybackCompleted();
        }
      });
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
        mMediaPlayer.setOnTimedTextListener((mp, timedText) -> {
          if (mVideoView != null) {
            mVideoView.showSubtitle(timedText.getText(), timedText.getBounds());
          }
        });
      }
      startVideo();

      MediaButtonEventReceiver.setMediaButtonEventHandler(
          new MediaButtonEventHandler(new Messenger(new MsgHandler(this))));
      mHeadsetEventsReceiver = new HeadsetEventsReceiver(mContext) {
        @Override
        public void onHeadsetPluggedOutOrBluetoothDisconnected() {
          pause(true);
        }
      };
      mHeadsetEventsReceiver.register(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
    }
  }

  private void showVideoErrorMsg(int errorType) {
    final int stringRes;
    switch (errorType) {
      case MediaPlayer.MEDIA_ERROR_IO:
        stringRes = R.string.failedToLoadThisVideo;
        break;
      case MediaPlayer.MEDIA_ERROR_UNSUPPORTED:
      case MediaPlayer.MEDIA_ERROR_MALFORMED:
        stringRes = R.string.videoInThisFormatIsNotSupported;
        break;
      case MediaPlayer.MEDIA_ERROR_TIMED_OUT:
        stringRes = R.string.loadTimeout;
        break;
      default:
        stringRes = R.string.unknownErrorOccurredWhenVideoIsPlaying;
        break;
    }
    if (mVideoView != null) {
      Utils.showUserCancelableSnackbar(mVideoView, stringRes, Snackbar.LENGTH_SHORT);
    } else {
      Toast.makeText(mContext, stringRes, Toast.LENGTH_SHORT).show();
    }
  }

  private void startVideo() {
    if (mVideoUri != null) {
      try {
        mMediaPlayer.setDataSource(mContext, mVideoUri);
//        final String url = mVideoUri.toString();
//        if (URLUtils.isNetworkUrl(url)) {
//          mMediaPlayer.setDataSource(getCacheServer().getProxyUrl(url));
//        } else {
//          mMediaPlayer.setDataSource(mContext, mVideoUri);
//        }
        if (mVideoView != null) {
          mVideoView.showLoadingView(true);
        }
        setPlaybackState(PLAYBACK_STATE_PREPARING);
        mMediaPlayer.prepareAsync();
//        mMediaPlayer.setLooping(isSingleVideoLoopPlayback());
      } catch (IOException e) {
        e.printStackTrace();
        showVideoErrorMsg(/* MediaPlayer.MEDIA_ERROR_IO */ -1004);
        if (mVideoView != null) {
          mVideoView.showLoadingView(false); // in case it is already showing
        }
        setPlaybackState(PLAYBACK_STATE_ERROR);
      }
    } else {
      if (mVideoView != null) {
        mVideoView.showLoadingView(false);
      }
      setPlaybackState(PLAYBACK_STATE_IDLE);
    }
    if (mVideoView != null) {
      mVideoView.cancelDraggingVideoSeekBar();
    }
  }

  private void stopVideo() {
    mMediaPlayer.stop();
    mMediaPlayer.reset();
    if (mVideoView != null) {
      mVideoView.showSubtitles(null);
    }
  }

  @Override
  public void restartVideo() {
    restartVideo(true);
  }

  @Override
  protected void restartVideo(boolean saveTrackSelections) {
    // First, resets mSeekOnPlay to 0 in case the MediaPlayer object is (being) released.
    // This ensures the video to be started at its beginning position the next time it resumes.
    mSeekOnPlay = 0;
    if (mMediaPlayer != null) {
      if (saveTrackSelections) {
        saveTrackSelections();
      }
      // Not clear the $FLAG_VIDEO_DURATION_DETERMINED flag
      mInternalFlags &= ~($FLAG_VIDEO_PAUSED_BY_USER
          | $FLAG_CAN_GET_ACTUAL_POSITION_FROM_PLAYER
          | $FLAG_SEEKING
          | $FLAG_BUFFERING);
      // Resets below to prepare for the next resume of the video player
      mPlaybackSpeed = DEFAULT_PLAYBACK_SPEED;
      mBuffering = 0;
      pause(false);
      stopVideo();
      startVideo();
    }
  }

  @Override
  public void play(boolean fromUser) {
    final int playbackState = getPlaybackState();

    if (mMediaPlayer == null) {
      // Opens the video only if this is a user request
      if (fromUser) {
        // If the video playback finished, skip to the next video if possible
        if (playbackState == PLAYBACK_STATE_COMPLETED && !isSingleVideoLoopPlayback() &&
            skipToNextIfPossible() && mMediaPlayer != null) {
          return;
        }

        mInternalFlags &= ~$FLAG_VIDEO_PAUSED_BY_USER;
        openVideo(true);
      } else {
        Log.e(TAG, "Cannot start playback programmatically before the video is opened.");
      }
      return;
    }

    if (!fromUser && (mInternalFlags & $FLAG_VIDEO_PAUSED_BY_USER) != 0) {
      Log.e(TAG, "Cannot start playback programmatically after it was paused by user.");
      return;
    }

    switch (playbackState) {
      case PLAYBACK_STATE_IDLE: // no video is set
        // Already in the preparing or playing state
      case PLAYBACK_STATE_PREPARING:
      case PLAYBACK_STATE_PLAYING:
        break;

      case PLAYBACK_STATE_ERROR:
        // Retries the failed playback after error occurred
        mInternalFlags &= ~($FLAG_SEEKING | $FLAG_BUFFERING);
        mPlaybackSpeed = DEFAULT_PLAYBACK_SPEED;
        mBuffering = 0;
        if ((mInternalFlags & $FLAG_SEEK_POSITION_WHILE_VIDEO_PAUSED) == 0) {
          // Record the current playback position only if there is no external program code
          // requesting a position seek in this case.
          mSeekOnPlay = getVideoProgress();
        }
        saveTrackSelections();
        mInternalFlags &= ~$FLAG_CAN_GET_ACTUAL_POSITION_FROM_PLAYER;
        stopVideo();
        startVideo();
        break;

      case PLAYBACK_STATE_COMPLETED:
        if (!isSingleVideoLoopPlayback() &&
            skipToNextIfPossible() && getPlaybackState() != PLAYBACK_STATE_COMPLETED) {
          break;
        }
        // Starts the video only if we have prepared it for the player
      case PLAYBACK_STATE_PREPARED:
      case PLAYBACK_STATE_PAUSED:
        //@formatter:off
        final int result = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? mAudioManager.requestAudioFocus(mAudioFocusRequest)
            : mAudioManager.requestAudioFocus(mOnAudioFocusChangeListener,
                AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        //@formatter:on
        switch (result) {
          case AudioManager.AUDIOFOCUS_REQUEST_FAILED:
            if (InternalConsts.DEBUG) {
              Log.w(TAG, "Failed to request audio focus");
            }
            // Starts to play video even if the audio focus is not gained, but it is
            // best not to happen.
          case AudioManager.AUDIOFOCUS_REQUEST_GRANTED:
            mMediaPlayer.start();
            // Position seek each time works correctly only if the player engine is started
            if (mSeekOnPlay != 0) {
              seekToInternal(mSeekOnPlay);
              mSeekOnPlay = 0;
            }
            if (mUserPlaybackSpeed != mPlaybackSpeed) {
              setPlaybackSpeedInternal(mUserPlaybackSpeed);
            }
            // Ensure the player's volume is at its maximum
            if ((mInternalFlags & $FLAG_VIDEO_VOLUME_TURNED_DOWN_AUTOMATICALLY) != 0) {
              mInternalFlags &= ~$FLAG_VIDEO_VOLUME_TURNED_DOWN_AUTOMATICALLY;
              mMediaPlayer.setVolume(1.0f, 1.0f);
            }
            mInternalFlags &= ~$FLAG_VIDEO_PAUSED_BY_USER;
            mInternalFlags |= $FLAG_CAN_GET_ACTUAL_POSITION_FROM_PLAYER;
            onVideoStarted();

            // Register MediaButtonEventReceiver every time the video starts, which
            // will ensure it to be the sole receiver of MEDIA_BUTTON intents
            mAudioManager.registerMediaButtonEventReceiver(sMediaButtonEventReceiverComponent);
            break;

          case AudioManager.AUDIOFOCUS_REQUEST_DELAYED:
            // do nothing
            break;
        }
        break;
    }
  }

  @Override
  public void pause(boolean fromUser) {
    if (isPlaying()) {
      pauseInternal(fromUser);
    }
  }

  /**
   * Similar to {@link #pause(boolean)}}, but does not check the playback state.
   */
  private void pauseInternal(boolean fromUser) {
    mMediaPlayer.pause();
    mInternalFlags = mInternalFlags & ~$FLAG_VIDEO_PAUSED_BY_USER
        | (fromUser ? $FLAG_VIDEO_PAUSED_BY_USER : 0);
    onVideoStopped();
  }

  @Override
  protected void closeVideoInternal(boolean fromUser) {
    if (mMediaPlayer != null) {
      final boolean playing = isPlaying();

      if (getPlaybackState() != PLAYBACK_STATE_COMPLETED) {
        mSeekOnPlay = getVideoProgress();
      }
      saveTrackSelections();
//      pause(fromUser);
      if (playing) {
        mMediaPlayer.pause();
        mInternalFlags = mInternalFlags & ~$FLAG_VIDEO_PAUSED_BY_USER
            | (fromUser ? $FLAG_VIDEO_PAUSED_BY_USER : 0);
      }
      mMediaPlayer.stop();
      mMediaPlayer.release();
      mMediaPlayer = null;
      // Not clear the $FLAG_VIDEO_DURATION_DETERMINED flag
      mInternalFlags &= ~($FLAG_VIDEO_VOLUME_TURNED_DOWN_AUTOMATICALLY
          | $FLAG_SEEKING
          | $FLAG_BUFFERING
          | $FLAG_CAN_GET_ACTUAL_POSITION_FROM_PLAYER);
      // Resets below to prepare for the next resume of the video player
      mPlaybackSpeed = DEFAULT_PLAYBACK_SPEED;
      mBuffering = 0;

      abandonAudioFocus();
      mHeadsetEventsReceiver.unregister();
      mHeadsetEventsReceiver = null;

      if (playing) {
        onVideoStopped();
      }

      if (mVideoView != null) {
        mVideoView.showSubtitles(null);
        mVideoView.showLoadingView(false);
      }
    }
    if (mVideoView != null) {
      mVideoView.cancelDraggingVideoSeekBar();
    }
  }

  private void abandonAudioFocus() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      mAudioManager.abandonAudioFocusRequest(mAudioFocusRequest);
    } else {
      mAudioManager.abandonAudioFocus(mOnAudioFocusChangeListener);
    }
  }

  @Override
  public void seekTo(int positionMs, boolean fromUser) {
    if (isPlaying()) {
      seekToInternal(positionMs);
    } else {
      mInternalFlags |= $FLAG_SEEK_POSITION_WHILE_VIDEO_PAUSED;
      mSeekOnPlay = positionMs;
      play(fromUser);
      mInternalFlags &= ~$FLAG_SEEK_POSITION_WHILE_VIDEO_PAUSED;
    }
  }

  /**
   * Similar to {@link #seekTo(int, boolean)}, but without check to the playing state.
   */
  private void seekToInternal(int positionMs) {
    // Unable to seek while streaming live content
    if (mVideoDuration != NO_DURATION) {
      if ((mInternalFlags & $FLAG_BUFFERING) == 0) {
        if (mVideoView != null)
          mVideoView.showLoadingView(true);
      }
      mInternalFlags |= $FLAG_SEEKING;
      positionMs = clampedPositionMs(positionMs);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        // Precise seek with larger performance overhead compared to the default one.
        // Slow! Really slow!
        mMediaPlayer.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST);
      } else {
        mMediaPlayer.seekTo(positionMs /*, MediaPlayer.SEEK_PREVIOUS_SYNC*/);
      }
    }
  }

  @Override
  public int getVideoProgress() {
    return clampedPositionMs(getVideoProgress0());
  }

  private int getVideoProgress0() {
    if (getPlaybackState() == PLAYBACK_STATE_COMPLETED) {
      // 1. If the video completed and the MediaPlayer object was released, we would get 0.
      // 2. The playback position from the MediaPlayer, usually, is not the duration of the video
      // but the position at the last video key-frame when the playback is finished, in the
      // case of which instead, here is the duration returned to avoid progress inconsistencies.
      return mVideoDuration;
    }
    if ((mInternalFlags & $FLAG_CAN_GET_ACTUAL_POSITION_FROM_PLAYER) != 0) {
      return mMediaPlayer.getCurrentPosition();
    }
    return mSeekOnPlay;
  }

  @Override
  public int getVideoBufferProgress() {
    return clampedPositionMs(mBuffering);
  }

  @SuppressLint("MissingSuperCall")
  @RestrictTo(RestrictTo.Scope.LIBRARY)
  @Override
  public void setPlaybackSpeed(float speed) {
    if (canSetPlaybackSpeed(speed)) {
      mUserPlaybackSpeed = speed;
      // When video is not playing or has no tendency of to be started, we prefer recording
      // the user request to forcing the player to start at that given speed.
      if (getPlaybackState() == PLAYBACK_STATE_PREPARING || isPlaying()) {
        setPlaybackSpeedInternal(speed);
      }
    }
  }

  /**
   * Similar to {@link #setPlaybackSpeed(float)}, but without check to the playback state.
   */
  private void setPlaybackSpeedInternal(float speed) {
    if (canSetPlaybackSpeed(speed)) {
      PlaybackParams pp = mMediaPlayer.getPlaybackParams().allowDefaults();
      pp.setSpeed(speed);
      try {
        mMediaPlayer.setPlaybackParams(pp);
      } catch (IllegalArgumentException e) {
        e.printStackTrace();
        mUserPlaybackSpeed = mPlaybackSpeed;
        return;
      }
      // If the above fails due to an unsupported playback speed, then our speed will
      // remain unchanged. This ensures the program runs steadily.
      mUserPlaybackSpeed = speed;
      super.setPlaybackSpeed(speed);
    }
  }

  private boolean canSetPlaybackSpeed(float speed) {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && speed != mPlaybackSpeed;
  }

//  @Override
//  public void setSingleVideoLoopPlayback(boolean looping) {
//    if (looping != isSingleVideoLoopPlayback()) {
//      if (mMediaPlayer != null) {
//        mMediaPlayer.setLooping(looping);
//      }
//      super.setSingleVideoLoopPlayback(looping);
//    }
//  }

  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  private boolean supportTrackSelection() {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN && mMediaPlayer != null;
  }

  @SuppressLint("SwitchIntDef")
  @NonNull
  @Override
  public TrackInfo[] getTrackInfos() {
    if (!supportTrackSelection()) return EMPTY_TRACK_INFOS;

    try {
      //@throws IllegalStateException if it is called in an invalid state.
      MediaPlayer.TrackInfo[] mediaTrackInfos = mMediaPlayer.getTrackInfo();
      if (mediaTrackInfos.length == 0) {
        return EMPTY_TRACK_INFOS;
      }

      List<TrackInfo> trackInfos = new LinkedList<>();
      for (MediaPlayer.TrackInfo mediaTrackInfo : mediaTrackInfos) {
        TrackInfo trackInfo;
        switch (mediaTrackInfo.getTrackType()) {
          case MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO: {
            MediaFormat mediaFormat = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
              mediaFormat = mediaTrackInfo.getFormat();
            }
            if (mediaFormat == null) {
              trackInfo = new VideoTrackInfo();
            } else {
              trackInfo = new VideoTrackInfo(
                  null, // FIXME: to get video codec name
                  mediaFormat.getInteger(MediaFormat.KEY_WIDTH),
                  mediaFormat.getInteger(MediaFormat.KEY_HEIGHT),
                  mediaFormat.getFloat(MediaFormat.KEY_FRAME_RATE),
                  mediaFormat.getInteger(MediaFormat.KEY_BIT_RATE));
            }
            break;
          }
          case MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO: {
            MediaFormat mediaFormat = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
              mediaFormat = mediaTrackInfo.getFormat();
            }
            if (mediaFormat == null) {
              trackInfo = new AudioTrackInfo();
              ((AudioTrackInfo) trackInfo).language = mediaTrackInfo.getLanguage();
            } else {
              trackInfo = new AudioTrackInfo(
                  null, // FIXME: to get audio codec name
                  mediaTrackInfo.getLanguage(),
                  mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                  mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                  mediaFormat.getInteger(MediaFormat.KEY_BIT_RATE));
            }
            break;
          }
          case MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT:
            trackInfo = new SubtitleTrackInfo(mediaTrackInfo.getLanguage());
            break;
          default:
            continue;
        }
        trackInfos.add(trackInfo);
      }
      return trackInfos.toArray(new TrackInfo[trackInfos.size()]);
    } catch (RuntimeException ignored) {
      return EMPTY_TRACK_INFOS;
    }
  }

  @Override
  public void selectTrack(int index) {
    if (!supportTrackSelection()) return;

    try {
      index = externalIndexToLibIndex(index);
      if (index >= 0) {
        // If a MediaPlayer is in invalid state, it throws an IllegalStateException exception.
        // If a MediaPlayer is in <em>Started</em> state, the selected track is presented immediately.
        // If a MediaPlayer is not in Started state, it just marks the track to be played.
        //
        // Currently, only timed text, subtitle or audio tracks can be selected via this method.
        //
        //@throws IllegalStateException if called in an invalid state.
        mMediaPlayer.selectTrack(index);
      }
    } catch (RuntimeException ignored) {
      //
    }
  }

  @Override
  public void deselectTrack(int index) {
    if (!supportTrackSelection()) return;

    try {
      index = externalIndexToLibIndex(index);
      if (index >= 0) {
        // Currently, the track must be a timed text track and no audio or video tracks can be
        // deselected.
        //
        //@throws IllegalStateException if called in an invalid state.
        mMediaPlayer.deselectTrack(index);
      }
    } catch (RuntimeException ignored) {
      //
    }
  }

  @SuppressLint("SwitchIntDef")
  @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
  private int externalIndexToLibIndex(int externalIndex) {
    if (externalIndex >= 0) {
      //@throws IllegalStateException if it is called in an invalid state.
      MediaPlayer.TrackInfo[] mediaTrackInfos = mMediaPlayer.getTrackInfo();
      if (mediaTrackInfos.length == 0) {
        return -1;
      }

      int usableTrackCount = 0;
      for (int i = 0; i < mediaTrackInfos.length; i++) {
        switch (mediaTrackInfos[i].getTrackType()) {
          case MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO:
          case MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO:
          case MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT:
            usableTrackCount++;
            break;
        }
        if (usableTrackCount > externalIndex) {
          return i;
        }
      }
    }
    return -1;
  }

  @SuppressLint("SwitchIntDef")
  @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
  private int libIndexToExternalIndex(int libIndex) {
    if (libIndex >= 0) {
      //@throws IllegalStateException if it is called in an invalid state.
      MediaPlayer.TrackInfo[] mediaTrackInfos = mMediaPlayer.getTrackInfo();
      if (mediaTrackInfos.length == 0) {
        return -1;
      }

      int externalIndex = -1;
      for (int i = 0; i < mediaTrackInfos.length; i++) {
        switch (mediaTrackInfos[i].getTrackType()) {
          case MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO:
          case MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO:
          case MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT:
            externalIndex++;
            break;
        }
        if (i == libIndex) {
          return externalIndex;
        }
      }
    }
    return -1;
  }

  @Override
  public int getSelectedTrackIndex(int trackType) {
    if (!supportTrackSelection()) return INVALID_TRACK_INDEX;

    int index = -1;
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        switch (trackType) {
          case TrackInfo.TRACK_TYPE_VIDEO:
            index = mMediaPlayer.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO);
            break;
          case TrackInfo.TRACK_TYPE_AUDIO:
            index = mMediaPlayer.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO);
            break;
          case TrackInfo.TRACK_TYPE_SUBTITLE:
            index = mMediaPlayer.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
            break;
        }
        if (index >= 0) {
          index = libIndexToExternalIndex(index);
        }
      } else {
        // TODO: support for getting the index of the video, audio, or subtitle track
        //       currently selected for playback on platform versions 16–20.
      }
    } catch (RuntimeException ignored) {
      index = -1;
    }
    return index >= 0 ? index : INVALID_TRACK_INDEX;
  }

  @Override
  protected boolean onPlaybackCompleted() {
    final boolean closed = super.onPlaybackCompleted();
    if (closed) {
      // Since the playback completion state deters the pause(boolean) method from being called
      // within the closeVideoInternal(boolean) method, we need this extra step to add
      // the $FLAG_VIDEO_PAUSED_BY_USER flag into mInternalFlags to denote that the user pauses
      // (closes) the video.
      mInternalFlags |= $FLAG_VIDEO_PAUSED_BY_USER;
      onVideoStopped();
    }
    return closed;
  }
}