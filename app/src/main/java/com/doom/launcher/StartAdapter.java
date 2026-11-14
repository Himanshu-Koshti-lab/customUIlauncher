package com.doom.launcher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Start screen content: one tile-grid item (pinned tiles + to-do tile), followed by the
 * "Apps" header and one row per app when "show all apps" is on.
 */
final class StartAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    static final int TYPE_GRID = 0;
    static final int TYPE_HEADER = 1;
    static final int TYPE_APP_ROW = 2;

    /** One row in the list. Only header/app rows carry data; the grid reads adapter state. */
    static final class Item {
        final int type;
        final AppInfo app;
        final boolean pinned;
        final String header;

        private Item(int type, AppInfo app, boolean pinned, String header) {
            this.type = type;
            this.app = app;
            this.pinned = pinned;
            this.header = header;
        }

        static Item grid() {
            return new Item(TYPE_GRID, null, false, null);
        }

        static Item header(String text) {
            return new Item(TYPE_HEADER, null, false, text);
        }

        static Item appRow(AppInfo app, boolean pinned) {
            return new Item(TYPE_APP_ROW, app, pinned, null);
        }
    }

    interface Listener {
        void onOpen(AppInfo app, View view);

        /** Long-press on a tile. {@code app} is null for the to-do tile. */
        void onTileLongPress(LauncherStore.Pin pin, AppInfo app, View view);

        void onRowLongPress(AppInfo app, boolean pinned, View view);

        /** Any tap on a tile while a tile is in resize mode: leave resize mode. */
        void onTapWhileResizing();

        void onResizeFinished(LauncherStore.Pin pin);

        void onTodoToggled(int index, boolean done);

        void onTodoEdit(int index);

        void onTodoAdd();

        void onTodoDelete(int index);
    }

    private final Listener listener;
    private List<Item> items = new ArrayList<>();
    private List<LauncherStore.Pin> pins = Collections.emptyList();
    private Map<String, AppInfo> apps = Collections.emptyMap();
    private List<LauncherStore.Todo> todos = Collections.emptyList();
    private String resizingKey;
    private int tilesPerRow;
    private boolean scrollTodosToEnd;
    /** Draw every tile in shades of gray. */
    private boolean grayscale;
    /** Index section whose apps are highlighted, or null. */
    private String highlightSection;

    /** On the next bind, scroll the task list to the bottom (after adding a task). */
    void scrollTodosToEndOnce() {
        scrollTodosToEnd = true;
    }

    StartAdapter(Listener listener, int tilesPerRow) {
        this.listener = listener;
        this.tilesPerRow = tilesPerRow;
    }

    void setTilesPerRow(int tilesPerRow) {
        this.tilesPerRow = tilesPerRow;
    }

    void setGrayscale(boolean grayscale) {
        this.grayscale = grayscale;
    }

    void submit(List<Item> items, List<LauncherStore.Pin> pins, Map<String, AppInfo> apps,
                List<LauncherStore.Todo> todos, String resizingKey) {
        this.items = items;
        this.pins = pins;
        this.apps = apps;
        this.todos = todos;
        this.resizingKey = resizingKey;
        notifyDataSetChanged();
    }

    /**
     * Highlight the app rows whose name starts with {@code section} (see
     * {@link AlphabetScrollBar#sectionFor}) and dim the rest; null clears the highlight.
     */
    void setHighlightSection(String section) {
        if (java.util.Objects.equals(section, highlightSection)) return;
        highlightSection = section;
        int header = headerPosition();
        if (header >= 0) notifyItemRangeChanged(header + 1, items.size() - header - 1);
    }

    /** Index of the "Apps" header, or -1 when the app list is hidden. */
    int headerPosition() {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).type == TYPE_HEADER) return i;
        }
        return -1;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).type;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case TYPE_GRID: {
                TileGridLayout grid = new TileGridLayout(parent.getContext());
                grid.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new GridHolder(grid);
            }
            case TYPE_HEADER:
                return new HeaderHolder(inflater.inflate(R.layout.item_header, parent, false));
            default:
                return new RowHolder(inflater.inflate(R.layout.item_app_row, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Item item = items.get(position);
        if (holder instanceof GridHolder) {
            ((GridHolder) holder).bind();
        } else if (holder instanceof HeaderHolder) {
            ((HeaderHolder) holder).text.setText(item.header);
        } else {
            ((RowHolder) holder).bind(item);
        }
    }

    // ---- Tile grid --------------------------------------------------------------------------

    private final class GridHolder extends RecyclerView.ViewHolder {
        final TileGridLayout grid;

        GridHolder(TileGridLayout grid) {
            super(grid);
            this.grid = grid;
            grid.setResizeListener(listener::onResizeFinished);
        }

        void bind() {
            LayoutInflater inflater = LayoutInflater.from(grid.getContext());
            grid.setColumns(tilesPerRow);
            grid.clearTiles();
            for (LauncherStore.Pin pin : pins) {
                View tile;
                if (pin.isTodo()) {
                    tile = createTodoTile(inflater, pin);
                } else {
                    AppInfo app = apps.get(pin.key);
                    if (app == null) continue;
                    tile = createAppTile(inflater, pin, app);
                }
                if (grayscale) applyGrayscale(tile);
                grid.addTile(tile, pin);
            }
            grid.setEditingKey(resizingKey);
        }

        private View createAppTile(LayoutInflater inflater, LauncherStore.Pin pin, AppInfo app) {
            View tile = inflater.inflate(R.layout.item_tile, grid, false);
            ImageView icon = tile.findViewById(R.id.tile_icon);
            TextView label = tile.findViewById(R.id.tile_label);

            tile.setBackgroundColor(app.tileColor);
            tile.setContentDescription(app.label);
            icon.setImageDrawable(app.newIcon());
            label.setText(app.label);

            // Smaller cells get a smaller icon and label so they don't look crowded.
            float density = tile.getResources().getDisplayMetrics().density;
            int iconDp = tilesPerRow <= 2 ? 56 : tilesPerRow == 3 ? 44 : 34;
            ViewGroup.LayoutParams lp = icon.getLayoutParams();
            lp.width = lp.height = Math.round(iconDp * density);
            label.setTextSize(tilesPerRow <= 2 ? 13 : tilesPerRow == 3 ? 12 : 10);
            int labelMargin = Math.round((tilesPerRow >= 4 ? 4 : 8) * density);
            ((ViewGroup.MarginLayoutParams) label.getLayoutParams())
                    .setMargins(labelMargin, labelMargin, labelMargin, labelMargin);

            tile.setOnClickListener(v -> {
                if (resizingKey != null) listener.onTapWhileResizing();
                else listener.onOpen(app, v);
            });
            tile.setOnLongClickListener(v -> {
                listener.onTileLongPress(pin, app, v);
                return true;
            });
            return tile;
        }

        private View createTodoTile(LayoutInflater inflater, LauncherStore.Pin pin) {
            View tile = inflater.inflate(R.layout.item_todo_tile, grid, false);
            LinearLayout list = tile.findViewById(R.id.todo_rows);
            tile.findViewById(R.id.todo_empty).setVisibility(todos.isEmpty() ? View.VISIBLE : View.GONE);

            NestedScrollView scroll = tile.findViewById(R.id.todo_scroll);
            keepScrollInsideTile(scroll);
            if (scrollTodosToEnd) {
                scrollTodosToEnd = false;
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN)); // show the task just added
            }

            tile.findViewById(R.id.todo_add).setOnClickListener(v -> {
                if (resizingKey != null) listener.onTapWhileResizing();
                else listener.onTodoAdd();
            });

            for (int i = 0; i < todos.size(); i++) {
                final int index = i;
                LauncherStore.Todo todo = todos.get(i);
                View row = inflater.inflate(R.layout.item_todo_row, list, false);
                CheckBox check = row.findViewById(R.id.todo_check);
                TextView text = row.findViewById(R.id.todo_text);
                View delete = row.findViewById(R.id.todo_delete);

                text.setText(todo.text);
                check.setChecked(todo.done);
                check.setContentDescription(
                        tile.getContext().getString(R.string.todo_mark_done, todo.text));
                delete.setContentDescription(
                        tile.getContext().getString(R.string.todo_delete, todo.text));
                applyDoneStyle(text, todo.done);

                delete.setOnClickListener(v -> {
                    if (resizingKey != null) listener.onTapWhileResizing();
                    else listener.onTodoDelete(index);
                });

                check.setOnClickListener(v -> {
                    if (resizingKey != null) {
                        check.setChecked(!check.isChecked()); // undo the toggle
                        listener.onTapWhileResizing();
                        return;
                    }
                    applyDoneStyle(text, check.isChecked());
                    listener.onTodoToggled(index, check.isChecked());
                });
                text.setOnClickListener(v -> {
                    if (resizingKey != null) listener.onTapWhileResizing();
                    else listener.onTodoEdit(index);
                });
                text.setOnLongClickListener(v -> {
                    listener.onTileLongPress(pin, null, tile);
                    return true;
                });
                list.addView(row);
            }

            tile.setOnClickListener(v -> {
                if (resizingKey != null) listener.onTapWhileResizing();
            });
            tile.setOnLongClickListener(v -> {
                listener.onTileLongPress(pin, null, v);
                return true;
            });
            return tile;
        }
    }

    /**
     * The Start list (a RecyclerView) would otherwise grab vertical swipes before the task list
     * gets them. While the finger is on the task list and it can still scroll in the swipe
     * direction, tell the ancestors not to intercept; at either end, hand the swipe back so the
     * Start screen scrolls as usual.
     */
    @SuppressLint("ClickableViewAccessibility")
    private static void keepScrollInsideTile(NestedScrollView scroll) {
        final float[] lastY = new float[1];
        scroll.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastY[0] = e.getY();
                    boolean scrollable = v.canScrollVertically(1) || v.canScrollVertically(-1);
                    v.getParent().requestDisallowInterceptTouchEvent(scrollable);
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float dy = lastY[0] - e.getY(); // > 0: finger moving up, content scrolls down
                    lastY[0] = e.getY();
                    if (dy != 0 && !v.canScrollVertically(dy > 0 ? 1 : -1)) {
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    break;
            }
            return false; // let NestedScrollView handle the scrolling itself
        });
    }

    /**
     * Desaturate the whole tile (background, icon, text) by drawing it through a hardware layer
     * whose paint has a zero-saturation color filter.
     */
    private static void applyGrayscale(View tile) {
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0f);
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        tile.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
    }

    private static void applyDoneStyle(TextView text, boolean done) {
        int flags = text.getPaintFlags();
        text.setPaintFlags(done ? flags | Paint.STRIKE_THRU_TEXT_FLAG : flags & ~Paint.STRIKE_THRU_TEXT_FLAG);
        text.setAlpha(done ? 0.6f : 1f);
    }

    // ---- Header + app rows ------------------------------------------------------------------

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView text;

        HeaderHolder(View view) {
            super(view);
            text = (TextView) view;
        }
    }

    private final class RowHolder extends RecyclerView.ViewHolder {
        final View iconBox;
        final ImageView icon;
        final TextView label;
        final View pinned;

        RowHolder(View view) {
            super(view);
            iconBox = view.findViewById(R.id.row_icon_box);
            icon = view.findViewById(R.id.row_icon);
            label = view.findViewById(R.id.row_label);
            pinned = view.findViewById(R.id.row_pinned);
        }

        void bind(Item item) {
            iconBox.setBackgroundColor(item.app.tileColor);
            icon.setImageDrawable(item.app.newIcon());
            label.setText(item.app.label);
            pinned.setVisibility(item.pinned ? View.VISIBLE : View.GONE);

            boolean match = highlightSection != null
                    && highlightSection.equals(AlphabetScrollBar.sectionFor(item.app.label));
            Context context = itemView.getContext();
            label.setTextColor(context.getColor(match ? R.color.metro_accent : R.color.white));
            label.setTypeface(null, match ? Typeface.BOLD : Typeface.NORMAL);
            itemView.setAlpha(highlightSection == null || match ? 1f : 0.45f);
            itemView.setOnClickListener(v -> {
                if (resizingKey != null) listener.onTapWhileResizing();
                else listener.onOpen(item.app, v);
            });
            itemView.setOnLongClickListener(v -> {
                listener.onRowLongPress(item.app, item.pinned, v);
                return true;
            });
        }
    }
}
