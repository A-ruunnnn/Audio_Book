package com.example.musicplayer;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

/** Saves library, folders and "last played" memory in SharedPreferences. */
public class Store {
    public static class Song {
        public final String uri, title;
        public Song(String uri, String title) { this.uri = uri; this.title = title; }
    }

    private final SharedPreferences p;
    public Store(Context c) { p = c.getSharedPreferences("music", Context.MODE_PRIVATE); }

    public List<Song> loadLibrary() {
        List<Song> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString("lib", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                l.add(new Song(o.getString("u"), o.getString("t")));
            }
        } catch (JSONException ignored) {}
        return l;
    }

    public void saveLibrary(List<Song> l) {
        JSONArray a = new JSONArray();
        try {
            for (Song s : l) a.put(new JSONObject().put("u", s.uri).put("t", s.title));
        } catch (JSONException ignored) {}
        p.edit().putString("lib", a.toString()).apply();
    }

    public Map<String, List<String>> loadFolders() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(p.getString("folders", "{}"));
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String name = it.next();
                JSONArray a = o.getJSONArray(name);
                List<String> uris = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) uris.add(a.getString(i));
                m.put(name, uris);
            }
        } catch (JSONException ignored) {}
        return m;
    }

    public void saveFolders(Map<String, List<String>> m) {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, List<String>> e : m.entrySet())
                o.put(e.getKey(), new JSONArray(e.getValue()));
        } catch (JSONException ignored) {}
        p.edit().putString("folders", o.toString()).apply();
    }

    // ---- "Memory": last played song, position and folder ----
    public void saveLast(String uri, int pos, String folder) {
        p.edit().putString("lastUri", uri).putInt("lastPos", pos).putString("lastFolder", folder).apply();
    }
    public String lastUri()    { return p.getString("lastUri", null); }
    public int    lastPos()    { return p.getInt("lastPos", 0); }
    public String lastFolder() { return p.getString("lastFolder", null); }
}
