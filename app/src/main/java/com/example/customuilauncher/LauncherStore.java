package com.example.customuilauncher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Persists pinned tiles (order + size), the to-do list and launcher settings. */
final class LauncherStore {
    private static final String PREFS = "launcher";
    private static final String KEY_PINS = "pins";
    private static final String KEY_SHOW_ALL = "show_all_apps";
    private static final String KEY_TILES_PER_ROW = "tiles_per_row";
    private static final String KEY_TODO_SEEDED = "todo_seeded";
    private static final String KEY_TODOS = "todos";
    // Previous build stored exactly 3 tasks under these keys; read once for migration.
    private static final String KEY_TODO_TEXT = "todo_text_";
    private static final String KEY_TODO_DONE = "todo_done_";
    private static final int LEGACY_TODO_SLOTS = 3;

    static final int MIN_TILES_PER_ROW = 2;
    static final int MAX_TILES_PER_ROW = 4;
    /** Tallest a tile may be dragged, in grid cells. */
    static final int MAX_TILE_ROWS = 4;

    /** Pin key of the built-in to-do tile (app pins use a flattened ComponentName). */
    static final String TODO_KEY = "builtin:todo";

    /** A pinned tile and its size in grid cells. */
    static final class Pin {
        final String key;
        int cols;
        int rows;

        Pin(String key, int cols, int rows) {
            this.key = key;
            this.cols = Math.max(1, cols);
            this.rows = Math.max(1, Math.min(MAX_TILE_ROWS, rows));
        }

        boolean isTodo() {
            return TODO_KEY.equals(key);
        }
    }

    static final class Todo {
        final String text;
        final boolean done;

        Todo(String text, boolean done) {
            this.text = text;
            this.done = done;
        }
    }

    private final SharedPreferences prefs;

    LauncherStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- Settings ---------------------------------------------------------------------------

    boolean isShowAllApps() {
        return prefs.getBoolean(KEY_SHOW_ALL, false);
    }

    void setShowAllApps(boolean show) {
        prefs.edit().putBoolean(KEY_SHOW_ALL, show).apply();
    }

    /** Number of 1×1 tiles that fit in one row (2, 3 or 4). */
    int getTilesPerRow() {
        int n = prefs.getInt(KEY_TILES_PER_ROW, MIN_TILES_PER_ROW);
        return Math.max(MIN_TILES_PER_ROW, Math.min(MAX_TILES_PER_ROW, n));
    }

    void setTilesPerRow(int n) {
        prefs.edit().putInt(KEY_TILES_PER_ROW, n).apply();
    }

    // ---- Pins -------------------------------------------------------------------------------

    /** False until pins have been saved once (used to seed default tiles on first run). */
    boolean hasSavedPins() {
        return prefs.contains(KEY_PINS);
    }

    /** Whether the to-do tile has been added once (so unpinning it sticks). */
    boolean isTodoSeeded() {
        return prefs.getBoolean(KEY_TODO_SEEDED, false);
    }

    void setTodoSeeded() {
        prefs.edit().putBoolean(KEY_TODO_SEEDED, true).apply();
    }

    /** Format per line: {@code key|cols|rows}. Older builds stored {@code key|W} or {@code key|M}. */
    List<Pin> loadPins() {
        List<Pin> pins = new ArrayList<>();
        String raw = prefs.getString(KEY_PINS, "");
        for (String line : raw.split("\n")) {
            String[] parts = line.split("\\|");
            if (parts.length == 3) {
                try {
                    pins.add(new Pin(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
                } catch (NumberFormatException ignored) {
                    // skip corrupt entry
                }
            } else if (parts.length == 2 && !parts[0].isEmpty()) {
                pins.add(new Pin(parts[0], "W".equals(parts[1]) ? 2 : 1, 1));
            }
        }
        return pins;
    }

    void savePins(List<Pin> pins) {
        StringBuilder sb = new StringBuilder();
        for (Pin pin : pins) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(pin.key).append('|').append(pin.cols).append('|').append(pin.rows);
        }
        prefs.edit().putString(KEY_PINS, sb.toString()).apply();
    }

    // ---- To-dos -----------------------------------------------------------------------------

    /** Saved tasks, in order. Empty by default. */
    List<Todo> loadTodos() {
        List<Todo> todos = new ArrayList<>();
        String raw = prefs.getString(KEY_TODOS, null);
        if (raw == null) {
            // Migrate the fixed 3-slot list from the previous build, keeping only filled slots.
            for (int i = 0; i < LEGACY_TODO_SLOTS; i++) {
                String text = prefs.getString(KEY_TODO_TEXT + i, "");
                if (!text.isEmpty()) {
                    todos.add(new Todo(text, prefs.getBoolean(KEY_TODO_DONE + i, false)));
                }
            }
            return todos;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                todos.add(new Todo(o.optString("text", ""), o.optBoolean("done", false)));
            }
        } catch (JSONException ignored) {
            // corrupt data: start with an empty list
        }
        return todos;
    }

    void saveTodos(List<Todo> todos) {
        JSONArray array = new JSONArray();
        try {
            for (Todo todo : todos) {
                array.put(new JSONObject().put("text", todo.text).put("done", todo.done));
            }
        } catch (JSONException e) {
            return; // can't happen with string/boolean values
        }
        SharedPreferences.Editor editor = prefs.edit().putString(KEY_TODOS, array.toString());
        for (int i = 0; i < LEGACY_TODO_SLOTS; i++) {
            editor.remove(KEY_TODO_TEXT + i).remove(KEY_TODO_DONE + i);
        }
        editor.apply();
    }
}
