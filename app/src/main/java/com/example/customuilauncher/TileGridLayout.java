package com.example.customuilauncher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;

/**
 * Windows 8 style tile grid. Each child carries a {@link LauncherStore.Pin} as its tag giving its
 * size in square cells. Tiles are packed in order, each into the first free spot (top-left first),
 * so small tiles fill the holes beside big ones.
 *
 * <p>One tile at a time can be in "resize mode": it gets a white outline and a corner handle that
 * can be dragged to grow or shrink the tile, snapping to whole cells.
 */
public class TileGridLayout extends ViewGroup {

    interface ResizeListener {
        void onResizeFinished(LauncherStore.Pin pin);
    }

    private int columns = 2;
    private final int gap;          // margin around each tile (half the gap between tiles)
    private final int handleSize;
    private float cellSize;

    private final ImageView handle;
    private View editingTile;
    private ResizeListener resizeListener;
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Drag state
    private float downX, downY;
    private int startCols, startRows;

    public TileGridLayout(Context context) {
        this(context, null);
    }

    public TileGridLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        gap = Math.round(3 * density);
        handleSize = Math.round(30 * density);

        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(2 * density);
        outlinePaint.setColor(0xFFFFFFFF);

        handle = new ImageView(context);
        handle.setImageResource(R.drawable.ic_resize_handle);
        handle.setContentDescription(context.getString(R.string.resize_handle));
        handle.setOnTouchListener(this::onHandleTouch);
        setWillNotDraw(false);
    }

    void setColumns(int columns) {
        if (this.columns != columns) {
            this.columns = columns;
            requestLayout();
        }
    }

    void setResizeListener(ResizeListener listener) {
        this.resizeListener = listener;
    }

    /** Remove all tiles (and the resize handle). */
    void clearTiles() {
        editingTile = null;
        removeAllViews();
    }

    void addTile(View tile, LauncherStore.Pin pin) {
        tile.setTag(pin);
        addView(tile);
    }

    /** Put the tile with this pin key into resize mode, or pass null to leave resize mode. */
    void setEditingKey(String key) {
        removeView(handle);
        editingTile = null;
        if (key != null) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (pinOf(child) != null && key.equals(pinOf(child).key)) {
                    editingTile = child;
                    addView(handle); // added last so it's drawn on top
                    break;
                }
            }
        }
        invalidate();
        requestLayout();
    }

    private static LauncherStore.Pin pinOf(View v) {
        return v.getTag() instanceof LauncherStore.Pin ? (LauncherStore.Pin) v.getTag() : null;
    }

    // ---- Measure / layout -----------------------------------------------------------------------

    /** Cell position assigned to each tile during packing. */
    private static final class Slot {
        int col, row, cols, rows;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        cellSize = (width - getPaddingLeft() - getPaddingRight()) / (float) columns;

        List<boolean[]> occupied = new ArrayList<>();
        int usedRows = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            LauncherStore.Pin pin = pinOf(child);
            if (pin == null) continue; // the resize handle

            Slot slot = new Slot();
            slot.cols = Math.min(Math.max(1, pin.cols), columns);
            slot.rows = Math.min(Math.max(1, pin.rows), LauncherStore.MAX_TILE_ROWS);
            place(occupied, slot);
            usedRows = Math.max(usedRows, slot.row + slot.rows);

            child.setTag(R.id.tile_slot, slot);
            int w = Math.round(slot.cols * cellSize) - 2 * gap;
            int h = Math.round(slot.rows * cellSize) - 2 * gap;
            child.measure(MeasureSpec.makeMeasureSpec(Math.max(0, w), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(0, h), MeasureSpec.EXACTLY));
        }

        if (handle.getParent() == this) {
            int spec = MeasureSpec.makeMeasureSpec(handleSize, MeasureSpec.EXACTLY);
            handle.measure(spec, spec);
        }

        int height = Math.round(usedRows * cellSize) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(width, height);
    }

    /** First-fit: scan rows top to bottom, columns left to right, for a free cols×rows block. */
    private void place(List<boolean[]> occupied, Slot slot) {
        for (int r = 0; ; r++) {
            for (int c = 0; c + slot.cols <= columns; c++) {
                if (isFree(occupied, r, c, slot.cols, slot.rows)) {
                    for (int rr = r; rr < r + slot.rows; rr++) {
                        for (int cc = c; cc < c + slot.cols; cc++) row(occupied, rr)[cc] = true;
                    }
                    slot.row = r;
                    slot.col = c;
                    return;
                }
            }
        }
    }

    private boolean isFree(List<boolean[]> occupied, int r, int c, int cols, int rows) {
        for (int rr = r; rr < r + rows; rr++) {
            if (rr >= occupied.size()) return true; // rows below are empty
            boolean[] line = occupied.get(rr);
            for (int cc = c; cc < c + cols; cc++) if (line[cc]) return false;
        }
        return true;
    }

    private boolean[] row(List<boolean[]> occupied, int r) {
        while (occupied.size() <= r) occupied.add(new boolean[columns]);
        return occupied.get(r);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int left0 = getPaddingLeft();
        int top0 = getPaddingTop();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            Object tag = child.getTag(R.id.tile_slot);
            if (child == handle || !(tag instanceof Slot)) continue;
            Slot s = (Slot) tag;
            int left = left0 + Math.round(s.col * cellSize) + gap;
            int top = top0 + Math.round(s.row * cellSize) + gap;
            child.layout(left, top, left + child.getMeasuredWidth(), top + child.getMeasuredHeight());
        }
        if (editingTile != null && handle.getParent() == this) {
            // Bottom-right corner, slightly overhanging the tile so it's easy to grab.
            int hl = editingTile.getRight() - handleSize + gap;
            int ht = editingTile.getBottom() - handleSize + gap;
            handle.layout(hl, ht, hl + handleSize, ht + handleSize);
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (editingTile != null) {
            float inset = outlinePaint.getStrokeWidth() / 2;
            canvas.drawRect(editingTile.getLeft() + inset, editingTile.getTop() + inset,
                    editingTile.getRight() - inset, editingTile.getBottom() - inset, outlinePaint);
            // Draw the handle again so it sits above the outline.
            if (handle.getParent() == this) drawChild(canvas, handle, getDrawingTime());
        }
    }

    // ---- Drag to resize ---------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private boolean onHandleTouch(View v, MotionEvent e) {
        LauncherStore.Pin pin = editingTile == null ? null : pinOf(editingTile);
        if (pin == null || cellSize <= 0) return false;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                startCols = Math.min(pin.cols, columns);
                startRows = pin.rows;
                // Stop the RecyclerView from scrolling while we drag.
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downX;
                float dy = e.getRawY() - downY;
                if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) dx = -dx;
                int cols = clamp(Math.round(startCols + dx / cellSize), 1, columns);
                int rows = clamp(Math.round(startRows + dy / cellSize), 1, LauncherStore.MAX_TILE_ROWS);
                if (cols != pin.cols || rows != pin.rows) {
                    pin.cols = cols;
                    pin.rows = rows;
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                    requestLayout();
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                if (resizeListener != null) resizeListener.onResizeFinished(pin);
                return true;
        }
        return false;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
