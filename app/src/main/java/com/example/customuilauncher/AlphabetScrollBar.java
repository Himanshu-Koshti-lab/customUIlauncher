package com.example.customuilauncher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Vertical fast-scroll index on the right edge of the app list. It lists only the characters
 * that some app name starts with: "#" for digits first, then the letters in order.
 * Tap or drag along it to jump to the apps starting with that character.
 */
public final class AlphabetScrollBar extends View {

    /** Section for app names starting with a digit. */
    static final String DIGITS = "#";

    interface Listener {
        /** The finger moved onto {@code section} (one of the sections passed to {@link #setSections}). */
        void onSectionSelected(String section);

        /** The finger left the bar. */
        void onSectionReleased();
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float textSize;
    private final float selectedTextSize;
    /** Cells never get taller than this, so a short index stays compact and centred. */
    private final float maxCellHeight;
    private final int activeColor;
    private final int textColor;

    private Listener listener;
    private List<String> sections = Collections.emptyList();
    private int selected = -1;

    public AlphabetScrollBar(Context context) {
        this(context, null);
    }

    public AlphabetScrollBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, dm);
        selectedTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 17, dm);
        maxCellHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, dm);
        activeColor = context.getColor(R.color.metro_accent);
        textColor = context.getColor(R.color.white);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        setContentDescription(context.getString(R.string.alphabet_index));
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Sections to show, top to bottom (see {@link #sectionFor}). */
    void setSections(List<String> sections) {
        if (sections.equals(this.sections)) return;
        this.sections = new ArrayList<>(sections);
        selected = -1;
        invalidate();
    }

    /**
     * Index section for an app label: "#" if it starts with a digit, otherwise its first
     * letter with accents stripped, upper-cased. Null for symbols and non-Latin scripts.
     */
    @Nullable
    static String sectionFor(String label) {
        if (label == null) return null;
        String trimmed = label.trim();
        if (trimmed.isEmpty()) return null;
        String first = Normalizer.normalize(trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)),
                Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        if (first.isEmpty()) return null;
        char c = first.toUpperCase(Locale.ROOT).charAt(0);
        if (c >= '0' && c <= '9') return DIGITS;
        if (c >= 'A' && c <= 'Z') return String.valueOf(c);
        return null;
    }

    private float cellHeight() {
        if (sections.isEmpty()) return 0;
        float available = getHeight() - getPaddingTop() - getPaddingBottom();
        return Math.min(maxCellHeight, available / sections.size());
    }

    /** Top of the first cell; the column of characters is centred vertically. */
    private float listTop() {
        float available = getHeight() - getPaddingTop() - getPaddingBottom();
        return getPaddingTop() + (available - cellHeight() * sections.size()) / 2f;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float cell = cellHeight();
        if (cell <= 0) return;
        float x = getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight()) / 2f;
        float top = listTop();
        float normalSize = Math.min(textSize, cell * 0.8f);
        for (int i = 0; i < sections.size(); i++) {
            boolean isSelected = i == selected;
            paint.setTextSize(isSelected ? Math.max(selectedTextSize, normalSize) : normalSize);
            paint.setColor(isSelected ? activeColor : textColor);
            Paint.FontMetrics fm = paint.getFontMetrics();
            float centerY = top + cell * (i + 0.5f);
            canvas.drawText(sections.get(i), x, centerY - (fm.ascent + fm.descent) / 2f, paint);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE: {
                float cell = cellHeight();
                if (cell <= 0) return true;
                int index = (int) Math.floor((event.getY() - listTop()) / cell);
                index = Math.max(0, Math.min(sections.size() - 1, index));
                if (index != selected) {
                    selected = index;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    invalidate();
                    if (listener != null) listener.onSectionSelected(sections.get(index));
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                selected = -1;
                invalidate();
                if (listener != null) listener.onSectionReleased();
                return true;
        }
        return super.onTouchEvent(event);
    }
}
