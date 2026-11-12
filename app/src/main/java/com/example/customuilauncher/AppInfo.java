package com.example.customuilauncher;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;

/** A launchable app as shown on the Start screen. */
final class AppInfo {
    final String label;
    final ComponentName component;
    final UserHandle user;
    final Drawable icon;
    final int tileColor;

    AppInfo(String label, ComponentName component, UserHandle user, Drawable icon) {
        this.label = label;
        this.component = component;
        this.user = user;
        this.icon = icon;
        this.tileColor = MetroColors.forKey(component.getPackageName());
    }

    /** Stable key used for persisting pins. */
    String key() {
        return component.flattenToString();
    }

    /** A Drawable can only live in one View at a time, so hand each View its own copy. */
    Drawable newIcon() {
        Drawable.ConstantState state = icon.getConstantState();
        return state != null ? state.newDrawable() : icon;
    }
}
