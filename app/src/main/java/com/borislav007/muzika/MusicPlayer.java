package com.borislav007.muzika;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.util.VLCVideoLayout;

/**
 * App-wide player with playlist, shuffle, and repeat support.
 * Uses Android MediaPlayer for common audio and LibVLC for WMA and video.
 */
final class MusicPlayer {
    interface Listener {
        void onPrepared();

        void onTrackChanged(PlayableTrack track);

        void onPlaylistFinished();

        void onError(String message);

        /** True when the current item should show a video surface. */
        void onVideoAvailabilityChanged(boolean hasVideo);
    }

    private static MusicPlayer instance;

    private android.media.MediaPlayer mediaPlayer;
    private LibVLC libVlc;
    private org.videolan.libvlc.MediaPlayer vlcPlayer;
    private VLCVideoLayout videoLayout;
    private boolean usingVlc;
    private boolean playingVideo;
    private boolean videoViewsAttached;
    private int lastVoutCount = -1;
    /** Positive video-output events since the picture surface was last attached. */
    private int voutSinceRebind;
    private long videoSurfaceReboundAt;
    /** True while the activity has left the screen and the picture surface is off. */
    private boolean videoSurfaceSuspended;
    /** Video is waiting for a sized surface before the first frame is decoded. */
    private boolean videoStartPending;
    private final Runnable startVideoFallback = this::startVideoDespiteSurface;
    private final Runnable confirmVideoGone = this::confirmVideoGone;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AssetFileDescriptor vlcAfd;
    private ParcelFileDescriptor vlcPfd;
    private File vlcTempFile;

    private Context appContext;
    private PlayableTrack currentTrack;
    private Listener listener;
    private boolean prepared;

    private final List<PlayableTrack> libraryTracks = new ArrayList<>();
    private final List<PlayableTrack> playQueue = new ArrayList<>();
    private int queueIndex = -1;

