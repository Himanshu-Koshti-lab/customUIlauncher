package com.doom.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.Settings;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;

/** Loads installed launchable apps. Safe to call off the main thread. */
final class AppRepository {
    private final Context context;
    private final LauncherApps launcherApps;

    AppRepository(Context context) {
        this.context = context.getApplicationContext();
        this.launcherApps = context.getSystemService(LauncherApps.class);
    }

    List<AppInfo> loadApps() {
        UserHandle user = Process.myUserHandle();
        int density = context.getResources().getDisplayMetrics().densityDpi;
        List<AppInfo> apps = new ArrayList<>();
        for (LauncherActivityInfo info : launcherApps.getActivityList(null, user)) {
            if (info.getComponentName().getPackageName().equals(context.getPackageName())) {
                continue; // don't list the launcher itself
            }
            apps.add(new AppInfo(
                    String.valueOf(info.getLabel()),
                    info.getComponentName(),
                    user,
                    info.getIcon(density)));
        }
        Collator collator = Collator.getInstance();
        apps.sort((a, b) -> collator.compare(a.label, b.label));
        return apps;
    }

    /** First-run tiles: phone, messages, browser, camera, gallery, settings (whatever exists). */
    List<LauncherStore.Pin> defaultPins(List<AppInfo> apps) {
        Intent[] intents = {
                new Intent(Intent.ACTION_DIAL),
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
                new Intent(MediaStore.ACTION_IMAGE_CAPTURE),
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY),
                new Intent(Settings.ACTION_SETTINGS),
        };
        PackageManager pm = context.getPackageManager();
        List<LauncherStore.Pin> pins = new ArrayList<>();
        List<String> usedPackages = new ArrayList<>();
        for (Intent intent : intents) {
            ResolveInfo ri = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri == null || ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if ("android".equals(pkg) || usedPackages.contains(pkg)) continue; // chooser / duplicate
            for (AppInfo app : apps) {
                if (app.component.getPackageName().equals(pkg)) {
                    // Make the first tile wide for a classic Metro look.
                    pins.add(new LauncherStore.Pin(app.key(), pins.isEmpty() ? 2 : 1, 1));
                    usedPackages.add(pkg);
                    break;
                }
            }
        }
        return pins;
    }
}
