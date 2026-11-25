package com.example.musicplayer;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.google.common.util.concurrent.ListenableFuture;
import android.view.Menu;
import android.view.MenuItem;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    // Extensions treated as audio when scanning folders (m4b etc. often have odd MIME types)
    static final Set<String> AUDIO_EXT = new HashSet<>(Arrays.asList(
            "mp3", "m4a", "m4b", "m4p", "aac", "flac", "ogg", "oga", "opus", "wav", "wave",
            "mka", "amr", "mid", "midi", "ac3", "aif", "aiff", "wma"));

    static final long SKIP_MS = 5000;   // +5 s / -5 s buttons

    Store store;
    List<Store.Song> library, shown = new ArrayList<>();
    Map<String, List<String>> folders;
    String currentFolder = null;   // null = "All songs"
    String curUri = null;          // song currently loaded

    // The ExoPlayer now lives in MusicService. The activity only holds a remote control for it.
    ListenableFuture<MediaController> controllerFuture;
    MediaController controller;
    final List<Runnable> pending = new ArrayList<>();  // actions requested before the controller connected
    boolean dragging = false;
    int tick = 0;
    Handler handler = new Handler(Looper.getMainLooper());

    Spinner spinner;
    ListView list;
    TextView nowTitle, txtCur, txtTotal;
    SeekBar seek;
    Button btnPlay;

    ActivityResultLauncher<String[]> pickFiles = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                for (Uri u : uris) { keep(u); addSong(u.toString(), nameOf(u)); }
                afterChange();
            });

    ActivityResultLauncher<Uri> pickTree = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), uri -> { if (uri != null) importTree(uri); });

    // Android 13+: needed for the media notification to be visible (playback works regardless).
    ActivityResultLauncher<String> askNotifications = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {});

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        store = new Store(this);
        library = store.loadLibrary();
        folders = store.loadFolders();

        spinner = findViewById(R.id.spinner);
        list = findViewById(R.id.list);
        nowTitle = findViewById(R.id.nowTitle);
        seek = findViewById(R.id.seek);
        txtCur = findViewById(R.id.txtCur);
        txtTotal = findViewById(R.id.txtTotal);
        btnPlay = findViewById(R.id.btnPlay);

        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS);
        }