    private PlaybackMediaSession mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean hasAudioFocus;
    private boolean becomingNoisyRegistered;

    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener =
            focusChange -> {
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS
                        || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    // Another app took audio. Stay paused until the user presses Play.
                    pause();
                    notifyMediaSessionPlayPause();
                }
            };

    /** Pause when headset / Bluetooth A2DP disconnects so audio does not continue on speaker. */
    private final BroadcastReceiver becomingNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null
                    && AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())
                    && isPlaying()) {
                pause();
            }
        }
    };

    static synchronized MusicPlayer get() {
        if (instance == null) {
            instance = new MusicPlayer();
        }
        return instance;
    }

    private MusicPlayer() {
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    PlayableTrack getCurrentTrack() {
        return currentTrack;
    }

    /** True while the current item is being shown as video. */
    boolean isVideoShown() {
        return playingVideo;
    }

    /** True when the selected file is a video, even while it is still loading. */
    boolean isCurrentTrackVideo() {
        return currentTrack != null && currentTrack.isVideo();
    }

    void attachVideoLayout(VLCVideoLayout layout) {
        videoSurfaceSuspended = false;
        if (currentTrack != null && currentTrack.isVideo()) {
            playingVideo = true;
        }
        videoLayout = layout;
        bindVideoOutput();
    }

    /** The activity is going to the background. Keep the file as video. */
    void suspendVideoOutput() {
        videoSurfaceSuspended = true;
        mainHandler.removeCallbacks(confirmVideoGone);
        if (vlcPlayer != null && videoViewsAttached) {
            try {
                vlcPlayer.detachViews();
            } catch (Exception ignored) {
            }
        }
        videoViewsAttached = false;
        videoLayout = null;
    }

    boolean isVideoSurfaceAttached() {
        return videoViewsAttached;
    }

    void detachVideoLayout(VLCVideoLayout layout) {
        if (layout != null && videoLayout != null && layout != videoLayout) {
            return;
        }
        mainHandler.removeCallbacks(confirmVideoGone);
        if (vlcPlayer != null && videoViewsAttached) {
            try {
                vlcPlayer.detachViews();
            } catch (Exception ignored) {
            }
        }
        videoViewsAttached = false;
        if (layout == null || layout == videoLayout) {
            videoLayout = null;
        }
    }

    /** Snapshot of the current play order (respects shuffle). */
    List<PlayableTrack> getQueueSnapshot() {
        return new ArrayList<>(playQueue);
    }

    int getQueueIndex() {
        return queueIndex;
    }

    int getQueueSize() {
        return playQueue.size();
    }

    /** True when a queue/track is already loaded (e.g. playing under the media notification). */
    boolean hasRestorableSession() {
        return currentTrack != null
                && currentTrack.uri != null
                && !playQueue.isEmpty();
    }

    /** Jump to a track in the current queue and start playback. */
    void playAtQueueIndex(int index) {
        if (index < 0 || index >= playQueue.size()) {
            return;
        }
        queueIndex = index;
        playCurrent();
    }

    /** Sort the play queue by filename; keeps the current track as current. */
    void sortQueueByName(boolean ascending) {
        if (playQueue.isEmpty()) {
            return;
        }
        PlayableTrack keep = currentTrack;
        final int dir = ascending ? 1 : -1;
        Collections.sort(playQueue, (a, b) -> dir * compareText(a.displayName, b.displayName));
        queueIndex = keep != null ? indexOfTrack(playQueue, keep) : 0;
        if (queueIndex < 0) {
            queueIndex = 0;
        }
    }

    /**
     * Sort the play queue by artist; if artist is missing, use album.
     * Keeps the current track as current.
     */
    void sortQueueByAuthor(boolean ascending) {
        if (playQueue.isEmpty()) {
            return;
        }
        PlayableTrack keep = currentTrack;
        final int dir = ascending ? 1 : -1;
        Collections.sort(playQueue, (a, b) -> {
            int byKey = dir * compareText(artistOrAlbumKey(a), artistOrAlbumKey(b));
            if (byKey != 0) {
                return byKey;
            }
            return dir * compareText(a.displayName, b.displayName);
        });
        queueIndex = keep != null ? indexOfTrack(playQueue, keep) : 0;
        if (queueIndex < 0) {
            queueIndex = 0;
        }
    }

    /**
     * Sort by release year/TYER, else file date. {@code newestFirst} true = Newest.
     * Keeps the current track as current.
     */
    void sortQueueByReleaseDate(boolean newestFirst) {
        if (playQueue.isEmpty()) {
            return;
        }
        PlayableTrack keep = currentTrack;
        final int dir = newestFirst ? -1 : 1;
        Collections.sort(playQueue, (a, b) -> {
            long keyA = a != null ? a.releaseSortKey() : 0L;
            long keyB = b != null ? b.releaseSortKey() : 0L;
            int byDate = Long.compare(keyA, keyB);
            if (byDate != 0) {
                return dir * byDate;
            }
            return compareText(
                    a != null ? a.displayName : "",
                    b != null ? b.displayName : "");
        });
        queueIndex = keep != null ? indexOfTrack(playQueue, keep) : 0;
        if (queueIndex < 0) {
            queueIndex = 0;
        }
    }

    private static String artistOrAlbumKey(PlayableTrack track) {
        if (track == null) {
            return "";
        }
        String artist = usableMeta(track.artist);
        if (!artist.isEmpty()) {
            return artist;
        }
        return usableMeta(track.album);
    }

    private static String usableMeta(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "<unknown>".equalsIgnoreCase(trimmed)
                || "unknown".equalsIgnoreCase(trimmed)) {
            return "";
        }
        return trimmed;
    }

    private static int compareText(String left, String right) {
        String a = left != null ? left.trim() : "";
        String b = right != null ? right.trim() : "";
        if (a.isEmpty() && b.isEmpty()) {
            return 0;
        }
        if (a.isEmpty()) {
            return 1;
        }
        if (b.isEmpty()) {
            return -1;
        }
        return a.compareToIgnoreCase(b);
    }

    PlaybackMediaSession mediaSessionOrNull() {
        return mediaSession;
    }

    void notifyMediaSessionPlayPause() {
        if (mediaSession != null) {
            mediaSession.onPlayPauseChanged();
        }
    }

    private void ensureMediaSession() {
        if (appContext == null) {
            return;
        }
        if (mediaSession == null) {
            mediaSession = new PlaybackMediaSession(appContext);
        }
        if (audioManager == null) {
            audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        }
        ensureBecomingNoisyReceiver();
    }

    private void ensureBecomingNoisyReceiver() {
        if (becomingNoisyRegistered || appContext == null) {
            return;
        }
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(becomingNoisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            appContext.registerReceiver(becomingNoisyReceiver, filter);
        }
        becomingNoisyRegistered = true;
    }

    private void unregisterBecomingNoisyReceiver() {
        if (!becomingNoisyRegistered || appContext == null) {
            return;
        }
        try {
            appContext.unregisterReceiver(becomingNoisyReceiver);
        } catch (Exception ignored) {
        }
        becomingNoisyRegistered = false;
    }

    private boolean requestAudioFocus() {
        ensureMediaSession();
        if (audioManager == null) {
            return true;
        }
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();
                audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs)
                        .setOnAudioFocusChangeListener(audioFocusChangeListener)
                        .build();
            }
            result = audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            result = audioManager.requestAudioFocus(audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return hasAudioFocus;
    }

    private void abandonAudioFocus() {
        if (audioManager == null || !hasAudioFocus) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(audioFocusChangeListener);
        }
        hasAudioFocus = false;
    }

    boolean isPlaying() {
        if (!prepared) {
            return false;
        }
        if (usingVlc) {
            return vlcPlayer != null && vlcPlayer.isPlaying();
        }
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    boolean isPrepared() {
        if (usingVlc) {
            return vlcPlayer != null && prepared;
        }
        return mediaPlayer != null && prepared;
    }

    int getDurationMs() {
        if (!prepared) {
            return 0;
        }
        try {
            if (usingVlc) {
                if (vlcPlayer == null) {
                    return 0;
                }
                long length = vlcPlayer.getLength();
                return length > 0 ? (int) length : 0;
            }
            return mediaPlayer != null ? mediaPlayer.getDuration() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    int getPositionMs() {
        if (!prepared) {
            return 0;
        }
        try {
            if (usingVlc) {
                if (vlcPlayer == null) {
                    return 0;
                }
                long time = vlcPlayer.getTime();
                return time > 0 ? (int) time : 0;
            }
            return mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Play {@code selected}, then the rest of tracks in the same folder key.
     */
    void playTrackInFolder(Context context, PlayableTrack selected, List<PlayableTrack> folderTracks) {
        if (selected == null || selected.uri == null) {
            notifyError("File not found");
            return;
        }
        appContext = context.getApplicationContext();
        libraryTracks.clear();
        if (folderTracks == null || folderTracks.isEmpty()) {
            libraryTracks.add(selected);
        } else {
            libraryTracks.addAll(folderTracks);
        }
        rebuildQueueFrom(selected);
        playCurrent();
    }

    /**
     * Play a prepared list, optionally starting at {@code startTrack}.
     */
    void playTracks(Context context, List<PlayableTrack> tracks, PlayableTrack startTrack) {
        if (tracks == null || tracks.isEmpty()) {
            notifyError(context.getString(R.string.folder_no_music));
            return;
        }
        appContext = context.getApplicationContext();
        libraryTracks.clear();
        libraryTracks.addAll(tracks);
        if (startTrack == null || indexOfTrack(libraryTracks, startTrack) < 0) {
            boolean shuffle = PlaybackPrefs.isShuffleEnabled(appContext);
            playQueue.clear();
            if (shuffle) {
                playQueue.addAll(libraryTracks);
                Collections.shuffle(playQueue);
            } else {
                playQueue.addAll(libraryTracks);
            }
            queueIndex = 0;
        } else {
            rebuildQueueFrom(startTrack);
        }
        playCurrent();
    }

    void onShuffleModeChanged() {
        if (currentTrack == null || libraryTracks.isEmpty()) {
            return;
        }
        rebuildQueueFrom(currentTrack);
    }

    private void rebuildQueueFrom(PlayableTrack selected) {
        playQueue.clear();
        boolean shuffle = appContext != null && PlaybackPrefs.isShuffleEnabled(appContext);
        PlayableTrack selectedTrack = selected;
        int existing = indexOfTrack(libraryTracks, selected);
        if (existing >= 0) {
            selectedTrack = libraryTracks.get(existing);
        }

        if (shuffle) {
            List<PlayableTrack> shuffled = new ArrayList<>(libraryTracks);
            if (indexOfTrack(shuffled, selectedTrack) < 0) {
                shuffled.add(selectedTrack);
            }
            Collections.shuffle(shuffled);
            playQueue.addAll(shuffled);
            int selectedIndex = indexOfTrack(playQueue, selectedTrack);
            queueIndex = selectedIndex >= 0 ? selectedIndex : 0;
        } else {
            playQueue.addAll(libraryTracks);
            if (indexOfTrack(playQueue, selectedTrack) < 0) {
                playQueue.add(selectedTrack);
            }
            if (playQueue.isEmpty()) {
                playQueue.add(selectedTrack);
            }
            int selectedIndex = indexOfTrack(playQueue, selectedTrack);
            queueIndex = selectedIndex >= 0 ? selectedIndex : 0;
        }
    }

    private static int indexOfTrack(List<PlayableTrack> tracks, PlayableTrack target) {
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).equals(target)) {
                return i;
            }
        }
        return -1;
    }

    private void rebuildQueueForRepeatCycle() {
        playQueue.clear();
        boolean shuffle = appContext != null && PlaybackPrefs.isShuffleEnabled(appContext);
        if (shuffle) {
            playQueue.addAll(libraryTracks);
            Collections.shuffle(playQueue);
        } else {
            playQueue.addAll(libraryTracks);
        }
        queueIndex = 0;
    }

    private void playCurrent() {
        if (queueIndex < 0 || queueIndex >= playQueue.size()) {
            notifyPlaylistFinished();
            return;
        }
        playTrackInternal(playQueue.get(queueIndex));
    }

    boolean hasPrevious() {
        return queueIndex > 0;
    }

    boolean hasNext() {
        return queueIndex >= 0 && queueIndex + 1 < playQueue.size();
    }

    void playPrevious() {
        if (!hasPrevious()) {
            return;
        }
        queueIndex--;
        playCurrent();
    }

    void playNext() {
        if (!hasNext()) {
            return;
        }
        queueIndex++;
        playCurrent();
    }

    private void playNextOrFinish() {
        if (queueIndex + 1 < playQueue.size()) {
            queueIndex++;
            playCurrent();
            return;
        }
        boolean repeat = appContext != null && PlaybackPrefs.isRepeatEnabled(appContext);
        if (repeat && !libraryTracks.isEmpty()) {
            rebuildQueueForRepeatCycle();
            playCurrent();
            return;
        }
        notifyPlaylistFinished();
    }

    private void playTrackInternal(PlayableTrack track) {
        if (track == null || track.uri == null) {
            notifyError("File not found");
            return;
        }
        ensureMediaSession();
        requestAudioFocus();
        releasePlayerOnly();
        currentTrack = track;
        prepared = false;
        if (mediaSession != null) {
            mediaSession.onTrackChanged(track);
        }
        if (listener != null) {
            listener.onTrackChanged(track);
        }
        if (track.needsVlc()) {
            playWithVlc(track);
        } else {
            playWithMediaPlayer(track);
        }
    }

    private void playWithMediaPlayer(PlayableTrack track) {
        usingVlc = false;
        try {
            mediaPlayer = new android.media.MediaPlayer();
            mediaPlayer.setDataSource(appContext, track.uri);
            mediaPlayer.setOnPreparedListener(mp -> {
                prepared = true;
                mp.start();
                if (mediaSession != null) {
                    mediaSession.onPrepared();
                }
                if (listener != null) {
                    listener.onPrepared();
                }
            });
            mediaPlayer.setOnCompletionListener(mp -> playNextOrFinish());
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                prepared = false;
                if (!track.needsVlc()) {
                    releasePlayerOnly();
                    playWithVlc(track);
                    return true;
                }
                playNextOrFinish();
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            releasePlayerOnly();
            playWithVlc(track);
        }
    }

    private void ensureLibVlc() {
        if (libVlc != null) {
            return;
        }
        ArrayList<String> options = new ArrayList<>();
        options.add("--aout=opensles");
        options.add("--audio-time-stretch");
        options.add("--network-caching=1000");
        libVlc = new LibVLC(appContext, options);
    }

    private void playWithVlc(PlayableTrack track) {
        usingVlc = true;
        try {
            ensureLibVlc();
            vlcPlayer = new org.videolan.libvlc.MediaPlayer(libVlc);
            Media media = openVlcMedia(track);
            if (media == null) {
                prepared = false;
                notifyError("Could not play this file");
                playNextOrFinish();
                return;
            }
            if (!prepareVideoOption(media, track)) {
                media.addOption(":no-video");
            }
            vlcPlayer.setMedia(media);
            media.release();
            vlcPlayer.setEventListener(event -> {
                if (event.type == org.videolan.libvlc.MediaPlayer.Event.Playing) {
                    if (!prepared) {
                        prepared = true;
                        if (mediaSession != null) {
                            mediaSession.onPrepared();
                        }
                        if (listener != null) {
                            listener.onPrepared();
                        }
                    } else if (mediaSession != null) {
                        mediaSession.onPlayPauseChanged();
                    }
                } else if (event.type == org.videolan.libvlc.MediaPlayer.Event.Vout) {
                    if (currentTrack != null && currentTrack.isVideo()) {
                        int count = event.getVoutCount();
                        mainHandler.post(() -> onVoutCount(count));
                    }
                } else if (event.type == org.videolan.libvlc.MediaPlayer.Event.EndReached) {
                    playNextOrFinish();
                } else if (event.type == org.videolan.libvlc.MediaPlayer.Event.EncounteredError) {
                    prepared = false;
                    notifyError("Could not play this file");
                    playNextOrFinish();
                }
            });
            if (playingVideo) {
                notifyVideoAvailability(true);
                bindVideoOutput();
            }
            // Decoding before the surface exists makes the first frames gray.
            startPlaybackWhenReady();
        } catch (Exception e) {
            prepared = false;
            notifyError("Could not play this file");
            playNextOrFinish();
        }
    }

    /**
     * Video files always open with a picture. A header parse that misses the video
     * track must not force album art for camera clips.
     */
    private boolean prepareVideoOption(Media media, PlayableTrack track) {
        if (media == null || track == null || !track.isVideo()) {
            playingVideo = false;
            return false;
        }
        playingVideo = true;
        // Direct rendering paints brightness only until the color planes attach,
        // so the opening seconds look gray. Copy the frames and convert them.
        media.addOption(":no-mediacodec-dr");
        return true;
    }

    /** Starts playback once a video surface is attached, or immediately for audio. */
    private void startPlaybackWhenReady() {
        if (vlcPlayer == null || vlcPlayer.isReleased()) {
            return;
        }
        if (playingVideo && !videoViewsAttached) {
            videoStartPending = true;
            mainHandler.removeCallbacks(startVideoFallback);
            mainHandler.postDelayed(startVideoFallback, 700);
            return;
        }
        videoStartPending = false;
        mainHandler.removeCallbacks(startVideoFallback);
        if (!vlcPlayer.isPlaying()) {
            vlcPlayer.play();
        }
    }

    /** Plays anyway if the picture surface never gets a size. */
    private void startVideoDespiteSurface() {
        if (!videoStartPending || vlcPlayer == null || vlcPlayer.isReleased()) {
            return;
        }
        bindVideoOutput();
        if (!videoStartPending) {
            return;
        }
        videoStartPending = false;
        if (!vlcPlayer.isPlaying()) {
            vlcPlayer.play();
        }
    }

    /**
     * Resizing or turning the picture surface makes LibVLC report zero video
     * outputs for a moment. Wait, and try once to attach the surface again,
     * before treating the file as audio-only.
     */
    private void onVoutCount(int count) {
        boolean pictureArrived = count > 0 && lastVoutCount <= 0;
        lastVoutCount = count;
        if (count > 0) {
            voutSinceRebind++;
        }
        mainHandler.removeCallbacks(confirmVideoGone);
        if (count > 0) {
            boolean wasVideo = playingVideo && videoViewsAttached;
            applyVoutCount(count);
            // The picture size is only known now; the screen must resize for it.
            if (pictureArrived && wasVideo) {
                notifyVideoAvailability(true);
            }
        } else {
            mainHandler.postDelayed(confirmVideoGone, 700);
        }
    }

    private void confirmVideoGone() {
        if (lastVoutCount > 0 || videoSurfaceSuspended || videoLayout == null) {
            return;
        }
        // A file selected from the video library remains a video even when a
        // SurfaceView is being recreated. A zero Vout event here is a surface
        // transition, not a reason to replace the picture with album art.
        if (currentTrack != null && currentTrack.isVideo()) {
            return;
        }
        if (voutSinceRebind > 0 && videoViewsAttached
                && currentTrack != null && currentTrack.isVideo()) {
            return;
        }
        if (SystemClock.uptimeMillis() - videoSurfaceReboundAt < 1500) {
            mainHandler.postDelayed(confirmVideoGone, 700);
            return;
        }
        applyVoutCount(0);
    }

    /** Attaches the picture again after the video view changes size or rotation. */
    void rebindVideoOutput() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::rebindVideoOutput);
            return;
        }
        if (vlcPlayer == null || vlcPlayer.isReleased() || videoLayout == null || !playingVideo) {
            return;
        }
        if (videoLayout.getWidth() <= 0 || videoLayout.getHeight() <= 0) {
            return;
        }
        mainHandler.removeCallbacks(confirmVideoGone);
        voutSinceRebind = 0;
        videoSurfaceReboundAt = SystemClock.uptimeMillis();
        if (videoViewsAttached) {
            try {
                vlcPlayer.detachViews();
            } catch (Exception ignored) {
            }
            videoViewsAttached = false;
        }
        bindVideoOutput();
        try {
            vlcPlayer.updateVideoSurfaces();
        } catch (Exception ignored) {
        }
    }

    private void applyVoutCount(int voutCount) {
        boolean hasVideo = voutCount > 0;
        if (hasVideo == playingVideo && (!hasVideo || videoViewsAttached)) {
            return;
        }
        playingVideo = hasVideo;
        if (hasVideo) {
            bindVideoOutput();
        } else if (vlcPlayer != null && videoViewsAttached) {
            try {
                vlcPlayer.detachViews();
            } catch (Exception ignored) {
            }
            videoViewsAttached = false;
        }
        notifyVideoAvailability(hasVideo);
    }

    /** Width/height of the picture, or null until the video track is known. */
    Float videoDisplayAspect() {
        int[] size = videoDisplaySize();
        if (size == null || size[1] <= 0) {
            return null;
        }
        return size[0] / (float) size[1];
    }

    /**
     * The activity already sized the video view to the picture's aspect ratio,
     * so LibVLC fills it exactly. Orientation comes from the view's own bounds
     * because the phone's orientation makes LibVLC swap width and height.
     */
    void fillVideoFrame() {
        if (vlcPlayer == null || vlcPlayer.isReleased()) {
            return;
        }
        vlcPlayer.setUseOrientationFromBounds(true);
        vlcPlayer.setScale(0f);
        // Until the picture size is known the view is not shaped like the
        // picture, so filling it would stretch the image.
        org.videolan.libvlc.MediaPlayer.ScaleType scale = videoDisplayAspect() != null
                ? org.videolan.libvlc.MediaPlayer.ScaleType.SURFACE_FILL
                : org.videolan.libvlc.MediaPlayer.ScaleType.SURFACE_BEST_FIT;
        if (vlcPlayer.getVideoScale() != scale) {
            vlcPlayer.setVideoScale(scale);
        } else {
            vlcPlayer.updateVideoSurfaces();
        }
    }

    /** Applies the current view bounds after the layout has been resized. */
    void updateVideoSurfaceSize(VLCVideoLayout layout) {
        if (vlcPlayer == null || vlcPlayer.isReleased() || !videoViewsAttached
                || layout == null || layout != videoLayout
                || layout.getWidth() <= 0 || layout.getHeight() <= 0) {
            return;
        }
        try {
            vlcPlayer.getVLCVout().setWindowSize(layout.getWidth(), layout.getHeight());
            vlcPlayer.updateVideoSurfaces();
        } catch (Exception ignored) {
        }
    }

    private int[] videoDisplaySize() {
        if (vlcPlayer == null || vlcPlayer.isReleased()) {
            return null;
        }
        IMedia.VideoTrack track = vlcPlayer.getCurrentVideoTrack();
        if (track == null || track.width <= 0 || track.height <= 0) {
            return null;
        }
        boolean swapped = track.orientation == IMedia.VideoTrack.Orientation.LeftBottom
                || track.orientation == IMedia.VideoTrack.Orientation.RightTop;
        int videoWidth = swapped ? track.height : track.width;
        int videoHeight = swapped ? track.width : track.height;
        if (track.sarDen > 0 && track.sarNum != track.sarDen) {
            videoWidth = videoWidth * track.sarNum / track.sarDen;
        }
        if (videoWidth <= 0 || videoHeight <= 0) {
            return null;
        }
        return new int[] { videoWidth, videoHeight };
    }

    private void bindVideoOutput() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::bindVideoOutput);
            return;
        }
        if (vlcPlayer == null || videoLayout == null || !playingVideo || videoViewsAttached) {
            return;
        }
        if (videoLayout.getWidth() <= 0 || videoLayout.getHeight() <= 0) {
            return;
        }
        try {
            // SurfaceView keeps the picture at the right brightness. TextureView
            // composites the first frames too dark, then corrects them later.
            // The subtitle surface is a second layer that stays gray and
            // translucent until LibVLC clears it, so the opening seconds look
            // faint. Subtitles are drawn into the picture instead.
            vlcPlayer.attachViews(videoLayout, null, false, false);
            videoViewsAttached = true;
            vlcPlayer.getVLCVout().setWindowSize(videoLayout.getWidth(), videoLayout.getHeight());
            vlcPlayer.updateVideoSurfaces();
            if (videoStartPending) {
                startPlaybackWhenReady();
            }
        } catch (Exception ignored) {
            videoViewsAttached = false;
        }
    }

    private void notifyVideoAvailability(boolean hasVideo) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> notifyVideoAvailability(hasVideo));
            return;
        }
        if (listener != null) {
            listener.onVideoAvailabilityChanged(hasVideo);
        }
    }

    /**
     * LibVLC often fails on bare content:// URIs. Prefer AssetFileDescriptor / FD,
     * then fall back to a temp file copy (needed for some SAF providers).
     */
    private Media openVlcMedia(PlayableTrack track) throws IOException {
        releaseVlcSources();
        Uri uri = track != null ? track.uri : null;
        if (uri == null) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme != null && scheme.equalsIgnoreCase("file")) {
            String path = uri.getPath();
            if (path != null && new File(path).exists()) {
                return new Media(libVlc, path);
            }
            return new Media(libVlc, uri);
        }

        try {
            vlcAfd = appContext.getContentResolver().openAssetFileDescriptor(uri, "r");
            if (vlcAfd != null) {
                return new Media(libVlc, vlcAfd);
            }
        } catch (Exception ignored) {
            closeQuietly(vlcAfd);
            vlcAfd = null;
        }

        try {
            vlcPfd = appContext.getContentResolver().openFileDescriptor(uri, "r");
            if (vlcPfd != null) {
                return new Media(libVlc, vlcPfd.getFileDescriptor());
            }
        } catch (Exception ignored) {
            closeQuietly(vlcPfd);
            vlcPfd = null;
        }

        vlcTempFile = copyUriToCache(uri, track);
        if (vlcTempFile != null) {
            return new Media(libVlc, vlcTempFile.getAbsolutePath());
        }
        return null;
    }

    private File copyUriToCache(Uri uri, PlayableTrack track) {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = appContext.getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            String name = "vlc_play_tmp";
            String path = uri.getLastPathSegment();
            if (path != null) {
                int slash = path.lastIndexOf('/');
                String leaf = slash >= 0 ? path.substring(slash + 1) : path;
                if (leaf.toLowerCase(Locale.US).endsWith(".wma")
                        || leaf.toLowerCase(Locale.US).endsWith(".asf")) {
                    name = leaf.replace(':', '_');
                } else {
                    int dot = leaf.lastIndexOf('.');
                    String ext = dot >= 0 ? leaf.substring(dot + 1).toLowerCase(Locale.US) : "";
                    if (isVideoExtension(ext) || supportedExtensions().contains(ext)) {
                        name = "vlc_play_tmp." + ext;
                    } else if (track != null && track.isVideo()) {
                        name = "vlc_play_tmp.mp4";
                    } else {
                        name = "vlc_play_tmp.wma";
                    }
                }
            }
            File file = new File(appContext.getCacheDir(), name);
            out = new FileOutputStream(file);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            return file;
        } catch (Exception e) {
            return null;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private void releaseVlcSources() {
        closeQuietly(vlcAfd);
        vlcAfd = null;
        closeQuietly(vlcPfd);
        vlcPfd = null;
        if (vlcTempFile != null) {
            //noinspection ResultOfMethodCallIgnored
            vlcTempFile.delete();
            vlcTempFile = null;
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    void play() {
        if (!prepared) {
            return;
        }
        requestAudioFocus();
        if (usingVlc) {
            // LibVLC ignores play() once the file has ended; open it again.
            if (vlcPlayer != null && vlcPlayer.getPlayerState() == IMedia.State.Ended) {
                playCurrent();
                return;
            }
            if (vlcPlayer != null && !vlcPlayer.isPlaying()) {
                vlcPlayer.play();
            }
        } else if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
        }
        notifyMediaSessionPlayPause();
    }

    void pause() {
        if (!prepared) {
            return;
        }
        if (usingVlc) {
            if (vlcPlayer != null && vlcPlayer.isPlaying()) {
                vlcPlayer.pause();
            }
        } else if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        }
        // Release focus so the system does not hand it back and restart playback.
        abandonAudioFocus();
        notifyMediaSessionPlayPause();
    }

    void seekTo(int positionMs) {
        if (!prepared) {
            return;
        }
        int safe = Math.max(0, positionMs);
        if (usingVlc) {
            if (vlcPlayer != null) {
                vlcPlayer.setTime(safe);
            }
        } else if (mediaPlayer != null) {
            mediaPlayer.seekTo(safe);
        }
        notifyMediaSessionPlayPause();
    }

    void releasePlayerOnly() {
        prepared = false;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Exception ignored) {
            }
            mediaPlayer = null;
        }
        if (vlcPlayer != null) {
            try {
                vlcPlayer.stop();
                vlcPlayer.detachViews();
                vlcPlayer.release();
            } catch (Exception ignored) {
            }
            vlcPlayer = null;
        }
        videoViewsAttached = false;
        videoStartPending = false;
        playingVideo = false;
        lastVoutCount = -1;
        voutSinceRebind = 0;
        mainHandler.removeCallbacks(confirmVideoGone);
        mainHandler.removeCallbacks(startVideoFallback);
        releaseVlcSources();
        usingVlc = false;
    }

    void releaseAll() {
        releasePlayerOnly();
        abandonAudioFocus();
        unregisterBecomingNoisyReceiver();
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        if (libVlc != null) {
            try {
                libVlc.release();
            } catch (Exception ignored) {
            }
            libVlc = null;
        }
        playQueue.clear();
        libraryTracks.clear();
        queueIndex = -1;
        currentTrack = null;
    }

    private void notifyError(String message) {
        if (listener != null) {
            listener.onError(message);
        }
    }

    private void notifyPlaylistFinished() {
        abandonAudioFocus();
        if (mediaSession != null) {
            mediaSession.onPlaylistFinished();
        }
        if (listener != null) {
            listener.onPlaylistFinished();
        }
    }

    static List<String> supportedExtensions() {
        List<String> list = new ArrayList<>();
        list.add("mp3");
        list.add("m4a");
        list.add("aac");
        list.add("flac");
        list.add("ogg");
        list.add("oga");
        list.add("opus");
        list.add("wav");
        list.add("aiff");
        list.add("aif");
        list.add("wma");
        list.add("asf");
        list.add("amr");
        list.add("mid");
        list.add("midi");
        list.add("xmf");
        list.add("rtttl");
        list.add("imy");
        list.add("mxmf");
        list.addAll(videoExtensions());
        return list;
    }

    static boolean isVideoExtension(String extension) {
        return extension != null && videoExtensions().contains(extension);
    }

    static List<String> videoExtensions() {
        List<String> list = new ArrayList<>();
        list.add("mp4");
        list.add("m4v");
        list.add("mkv");
        list.add("webm");
        list.add("mov");
        list.add("avi");
        list.add("wmv");
        list.add("flv");
        list.add("mpeg");
        list.add("mpg");
        list.add("3gp");
        list.add("3gpp");
        list.add("3g2");
        list.add("ts");
        list.add("mts");
        list.add("m2t");
        list.add("m2ts");
        list.add("qt");
        list.add("vob");
        list.add("hevc");
        list.add("h264");
        list.add("h265");
        list.add("mpeg4");
        list.add("ogv");
        return list;
    }
}
