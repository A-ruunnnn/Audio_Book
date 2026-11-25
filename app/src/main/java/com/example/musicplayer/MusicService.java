package com.example.musicplayer;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;

/**
 * Owns the one and only ExoPlayer and exposes it through a MediaSession.
 *
 * MainActivity, the notification and the lock screen are all just controllers of this
 * session, so they always talk to the same player. Because the service (not the activity)
 * owns the player, playback survives the activity being closed, rotated or recreated.
 */
public class MusicService extends MediaSessionService {
    private static final long SAVE_INTERVAL_MS = 3000;

    private ExoPlayer player;
    private MediaSession session;
    private Store store;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Saves "last played" memory every ~3 s while playing (the activity may not be alive). */
    private final Runnable saver = new Runnable() {
        @Override public void run() {
            saveMemory();
            if (player != null && player.isPlaying()) handler.postDelayed(this, SAVE_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        store = new Store(this);

        player = new ExoPlayer.Builder(this)
                // true = handle audio focus (pause on calls / other apps, duck, resume)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)      // pause when headphones are unplugged
                .setWakeMode(C.WAKE_MODE_LOCAL)         // keep CPU awake while the screen is off
                .build();
        // Same behaviour as the old step(): next after the last song wraps to the first.
        player.setRepeatMode(Player.REPEAT_MODE_ALL);

        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                handler.removeCallbacks(saver);
                saveMemory();
                if (isPlaying) handler.postDelayed(saver, SAVE_INTERVAL_MS);
            }
            @Override public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
                // Auto-advance, or next/previous from the notification / lock screen.
                // (A fresh list from the activity is saved by the activity itself.)
                if (item != null && (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                        || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)) {
                    store.saveLast(item.mediaId, 0, store.lastFolder());
                }
            }
        });

        // Tapping the notification re-opens the app.
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        session = new MediaSession.Builder(this, player)
                .setSessionActivity(contentIntent)
                .setCallback(new MediaSession.Callback() {
                    // Controllers send MediaItems without a playable URI in some cases
                    // (e.g. Bluetooth / assistant). Our mediaId IS the song URI, so fill it in.
                    @Override
                    public ListenableFuture<List<MediaItem>> onAddMediaItems(
                            MediaSession mediaSession, MediaSession.ControllerInfo controller,
                            List<MediaItem> mediaItems) {
                        ImmutableList.Builder<MediaItem> out = ImmutableList.builder();
                        for (MediaItem mi : mediaItems) {
                            if (mi.localConfiguration == null && !mi.mediaId.isEmpty()
                                    && !MediaItem.DEFAULT_MEDIA_ID.equals(mi.mediaId)) {
                                out.add(mi.buildUpon().setUri(mi.mediaId).build());
                            } else {
                                out.add(mi);
                            }
                        }
                        return Futures.immediateFuture(out.build());
                    }
                })
                .build();
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    /** User swiped the app away: keep playing if music is playing, otherwise shut down. */
    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        if (player == null || !player.getPlayWhenReady() || player.getMediaItemCount() == 0) {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(saver);
        saveMemory();
        if (session != null) { session.release(); session = null; }
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }

    private void saveMemory() {
        if (player == null) return;
        MediaItem item = player.getCurrentMediaItem();
        int st = player.getPlaybackState();
        if (item == null || (st != Player.STATE_READY && st != Player.STATE_ENDED)) return;
        store.saveLast(item.mediaId, (int) Math.max(0, player.getCurrentPosition()), store.lastFolder());
    }
}
