# customUIlauncher

A Windows 8 / Metro-style home screen launcher for Android.

## Features

- **Live tile grid** – pin apps to Start as tiles; long-press a tile to resize it
  (drag the corner), move it to the front, or unpin it.
- **To-do tile** – a built-in tile for quick tasks: add, edit, check off and delete.
- **All apps** – the arrow at the top right shows/hides the full app list below the tiles.
  Long-press an app to pin it, open App info, or uninstall.
- **World clock header** – the header shows a time-zone selector (default **MST**) with the
  current time beside it in 24-hour format. Tap the zone to pick another one; the choice is saved.
- **Options menu** (⋮) – choose 2, 3 or 4 tiles per row, restore the to-do tile, and set or
  remove the launcher as the default home app.

## Requirements

- Android 15 (API 35) or newer
- Android Studio with JDK 17+

## Build & run

```sh
./gradlew :app:assembleDebug        # build the debug APK
./gradlew :app:installDebug         # install on a connected device/emulator
```

Then press Home and choose **customULauncher**, or open the app and use
**⋮ → Set as default home app**.

## Project layout

| File | Purpose |
|------|---------|
| `LauncherActivity.java` | Home screen, header clock, menus, to-dos |
| `StartAdapter.java` / `TileGridLayout.java` | Tile grid and app list rendering |
| `AppRepository.java` | Loads installed launchable apps |
| `LauncherStore.java` | Persists pins, to-dos and settings |
| `MetroColors.java` | Tile colours |