//        findViewById(R.id.btnFolder).setOnClickListener(v -> pickTree.launch(null));
//        findViewById(R.id.btnNew).setOnClickListener(v -> newFolderDialog());
//        findViewById(R.id.btnDel).setOnClickListener(v -> deleteFolder());
        findViewById(R.id.btnPrev).setOnClickListener(v -> step(-1));
        findViewById(R.id.btnNext).setOnClickListener(v -> step(1));
        findViewById(R.id.btnBack5).setOnClickListener(v -> skip(-SKIP_MS));
        findViewById(R.id.btnFwd5).setOnClickListener(v -> skip(SKIP_MS));
        btnPlay.setOnClickListener(v -> togglePlay());

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                currentFolder = pos == 0 ? null : spinner.getItemAtPosition(pos).toString();
                refreshList();
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });

        list.setOnItemClickListener((p, v, i, id) -> play(shown.get(i).uri, 0, true));
        list.setOnItemLongClickListener((p, v, i, id) -> {
            Store.Song s = shown.get(i);
            String[] opts = currentFolder == null
                    ? new String[]{"Add to folder", "Remove from library"}
                    : new String[]{"Add to another folder", "Remove from this folder"};
            new AlertDialog.Builder(this).setTitle(s.title).setItems(opts, (d, w) -> {
                if (w == 0) chooseFolder(s); else removeSong(s);
            }).show();
            return true;
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                if (fromUser) txtCur.setText(fmt(v));          // live timestamp while dragging
            }
            public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (controller != null && controller.getMediaItemCount() > 0) {
                    controller.seekTo(s.getProgress());
                }
                updateProgress();
            }
        });

        // ---- MEMORY: restore last folder (song + position are restored once the player connects) ----
        currentFolder = store.lastFolder();
        if (currentFolder != null && !folders.containsKey(currentFolder)) currentFolder = null;
        refreshSpinner();

        connectToService();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_add) {
            pickFiles.launch(new String[]{"audio/*", "application/octet-stream", "application/ogg", "video/mp4"});
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override protected void onStart() {
        super.onStart();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override protected void onStop() {
        super.onStop();
        handler.removeCallbacks(ticker);   // no UI polling while in the background
    }

    // ---------- Adding music ----------
    void keep(Uri u) {
        try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignored) {}
    }

    String stripExt(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    String nameOf(Uri u) {
        DocumentFile f = DocumentFile.fromSingleUri(this, u);
        return stripExt(f != null && f.getName() != null ? f.getName() : "Unknown");
    }

    boolean addSong(String uri, String title) {
        for (Store.Song s : library) if (s.uri.equals(uri)) return false;
        library.add(new Store.Song(uri, title));
        return true;
    }

    void addToFolder(String name, String uri) {
        List<String> l = folders.get(name);
        if (l == null) { l = new ArrayList<>(); folders.put(name, l); }
        if (!l.contains(uri)) l.add(uri);
    }

    void importTree(Uri tree) {
        keep(tree);
        new Thread(() -> {
            DocumentFile root = DocumentFile.fromTreeUri(this, tree);
            List<Store.Song> found = new ArrayList<>();
            scan(root, found);
            runOnUiThread(() -> {
                String name = root != null && root.getName() != null ? root.getName() : "Folder";
                for (Store.Song s : found) { addSong(s.uri, s.title); addToFolder(name, s.uri); }
                if (!found.isEmpty()) currentFolder = name;
                afterChange();
                toast(found.size() + " songs added");
            });
        }).start();
    }

    boolean isAudio(DocumentFile f) {
        String t = f.getType();
        if (t != null && t.startsWith("audio/")) return true;
        String n = f.getName() == null ? "" : f.getName().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return dot >= 0 && AUDIO_EXT.contains(n.substring(dot + 1));
    }

    void scan(DocumentFile dir, List<Store.Song> out) {
        if (dir == null) return;
        for (DocumentFile f : dir.listFiles()) {
            if (f.isDirectory()) scan(f, out);
            else if (isAudio(f)) out.add(new Store.Song(f.getUri().toString(),
                    stripExt(f.getName() == null ? "Unknown" : f.getName())));
        }
    }

    // ---------- Folders ----------
    void newFolderDialog() {
        EditText et = new EditText(this);
        et.setHint("Folder name");
        new AlertDialog.Builder(this).setTitle("New music folder").setView(et)
                .setPositiveButton("Create", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (n.isEmpty()) return;
                    if (!folders.containsKey(n)) folders.put(n, new ArrayList<>());
                    currentFolder = n;
                    afterChange();
                }).setNegativeButton("Cancel", null).show();
    }

    void deleteFolder() {
        if (currentFolder == null) { toast("Select a folder first"); return; }
        folders.remove(currentFolder);
        currentFolder = null;
        afterChange();
    }

    void chooseFolder(Store.Song s) {
        if (folders.isEmpty()) { toast("Create a folder first"); return; }
        String[] names = folders.keySet().toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("Add to…").setItems(names, (d, w) -> {
            addToFolder(names[w], s.uri);
            store.saveFolders(folders);
            toast("Added to " + names[w]);
        }).show();
    }

    void removeSong(Store.Song s) {
        if (currentFolder == null) {
            library.remove(s);
            for (List<String> l : folders.values()) l.remove(s.uri);
        } else folders.get(currentFolder).remove(s.uri);
        afterChange();
    }

    // ---------- UI refresh ----------
    void afterChange() {
        store.saveLibrary(library);
        store.saveFolders(folders);
        refreshSpinner();
    }

    void refreshSpinner() {
        List<String> names = new ArrayList<>();
        names.add("All songs");
        names.addAll(folders.keySet());
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        spinner.setSelection(currentFolder == null ? 0 : Math.max(0, names.indexOf(currentFolder)));
        refreshList();
    }

    void refreshList() {
        shown.clear();
        if (currentFolder == null) shown.addAll(library);
        else for (String u : folders.get(currentFolder)) {
            for (Store.Song s : library) if (s.uri.equals(u)) { shown.add(s); break; }
        }
        List<String> titles = new ArrayList<>();
        for (Store.Song s : shown) titles.add(s.title);
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, titles));
    }

    String titleOf(String uri) {
        for (Store.Song s : library) if (s.uri.equals(uri)) return s.title;
        return null;
    }

    void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    // ---------- Connection to MusicService ----------
    void connectToService() {
        SessionToken token = new SessionToken(this, new ComponentName(this, MusicService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
            } catch (Exception e) {
                toast("Couldn't start the music service");
                return;
            }
            controller.addListener(playerListener);
            btnPlay.setText(controller.isPlaying() ? "⏸" : "▶");

            if (controller.getMediaItemCount() > 0) {
                // Service is already playing/holding a song (activity was recreated or re-opened):
                // adopt its state instead of restarting anything.
                syncFromPlayer();
            } else {
                // ---- MEMORY: restore last song + position (paused) ----
                String last = store.lastUri();
                if (last != null && titleOf(last) != null) play(last, store.lastPos(), false);
            }
            for (Runnable r : new ArrayList<>(pending)) r.run();
            pending.clear();
            updateProgress();
        }, ContextCompat.getMainExecutor(this));
    }

    void withController(Runnable r) {
        if (controller != null) r.run(); else pending.add(r);
    }

    void syncFromPlayer() {
        MediaItem mi = controller.getCurrentMediaItem();
        if (mi == null) return;
        curUri = mi.mediaId;
        String t = titleOf(curUri);
        nowTitle.setText(t != null ? t : mi.mediaMetadata.title);
    }

    final Player.Listener playerListener = new Player.Listener() {
        @Override public void onPlaybackStateChanged(int state) { updateProgress(); }
        @Override public void onIsPlayingChanged(boolean isPlaying) {
            btnPlay.setText(isPlaying ? "⏸" : "▶");
        }
        @Override public void onMediaItemTransition(MediaItem item, int reason) {
            // Fires for auto-advance AND for next/previous pressed in the notification / lock screen.
            if (item == null) return;
            curUri = item.mediaId;
            String t = titleOf(curUri);
            nowTitle.setText(t != null ? t : item.mediaMetadata.title);
            updateProgress();
        }
        @Override public void onPlayerError(PlaybackException e) {
            toast("Can't play this file (" + e.getErrorCodeName() + ")");
        }
    };

    // ---------- Playback (all commands go to the player inside MusicService) ----------
    MediaItem itemFor(String uri, String title) {
        return new MediaItem.Builder()
                .setMediaId(uri)                 // mediaId == song URI
                .setUri(Uri.parse(uri))
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(title).build())
                .build();
    }

    void play(String uri, int startPos, boolean autoStart) {
        curUri = uri;
        nowTitle.setText(titleOf(uri));
        seek.setProgress(0);
        txtCur.setText(fmt(startPos));
        txtTotal.setText(fmt(0));
        store.saveLast(uri, startPos, currentFolder);
        withController(() -> {
            // Queue = the songs currently shown, so Next/Previous (also from the notification)
            // walk the list the user picked from.
            List<MediaItem> items = new ArrayList<>();
            int idx = -1;
            for (Store.Song s : shown) {
                if (s.uri.equals(uri)) idx = items.size();
                items.add(itemFor(s.uri, s.title));
            }
            if (idx < 0) {                       // song isn't in the visible list: play it alone
                items.clear();
                items.add(itemFor(uri, titleOf(uri)));
                idx = 0;
            }
            controller.setMediaItems(items, idx, startPos);
            controller.prepare();
            controller.setPlayWhenReady(autoStart);
        });
    }

    void step(int dir) {
        if (controller == null) return;
        if (controller.getMediaItemCount() == 0) {
            if (!shown.isEmpty()) play(shown.get(0).uri, 0, true);
            return;
        }
        if (dir > 0) controller.seekToNextMediaItem(); else controller.seekToPreviousMediaItem();
        if (controller.getPlaybackState() == Player.STATE_IDLE) controller.prepare();
        controller.play();
        saveMemory();
    }

    void togglePlay() {
        if (controller == null) return;
        if (curUri == null || controller.getMediaItemCount() == 0) {
            if (curUri == null && !shown.isEmpty()) play(shown.get(0).uri, 0, true);
            return;
        }
        if (controller.isPlaying()) { controller.pause(); }
        else {
            if (controller.getPlaybackState() == Player.STATE_IDLE) controller.prepare();
            controller.play();
        }
        saveMemory();
    }

    /** +5 s / -5 s. Clamped to [0, duration]; works while playing or paused. */
    void skip(long deltaMs) {
        if (controller == null || controller.getMediaItemCount() == 0) return;
        long dur = controller.getDuration();
        long target = controller.getCurrentPosition() + deltaMs;
        if (dur != C.TIME_UNSET && dur > 0) target = Math.min(target, dur);
        target = Math.max(0, target);
        controller.seekTo(target);
        // Update the UI right away instead of waiting for the next tick.
        if (seek.getMax() > 0) seek.setProgress((int) Math.min(target, seek.getMax()));
        txtCur.setText(fmt(target));
    }

    // ---------- Progress bar + timestamps ----------
    /** HH:MM:SS (hours grow past 99 if they ever need to). */
    static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    void updateProgress() {
        if (controller == null || controller.getMediaItemCount() == 0) {
            seek.setEnabled(false);
            seek.setMax(0);
            if (!dragging) { seek.setProgress(0); txtCur.setText(fmt(0)); }
            txtTotal.setText(fmt(0));
            return;
        }
        long dur = controller.getDuration();
        boolean known = dur != C.TIME_UNSET && dur > 0;
        seek.setEnabled(known);                                   // unknown length: can't drag safely
        seek.setMax(known ? (int) Math.min(dur, Integer.MAX_VALUE) : 0);
        txtTotal.setText(known ? fmt(dur) : "--:--:--");
        if (dragging) return;                                     // user is moving the thumb
        long pos = Math.max(0, controller.getCurrentPosition());
        seek.setProgress((int) Math.min(pos, Integer.MAX_VALUE));
        txtCur.setText(fmt(pos));
    }

    void saveMemory() {
        if (curUri == null) return;
        boolean ready = controller != null && controller.getPlaybackState() == Player.STATE_READY;
        int pos = ready ? (int) controller.getCurrentPosition() : store.lastPos();
        store.saveLast(curUri, pos, currentFolder);
    }

    Runnable ticker = new Runnable() {
        public void run() {
            updateProgress();
            handler.postDelayed(this, 500);
        }
    };

    @Override protected void onPause() { super.onPause(); saveMemory(); }
    @Override protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(ticker);
        saveMemory();
        // Only release the REMOTE CONTROL. The player stays alive in MusicService.
        if (controller != null) controller.removeListener(playerListener);
        if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
        controller = null;
    }
}
