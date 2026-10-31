# crease

A text-only terminal launcher for foldable Android phones, in the spirit of Pro Launcher.
Android 11+. No icons, no internet permission, about 90 KB.

## Install

Download [`crease.apk`](https://github.com/JonathanRiche/crease/releases/latest/download/crease.apk)
on the phone and open it, or build it yourself:

    ./gradlew assembleRelease
    adb install app/build/outputs/apk/release/app-release.apk

Then pick crease as the home app in
Settings > Apps > Default apps > Home app. To go back, choose your old launcher there,
or run `settings` in crease.

## Layouts

- **Inner screen:** clock, pins, folder tree and widgets on the left half; terminal on the right.
  The gap between the panes sits on the crease.
- **Cover screen:** one column. Widgets take at most 40% of the height and hide while the keyboard is open.

Widgets belong to the screen you add them on, so each screen keeps its own set.

## Commands

Tap any green line to run it. Typing shows live suggestions above the prompt.

| Command | What it does |
| --- | --- |
| `<app>` | Opens the best match. Several equal matches are listed instead. |
| `ls [folder]` | Lists folders and unfiled apps, or one folder |
| `folder new\|rm <name>` | Creates or removes a folder. Apps stay installed. |
| `folder add\|del <name> <app>` | Puts an app in a folder or takes it out |
| `pin\|unpin <app>` | Adds or removes an app in the shortcut row |
| `alias <name> <app>`, `unalias <name>` | Short names |
| `hide <app>`, `unhide [app]` | Hides apps from lists |
| `widget add [search]` | Lists widgets to add to this screen |
| `widget ls`, `widget rm <n>`, `widget size <n> <dp>` | Manages this screen's widgets |
| `web <query>` | Searches DuckDuckGo in your browser |
| `info\|uninstall <app>` | Opens app info or the uninstall dialog |
| `settings`, `clear` | Opens system settings; clears the output |
| `theme [name]`, `keyboard on\|off` | Accent colour; opens the keyboard automatically on home |

Commands win over app names. To open an app called `Settings`, use `open settings`.

## Development

Needs JDK 17 and the Android SDK (API 36). `./gradlew testDebugUnitTest` runs the command logic tests.
`Shell.kt` holds that logic and has no Android imports; `MainActivity.kt` is the UI and widget host.
