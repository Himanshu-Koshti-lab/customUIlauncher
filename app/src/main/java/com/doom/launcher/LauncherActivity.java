package com.doom.launcher;

import android.app.ActivityOptions;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Menu;
import android.os.PowerManager;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.SystemBarStyle;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Windows 8 style home screen: a to-do tile and pinned app tiles (drag-resizable),
 * plus a top-right toggle that shows/hides all apps.
 */
public class LauncherActivity extends AppCompatActivity implements StartAdapter.Listener {

    private static final int MENU_PIN = 1;
    private static final int MENU_UNPIN = 2;
    private static final int MENU_RESIZE = 3;
    private static final int MENU_MOVE_FRONT = 4;
    private static final int MENU_APP_INFO = 5;
    private static final int MENU_UNINSTALL = 6;
    private static final int MENU_GROUP_TILES = 1;
    private static final int MENU_SHOW_TODO = 99;
    private static final int MENU_DEFAULT_HOME = 100;
    private static final int MENU_REMOVE_DEFAULT_HOME = 101;

    private final ActivityResultLauncher<Intent> homeRoleRequest = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), this::onHomeRoleResult);

    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private LauncherStore store;
    private AppRepository repository;
    private LauncherApps launcherApps;
    private StartAdapter adapter;

    private View root;
    /** Set when we leave the foreground for another app, so returning home can animate in. */
    private boolean returnFromApp;
    private View header;
    private final TileZoomAnimator tileZoom = new TileZoomAnimator();
    /** Centre of the tile/row an app was last opened from, in window coordinates. */
    @Nullable private PointF lastLaunchPoint;
    private RecyclerView list;
    /** Whether the app list is shown below the tiles. Hidden on launch; the index bar reveals it. */
    private boolean showAllApps;
    private TextView emptyHint;
    private AlphabetScrollBar alphabetBar;
    private TextView sectionPreview;
    /** First list position of each index section ("#", A-Z) that has apps; sorted, "#" first. */
    private final TreeMap<String, Integer> sectionPositions = new TreeMap<>();

    /** Mountain Standard Time, UTC−07:00 all year (Phoenix doesn't observe DST). Default zone. */
    private static final String MST_ZONE = "America/Phoenix";
    /** Saved value meaning "follow the device time zone". */
    private static final String DEVICE_ZONE = "device";

    /** Zones offered in the header picker (the device zone is always offered first). */
    private static final String[] CLOCK_ZONES = {
            MST_ZONE,
            "UTC",
            "Pacific/Honolulu",
            "America/Anchorage",
            "America/Los_Angeles",
            "America/Denver",
            "America/Chicago",
            "America/New_York",
            "America/Sao_Paulo",
            "Europe/London",
            "Europe/Paris",
            "Europe/Berlin",
            "Africa/Cairo",
            "Africa/Johannesburg",
            "Europe/Moscow",
            "Asia/Dubai",
            "Asia/Karachi",
            "Asia/Kolkata",
            "Asia/Kathmandu",
            "Asia/Dhaka",
            "Asia/Bangkok",
            "Asia/Singapore",
            "Asia/Shanghai",
            "Asia/Tokyo",
            "Australia/Sydney",
            "Pacific/Auckland",
    };
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private TextView zoneSelect;
    private TextView clock;

    /** Redraws the clock, then reschedules itself for the start of the next minute. */
    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            mainHandler.postDelayed(this, 60_000 - (now % 60_000) + 50);
        }
    };

    private List<AppInfo> allApps = new ArrayList<>();
    private List<LauncherStore.Pin> pins = new ArrayList<>();
    private List<LauncherStore.Todo> todos = new ArrayList<>();
    private boolean appsLoaded;
    /** Pin key of the tile currently in resize mode, or null. */
    private String resizingKey;

    /** Reload when apps are installed, removed or updated. */
    private final LauncherApps.Callback packageCallback = new LauncherApps.Callback() {
        @Override
        public void onPackageRemoved(String packageName, UserHandle user) {
            loadApps();
        }

        @Override
        public void onPackageAdded(String packageName, UserHandle user) {
            loadApps();
        }

        @Override
        public void onPackageChanged(String packageName, UserHandle user) {
            loadApps();
        }

        @Override
        public void onPackagesAvailable(String[] packageNames, UserHandle user, boolean replacing) {
            loadApps();
        }

        @Override
        public void onPackagesUnavailable(String[] packageNames, UserHandle user, boolean replacing) {
            loadApps();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Draw behind transparent system bars. API 35+ enforces this anyway; on Android 14 it
        // has to be requested, otherwise the bars get the theme's accent colour.
        EdgeToEdge.enable(this,
                SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);

        store = new LauncherStore(this);
        repository = new AppRepository(this);
        launcherApps = getSystemService(LauncherApps.class);
        todos = store.loadTodos();

        root = findViewById(R.id.root);
        header = findViewById(R.id.header);
        list = findViewById(R.id.start_grid);
        emptyHint = findViewById(R.id.empty_hint);
        alphabetBar = findViewById(R.id.alphabet_bar);
        sectionPreview = findViewById(R.id.section_preview);

        // We draw edge-to-edge, so keep content clear of the system bars.
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        adapter = new StartAdapter(this, store.getTilesPerRow());
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        list.setItemAnimator(null); // the tile grid rebinds in place; avoid cross-fade flicker

        alphabetBar.setListener(new AlphabetScrollBar.Listener() {
            @Override
            public void onSectionSelected(String section) {
                jumpToSection(section);
            }

            @Override
            public void onSectionReleased() {
                sectionPreview.setVisibility(View.GONE);
            }
        });

        findViewById(R.id.options).setOnClickListener(this::showOptionsMenu);

        zoneSelect = findViewById(R.id.zone_select);
        clock = findViewById(R.id.clock);
        zoneSelect.setOnClickListener(v -> showZonePicker());
        updateClock();

        // A home screen should never close itself on Back.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (resizingKey != null) stopResizing();
                else if (showAllApps) hideAllApps();
                else list.smoothScrollToPosition(0);
            }
        });

        launcherApps.registerCallback(packageCallback, mainHandler);
        loadApps();
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already on Start: leave resize mode, hide the app list, go to the top.
        if (resizingKey != null) stopResizing();
        if (showAllApps) hideAllApps();
        else if (list != null) list.smoothScrollToPosition(0);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (returnFromApp) {
            returnFromApp = false;
            // Keep Start hidden until the system's return transition actually begins, which is when
            // our window regains focus; the timeout covers the case where focus never arrives.
            root.setAlpha(0f);
            mainHandler.postDelayed(returnHomeAnimation, RETURN_HOME_MAX_WAIT_MS);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && mainHandler.hasCallbacks(returnHomeAnimation)) {
            mainHandler.removeCallbacks(returnHomeAnimation);
            returnHomeAnimation.run();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Only animate when coming back from an app, not when the screen turns back on.
        returnFromApp = getSystemService(PowerManager.class).isInteractive();
        // Never leave Start stuck mid-zoom, e.g. if the screen turns off during the animation.
        mainHandler.removeCallbacks(returnHomeAnimation);
        mainHandler.removeCallbacks(settleIfLaunchStalled);
        tileZoom.cancel();
        root.setAlpha(1f);
    }

    private static final long RETURN_HOME_MAX_WAIT_MS = 300;
    /** If Start is still visible this long after zooming past for a launch, bring it back. */
    private static final long LAUNCH_STALL_MS = 800;

    /** Per-tile zoom-out ripple, centred on the tile the user last launched an app from. */
    private final Runnable returnHomeAnimation = () -> {
        tileZoom.settle(list, lastLaunchPoint, header, alphabetBar);
        lastLaunchPoint = null;
        root.setAlpha(1f);
    };

    /**
     * The launched app didn't cover Start (it's translucent, or the launch silently failed), so
     * don't leave the home screen blank behind it.
     */
    private final Runnable settleIfLaunchStalled = () -> {
        if (tileZoom.isAway() && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            tileZoom.settle(list, lastLaunchPoint, header, alphabetBar);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        mainHandler.removeCallbacks(clockTick);
        clockTick.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(clockTick);
        if (resizingKey != null) stopResizing();
    }

    @Override
    protected void onDestroy() {
        launcherApps.unregisterCallback(packageCallback);
        loader.shutdownNow();
        super.onDestroy();
    }

    // ---- Data ---------------------------------------------------------------------------------

    private void loadApps() {
        if (loader.isShutdown()) return;
        loader.execute(() -> {
            List<AppInfo> apps = repository.loadApps();
            List<LauncherStore.Pin> seeded = store.hasSavedPins() ? null : repository.defaultPins(apps);
            mainHandler.post(() -> {
                if (isDestroyed()) return;
                allApps = apps;
                pins = seeded != null ? seeded : store.loadPins();
                // Add the to-do tile at the top once (first run, or upgrade from an older build).
                if (!store.isTodoSeeded()) {
                    if (!containsTodo()) pins.add(0, newTodoPin());
                    store.setTodoSeeded();
                    store.savePins(pins);
                } else if (seeded != null) {
                    store.savePins(pins);
                }
                appsLoaded = true;
                render();
            });
        });
    }

    private LauncherStore.Pin newTodoPin() {
        int columns = store.getTilesPerRow();
        // Full width; tall enough for 3 tasks on smaller cells.
        return new LauncherStore.Pin(LauncherStore.TODO_KEY, columns, columns <= 2 ? 1 : 2);
    }

    private boolean containsTodo() {
        for (LauncherStore.Pin p : pins) if (p.isTodo()) return true;
        return false;
    }

    private Map<String, AppInfo> appsByKey() {
        Map<String, AppInfo> map = new HashMap<>();
        for (AppInfo app : allApps) map.put(app.key(), app);
        return map;
    }

    /** Rebuild the Start screen from pins, to-dos and toggle state. */
    private void render() {
        Map<String, AppInfo> byKey = appsByKey();

        // Drop pins whose app was uninstalled.
        boolean pruned = false;
        for (Iterator<LauncherStore.Pin> it = pins.iterator(); it.hasNext(); ) {
            LauncherStore.Pin pin = it.next();
            if (!pin.isTodo() && !byKey.containsKey(pin.key)) {
                it.remove();
                pruned = true;
            }
        }
        if (pruned) store.savePins(pins);

        Set<String> pinnedKeys = new HashSet<>();
        for (LauncherStore.Pin pin : pins) pinnedKeys.add(pin.key);

        List<StartAdapter.Item> items = new ArrayList<>();
        if (!pins.isEmpty()) items.add(StartAdapter.Item.grid());

        boolean showAll = showAllApps;
        if (showAll) items.add(StartAdapter.Item.header(getString(R.string.all_apps)));

        // The index bar lists every section that has apps, whether or not the list is shown.
        sectionPositions.clear();
        for (AppInfo app : allApps) {
            String section = AlphabetScrollBar.sectionFor(app.label);
            if (section != null && !sectionPositions.containsKey(section)) {
                sectionPositions.put(section, showAll ? items.size() : -1);
            }
            if (showAll) items.add(StartAdapter.Item.appRow(app, pinnedKeys.contains(app.key())));
        }

        adapter.submit(items, pins, byKey, todos, resizingKey);
        alphabetBar.setSections(new ArrayList<>(sectionPositions.keySet()));
        alphabetBar.setVisibility(sectionPositions.isEmpty() ? View.GONE : View.VISIBLE);
        emptyHint.setVisibility(appsLoaded && pins.isEmpty() && !showAll ? View.VISIBLE : View.GONE);
    }

    // ---- Alphabet index -----------------------------------------------------------------------

    /**
     * Reveal the app list if it's hidden, scroll so the first app under {@code section} is at
     * the top, and highlight that section's apps.
     */
    private void jumpToSection(String section) {
        sectionPreview.setText(section);
        sectionPreview.setVisibility(View.VISIBLE);

        boolean needsRender = !showAllApps || resizingKey != null;
        if (resizingKey != null) {
            resizingKey = null;
            store.savePins(pins);
        }
        if (needsRender) {
            showAllApps = true;
            render(); // also fills in the list position of each section
        }
        adapter.setHighlightSection(section);

        Integer position = sectionPositions.get(section);
        if (position == null || position < 0) return;
        ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(position, 0);
    }

    /** Hide the app list (opened from the index bar) and go back to the top of Start. */
    private void hideAllApps() {
        showAllApps = false;
        adapter.setHighlightSection(null);
        render();
        list.scrollToPosition(0);
    }

    // ---- Header clock -------------------------------------------------------------------------

    /** The selected zone; MST until the user picks another one. */
    private ZoneId clockZone() {
        String id = store.getClockZone();
        if (id == null) return ZoneId.of(MST_ZONE);
        if (DEVICE_ZONE.equals(id)) return ZoneId.systemDefault();
        try {
            return ZoneId.of(id);
        } catch (DateTimeException e) {
            return ZoneId.of(MST_ZONE);
        }
    }

    /** "America/New_York" -> "New York"; the MST zone is shown as "MST". */
    private static String cityName(ZoneId zone) {
        String id = zone.getId();
        if (MST_ZONE.equals(id)) return "MST";
        return id.substring(id.lastIndexOf('/') + 1).replace('_', ' ');
    }

    private static String offsetLabel(ZoneId zone) {
        String offset = ZonedDateTime.now(zone).getOffset().getId();
        return "UTC" + ("Z".equals(offset) ? "" : offset);
    }

    private void updateClock() {
        if (clock == null) return;
        ZoneId zone = clockZone();
        clock.setText(ZonedDateTime.now(zone).format(CLOCK_FORMAT));
        zoneSelect.setText(getString(R.string.zone_label, cityName(zone)));
    }

    private void showZonePicker() {
        String saved = store.getClockZone();
        if (saved == null) saved = MST_ZONE;
        String[] labels = new String[CLOCK_ZONES.length + 1];
        ZoneId device = ZoneId.systemDefault();
        labels[0] = getString(R.string.device_time_zone) + " – " + cityName(device)
                + "  " + ZonedDateTime.now(device).format(CLOCK_FORMAT);
        int checked = DEVICE_ZONE.equals(saved) ? 0 : -1;
        for (int i = 0; i < CLOCK_ZONES.length; i++) {
            ZoneId zone = ZoneId.of(CLOCK_ZONES[i]);
            labels[i + 1] = cityName(zone) + " (" + offsetLabel(zone) + ")  "
                    + ZonedDateTime.now(zone).format(CLOCK_FORMAT);
            if (CLOCK_ZONES[i].equals(saved)) checked = i + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.select_time_zone)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    store.setClockZone(which == 0 ? DEVICE_ZONE : CLOCK_ZONES[which - 1]);
                    updateClock();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---- Options menu -------------------------------------------------------------------------

    private void showOptionsMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        Menu menu = popup.getMenu();
        int current = store.getTilesPerRow();
        for (int n = LauncherStore.MIN_TILES_PER_ROW; n <= LauncherStore.MAX_TILES_PER_ROW; n++) {
            // Item id == tile count, so the click handler can use it directly.
            menu.add(MENU_GROUP_TILES, n, n, getString(R.string.tiles_per_row, n))
                    .setChecked(n == current);
        }
        menu.setGroupCheckable(MENU_GROUP_TILES, true, true);

        if (!containsTodo()) {
            menu.add(Menu.NONE, MENU_SHOW_TODO, 9, R.string.show_todo_tile);
        }

        if (isDefaultHome()) {
            menu.add(Menu.NONE, MENU_REMOVE_DEFAULT_HOME, 10, R.string.remove_default_home);
        } else {
            menu.add(Menu.NONE, MENU_DEFAULT_HOME, 10, R.string.set_default_home);
        }

        popup.setOnMenuItemClickListener(menuItem -> {
            int id = menuItem.getItemId();
            if (id == MENU_DEFAULT_HOME) {
                requestDefaultHome();
            } else if (id == MENU_REMOVE_DEFAULT_HOME) {
                removeDefaultHome();
            } else if (id == MENU_SHOW_TODO) {
                pins.add(0, newTodoPin());
                store.savePins(pins);
                render();
                list.scrollToPosition(0);
            } else {
                setTilesPerRow(id);
            }
            return true;
        });
        popup.show();
    }

    private void setTilesPerRow(int n) {
        if (n == store.getTilesPerRow()) return;
        store.setTilesPerRow(n);
        adapter.setTilesPerRow(n);
        render();
    }

    // ---- Default home app ---------------------------------------------------------------------

    private boolean isDefaultHome() {
        RoleManager roleManager = getSystemService(RoleManager.class);
        return roleManager != null && roleManager.isRoleHeld(RoleManager.ROLE_HOME);
    }

    /** Ask the system to make us the home app; fall back to the Settings screen if needed. */
    private void requestDefaultHome() {
        RoleManager roleManager = getSystemService(RoleManager.class);
        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
            try {
                homeRoleRequest.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME));
                return;
            } catch (ActivityNotFoundException ignored) {
                // fall through to Settings
            }
        }
        openHomeSettings();
    }

    private void onHomeRoleResult(ActivityResult result) {
        if (isDefaultHome()) {
            Toast.makeText(this, getString(R.string.default_home_set, getString(R.string.app_name)),
                    Toast.LENGTH_SHORT).show();
        } else {
            // Dialog dismissed, or the system stopped showing it after repeated denials.
            openHomeSettings();
        }
    }

    /**
     * Android doesn't let an app give up the home role itself, so open the system
     * "Default home app" screen where the user can pick another launcher.
     */
    private void removeDefaultHome() {
        Toast.makeText(this, getString(R.string.pick_other_home, getString(R.string.app_name)),
                Toast.LENGTH_LONG).show();
        openHomeSettings();
    }

    private void openHomeSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    // ---- Tile resizing ------------------------------------------------------------------------

    private void startResizing(String key) {
        resizingKey = key;
        render();
    }

    private void stopResizing() {
        resizingKey = null;
        store.savePins(pins);
        render();
    }

    @Override
    public void onTapWhileResizing() {
        stopResizing();
    }

    @Override
    public void onResizeFinished(LauncherStore.Pin pin) {
        store.savePins(pins); // pin was updated in place by the grid
    }

    // ---- To-dos -------------------------------------------------------------------------------

    @Override
    public void onTodoToggled(int index, boolean done) {
        todos.set(index, new LauncherStore.Todo(todos.get(index).text, done));
        store.saveTodos(todos);
    }

    @Override
    public void onTodoAdd() {
        showTodoDialog(-1);
    }

    @Override
    public void onTodoEdit(int index) {
        showTodoDialog(index);
    }

    @Override
    public void onTodoDelete(int index) {
        todos.remove(index);
        store.saveTodos(todos);
        render();
    }

    /** Add (index == -1) or edit a task. */
    private void showTodoDialog(int index) {
        boolean adding = index < 0;
        String oldText = adding ? "" : todos.get(index).text;

        EditText input = new EditText(this);
        input.setText(oldText);
        input.setSelection(oldText.length());
        input.setHint(R.string.todo_hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);

        FrameLayout container = new FrameLayout(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(adding ? R.string.todo_add : R.string.todo_edit_title)
                .setView(container)
                .setPositiveButton(R.string.save, (d, w) -> saveTodoText(index, input.getText().toString()))
                .setNegativeButton(R.string.cancel, null);
        if (!adding) builder.setNeutralButton(R.string.delete, (d, w) -> onTodoDelete(index));
        AlertDialog dialog = builder.create();

        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveTodoText(index, input.getText().toString());
                dialog.dismiss();
                return true;
            }
            return false;
        });

        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.show();
        input.requestFocus();
    }

    /** Save a new (index == -1) or edited task. Saving empty text deletes an existing task. */
    private void saveTodoText(int index, String rawText) {
        String text = rawText.trim();
        if (index < 0) {
            if (text.isEmpty()) return;
            todos.add(new LauncherStore.Todo(text, false));
            adapter.scrollTodosToEndOnce();
        } else if (text.isEmpty()) {
            todos.remove(index);
        } else {
            LauncherStore.Todo old = todos.get(index);
            // Keep the done state only if the task itself didn't change.
            todos.set(index, new LauncherStore.Todo(text, text.equals(old.text) && old.done));
        }
        store.saveTodos(todos);
        render();
    }

    // ---- Tile / row actions -------------------------------------------------------------------

    @Override
    public void onOpen(AppInfo app, View view) {
        Rect bounds = new Rect();
        view.getGlobalVisibleRect(bounds);
        int[] loc = new int[2];
        view.getLocationInWindow(loc);
        lastLaunchPoint = new PointF(loc[0] + view.getWidth() / 2f, loc[1] + view.getHeight() / 2f);
        // Start zooms past the camera from the tapped tile. Where the system honours it, the app
        // also zooms up out of the tile; newer Android versions substitute their own transition.
        Bundle options = ActivityOptions.makeScaleUpAnimation(
                view, 0, 0, view.getWidth(), view.getHeight()).toBundle();
        try {
            launcherApps.startMainActivity(app.component, app.user, bounds, options);
            tileZoom.zoomPast(list, lastLaunchPoint, header, alphabetBar);
            mainHandler.removeCallbacks(settleIfLaunchStalled);
            mainHandler.postDelayed(settleIfLaunchStalled, LAUNCH_STALL_MS);
        } catch (RuntimeException e) { // ActivityNotFoundException, SecurityException
            Toast.makeText(this, getString(R.string.launch_failed, app.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onTileLongPress(LauncherStore.Pin pin, AppInfo app, View view) {
        PopupMenu popup = new PopupMenu(this, view);
        Menu menu = popup.getMenu();
        menu.add(Menu.NONE, MENU_RESIZE, 0, R.string.resize);
        menu.add(Menu.NONE, MENU_MOVE_FRONT, 1, R.string.move_to_front);
        menu.add(Menu.NONE, MENU_UNPIN, 2, app == null ? R.string.hide_todo_tile : R.string.unpin_from_start);
        if (app != null) {
            menu.add(Menu.NONE, MENU_APP_INFO, 3, R.string.app_info);
            menu.add(Menu.NONE, MENU_UNINSTALL, 4, R.string.uninstall);
        }
        popup.setOnMenuItemClickListener(menuItem -> {
            handleMenu(menuItem.getItemId(), pin.key, app);
            return true;
        });
        popup.show();
    }

    @Override
    public void onRowLongPress(AppInfo app, boolean pinned, View view) {
        PopupMenu popup = new PopupMenu(this, view);
        Menu menu = popup.getMenu();
        menu.add(Menu.NONE, pinned ? MENU_UNPIN : MENU_PIN, 0,
                pinned ? R.string.unpin_from_start : R.string.pin_to_start);
        menu.add(Menu.NONE, MENU_APP_INFO, 1, R.string.app_info);
        menu.add(Menu.NONE, MENU_UNINSTALL, 2, R.string.uninstall);
        popup.setOnMenuItemClickListener(menuItem -> {
            handleMenu(menuItem.getItemId(), app.key(), app);
            return true;
        });
        popup.show();
    }

    /** @param app null for the to-do tile */
    private void handleMenu(int id, String key, AppInfo app) {
        switch (id) {
            case MENU_PIN:
                pins.add(new LauncherStore.Pin(key, 1, 1));
                break;
            case MENU_UNPIN:
                pins.removeIf(p -> p.key.equals(key));
                if (key.equals(resizingKey)) resizingKey = null;
                break;
            case MENU_RESIZE:
                startResizing(key);
                return;
            case MENU_MOVE_FRONT:
                for (int i = 0; i < pins.size(); i++) {
                    if (pins.get(i).key.equals(key)) {
                        LauncherStore.Pin pin = pins.remove(i);
                        // The to-do tile stays on top unless it's the one being moved.
                        int at = (!pin.isTodo() && !pins.isEmpty() && pins.get(0).isTodo()) ? 1 : 0;
                        pins.add(at, pin);
                        break;
                    }
                }
                break;
            case MENU_APP_INFO:
                if (app != null) launcherApps.startAppDetailsActivity(app.component, app.user, null, null);
                return;
            case MENU_UNINSTALL:
                if (app != null) {
                    startActivity(new Intent(Intent.ACTION_DELETE,
                            Uri.fromParts("package", app.component.getPackageName(), null)));
                }
                return; // the package callback re-renders after removal
            default:
                return;
        }
        store.savePins(pins);
        render();
    }
}
