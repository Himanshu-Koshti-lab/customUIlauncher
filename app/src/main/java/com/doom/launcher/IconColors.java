package com.doom.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import androidx.core.graphics.ColorUtils;

/**
 * Picks a tile color that matches an app's icon: the icon's most prominent vivid hue, toned so
 * white icons/labels stay readable on it. Grey or black-and-white icons get a matching neutral.
 */
final class IconColors {
    private static final int SAMPLE_SIZE = 48;
    private static final int HUE_BUCKETS = 36; // 10° each
    /** Below this share of vivid pixels the icon counts as neutral (grey / black / white). */
    private static final float MIN_VIVID_SHARE = 0.04f;

    private IconColors() {}

    /** Tile color for {@code icon}; falls back to the Metro palette if the icon can't be read. */
    static int tileColor(Drawable icon, String packageName) {
        try {
            Integer color = dominantColor(icon);
            if (color != null) return color;
        } catch (RuntimeException ignored) {
            // Some drawables fail to draw off-screen; use the palette instead.
        }
        return MetroColors.forKey(packageName);
    }

    private static Integer dominantColor(Drawable icon) {
        if (icon == null) return null;
        Bitmap bitmap = Bitmap.createBitmap(SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888);
        Drawable copy = icon.getConstantState() != null ? icon.getConstantState().newDrawable().mutate() : icon;
        copy.setBounds(0, 0, SAMPLE_SIZE, SAMPLE_SIZE);
        copy.draw(new Canvas(bitmap));
        int[] pixels = new int[SAMPLE_SIZE * SAMPLE_SIZE];
        bitmap.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE);
        bitmap.recycle();

        float[] weight = new float[HUE_BUCKETS];
        float[] sumR = new float[HUE_BUCKETS], sumG = new float[HUE_BUCKETS], sumB = new float[HUE_BUCKETS];
        float[] hsv = new float[3];
        int opaque = 0, vivid = 0;
        long greyR = 0, greyG = 0, greyB = 0;
        int greyCount = 0;

        for (int p : pixels) {
            if (Color.alpha(p) < 128) continue;
            opaque++;
            Color.colorToHSV(p, hsv);
            float s = hsv[1], v = hsv[2];
            if (s >= 0.3f && v >= 0.25f) {
                vivid++;
                int b = Math.min(HUE_BUCKETS - 1, (int) (hsv[0] / (360f / HUE_BUCKETS)));
                float w = s * v; // favour strong, bright colors
                weight[b] += w;
                sumR[b] += Color.red(p) * w;
                sumG[b] += Color.green(p) * w;
                sumB[b] += Color.blue(p) * w;
            } else if (v < 0.92f) {
                // Not white: remember greys/blacks for neutral icons. (White plates are ignored.)
                greyR += Color.red(p);
                greyG += Color.green(p);
                greyB += Color.blue(p);
                greyCount++;
            }
        }
        if (opaque == 0) return null;

        int color;
        if (vivid >= opaque * MIN_VIVID_SHARE) {
            // Merge each bucket with its neighbours so a hue split across two buckets still wins.
            int best = 0;
            float bestWeight = -1;
            for (int i = 0; i < HUE_BUCKETS; i++) {
                float w = weight[i] + 0.5f * (weight[(i + 1) % HUE_BUCKETS]
                        + weight[(i + HUE_BUCKETS - 1) % HUE_BUCKETS]);
                if (w > bestWeight) {
                    bestWeight = w;
                    best = i;
                }
            }
            float w = weight[best];
            if (w <= 0) return null;
            color = Color.rgb(Math.round(sumR[best] / w), Math.round(sumG[best] / w),
                    Math.round(sumB[best] / w));
            return toneForTile(color, 0.35f, 0.42f);
        }
        if (greyCount > 0) {
            color = Color.rgb((int) (greyR / greyCount), (int) (greyG / greyCount),
                    (int) (greyB / greyCount));
        } else {
            color = Color.GRAY; // pure white icon
        }
        return toneForTile(color, 0f, 0.28f); // darker, so grey icons stand out
    }

    /** Keep the hue, but clamp lightness (and boost saturation) so white text reads well. */
    private static int toneForTile(int color, float minSaturation, float maxLightness) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        hsl[1] = Math.max(hsl[1], minSaturation);
        hsl[2] = Math.max(0.2f, Math.min(maxLightness, hsl[2]));
        return ColorUtils.HSLToColor(hsl);
    }
}
