## Pro editor engine (Sora Editor + TextMate)

Not compiled here - build it and send me any errors.

- New `ProEditor.kt`: drop-in replacement for `CodeEditText` (same API used by ProjectActivity), built on
  Sora Editor 0.24.6 with TextMate grammars. Gives real syntax highlighting (Kotlin, Java, Gradle, XML, HTML,
  JS/JSON, Python, Markdown, Lua), line numbers, pin line numbers, sticky scroll, block lines, bracket matching,
  whitespace + line-ending display, word wrap, ligatures, completion popup, magnifier, find highlight.
- New `EditorSettings.kt`: settings screen (tap the gear chip in the symbol bar): theme (darcula, ayu-dark,
  solarized_dark, quietlight), font size, tab size and all toggles above. Applied live, saved in prefs.
- Assets: `assets/textmate/` (grammars + themes from the Sora demo) and `assets/fonts/JetBrainsMono-*.ttf`.
- Per-tab undo/redo now lives in the editor buffer kept inside `UndoHistory.content`.
- build.gradle: sora `editor`, `language-textmate`, `oniguruma-native` 0.24.6 + core library desugaring.
- Not included: code-folding gutter (the library only has the data model), LSP, C/C++/Dart/YAML grammars
  (C-style files use the Java grammar for now). Drop a grammar into assets/textmate and add it to languages.json.
- `CodeEditText.kt` is kept (only `UndoHistory` is still used); it can be deleted later.

## Red lines + SDK check (all templates, Flutter too)

- Red only for real errors (`e:` lines, `: error:`, FAILURE, Execution failed, exception class lines, Caused by). Names like "ErrorHandler" or "Exception" inside a normal line are no longer red, and a run that ends with BUILD / CONFIGURE SUCCESSFUL shows no red lines and an empty Problems tab.
- Before every sync / build the project's needs are read (compileSdk platform, build-tools, NDK, CMake, Flutter SDK) and compared with the SDK Manager. Something missing -> dialog "SDK parts missing": Install now (opens the SDK Manager with exactly those packages) / Run anyway / Cancel. Automatic sync after a file save skips quietly.

## Build output panel

- No more jumping up/down: the output redraws at most every 150 ms and scrolls in the same layout pass; it follows the newest line only while you are at the bottom (scroll up to read, scroll down to follow again).
- Text is selectable (long-press), new Copy button next to Share. Copy / Share give the clean text without line numbers.
- Java stack frames are folded into one line in the view (Copy / Share keep everything).
- Fail fast: at the first "FAILURE: Build failed", the build is stopped 1.2 s later (after Gradle printed the reason), status = Build failed, fix chips shown. Successful builds are unchanged.

## Termux: remaining pieces

- proot now runs with `--link2symlink` (apt / dpkg / git need hard links, app storage forbids them) and binds `/usr`, `/bin`, `/tmp`, so `#!/usr/bin/env python`, `#!/bin/sh` and /tmp work like on a normal Linux.
- Newer Termux variables (`TERMUX__PREFIX`, `TERMUX__HOME`, `TERMUX_APP__PACKAGE_NAME` ...) are set.
- `termux-setup-storage` (creates ~/storage/shared, downloads, dcim ...), `termux-reload-settings` and a starter `~/.bashrc` (created once, never overwritten).
- Volume Down = Ctrl, like Termux.

## Flutter package in the SDK Manager

- New **Flutter** tab in the SDK Manager: Install / Reinstall / Uninstall the Flutter SDK (stored in `home/flutter`). Download = newest arm64 .zip/.tar.gz release of `mumumusuc/termux-flutter` (change with prefs "flutter" -> "repo").
- Flutter Activity template: if the package is not installed, tapping it shows "Flutter package not installed -> Install Flutter package" (opens the SDK Manager and installs). If it is installed the template opens normally. New-project screen also lists the missing Flutter SDK.

## ▶ build menu + Flutter fixes

- Tap ▶ -> popup: Build debug (APK), Build release (APK), Bundle release (AAB), your saved tasks, Add task..., Custom..., Remove task.... Long-press ▶ still opens the variant dialog. The APK found/installed matches the picked variant.
- Flutter template: `flutter.sdk` is written to local.properties automatically when a Flutter SDK is found (Termux home, app files, sdcard). If it is missing the error shows a "Set Flutter SDK" chip that asks for the folder and re-runs.

## Project sync: big improvements

- **Speed**: nested modules (`:feature:login`) are synced on their own, `buildSrc` and `libs.versions.toml` count as sync files, heap sized by RAM.
- **Live progress**: determinate bar under the status text, phases (Configuring / Downloading file name / n of total tasks, %, ETA), same text in the notification.
- **Auto-fix** (retries by itself): flaky network x3 with backoff, out-of-memory / killed (exit 137) with a bigger/smaller heap, "Plugin not found" -> adds pluginManagement repositories, "Could not find dependency" -> adds repositories (+JitPack for com.github.*). New "Retry" chip.
- **Offline / reliability**: no internet -> clear message with the missing dependency instead of long timeouts; longer HTTP timeouts and repository retries when online. Needs ACCESS_NETWORK_STATE (added).

(Not compiled here - no Android SDK in this environment.)

# Termux terminal (not compiled here - build and send me any errors)

- Terminal button also added to the SDK Manager top bar (opens the same terminal)
- Terminal button (home screen): new dark circle with a ">_" icon instead of the boxed picture
- Terminal screen redone like Termux: session tabs + "+", extra keys (ESC / - HOME UP END PGUP, TAB CTRL ALT LEFT DOWN RIGHT PGDN), pinch zoom, long-press select + Copy/Paste, scrollback
- Real terminal emulator (term/TerminalEmulator.java): cursor addressing, alt screen, colours -> nano, vim, htop, less, python REPL work (emulator tested on a PC: 371 checks incl. fuzz)
- Real Termux environment: first open downloads the official Termux bootstrap + proot (about 60 MB), then pkg / apt / dpkg and every Termux package run for real. Lives in files/termux, $HOME is the IDE home (projects + android-sdk visible)
- Sessions stay alive in the background (notification: Exit, wake lock). "Basic shell" button = old Android sh
- Not possible inside: proot-distro / nested proot

# Upgrades in this build (not compiled here - build and send me any errors)

- Editor: CodeEditText (line numbers, syntax colours, undo/redo, auto-indent, auto-close, find/replace, word suggestions), file tabs, symbol bar
- Terminal: real PTY via JNI (cpp/pty.cpp + Pty.kt), ANSI colours, real Ctrl+C, Tab/Esc, window size
- Build: tappable file:line errors, coloured output, Problems tab auto-open on failure
- After assembleDebug: ⬇ button installs the APK (long-press = root install)
- Logcat tab (long-press the tab = root mode)
- 📦 in the file drawer: search Maven/Google and add a dependency to build.gradle
- JDK/SDK downloads resume and keep partial files

## v2
- Git (JGit 5.13.3): drawer -> "Git": init, clone into new project, status, diff, commit all, log, pull, push, branches (switch/create), remote URL. Output in the new "Git" panel tab (diff is coloured). Token is asked each time; "Remember" stores it in app-private prefs.
- New templates: "Compose Navigation" (Material3 NavigationBar + NavHost + detail screen) and "Compose Notes" (ViewModel + StateFlow + DataStore).

## v3 - faster build + sync
- Tuned Gradle runs: heap = 1/4 of phone RAM (768MB-3GB), C1-only JIT + SerialGC, build cache, parallel workers = cores, native build only for this phone's ABI. All passed as -D/-P flags, so existing projects get it too (GradleTuner.kt)
- Fast Mode (long-press ⟳ or ⚡ in drawer -> Build settings): Gradle daemon + configuration cache. Daemon is stopped when the project closes ("Stop daemons" button too). Default OFF
- Real Sync: resolves debug/runtime classpaths of every module (init script), so a wrong library fails at Sync, not at Build. Offline-first: tries --offline, retries online only if the cache was incomplete
- "Sync needed" badge (⟳●) when build.gradle / settings / libs.versions.toml / gradle.properties change; optional auto-sync 1.5s after save
- Adding a dependency (📦) now: writes to libs.versions.toml when the project uses a catalog, reloads open tabs, auto-syncs. Search results cached 1h, second search host as fallback
- Progress in the status line: [12/40 · 30%] :app:compileDebugKotlin · 18s (total learned from the previous run)
- Stop button now kills the whole process tree (gradlew -> java); before, the Gradle JVM kept running
- local.properties sdk.dir is refreshed if the SDK path changed
- Library / non-app projects with the Android plugin now run assembleDebug instead of the slow full `build`; plain `build` skips lint + unit tests (setting)
- Template gradle.properties: build cache on, parallel on, kotlin.incremental, no Jetifier

## v4 - smarter sync
- Smart Sync: nothing changed since the last good sync/build -> no Gradle run ("Already synced", tap ⟳ again within 4s to force). Only module build files changed -> only those modules are synced (`:app:ideResolveDeps`); settings / root / catalog / gradle.properties change -> full sync. If a folder name does not match a Gradle project it falls back to a full sync by itself
- One-tap fixes (bar under the Output/Problems tabs, shown when a run fails with "Could not resolve/find ..."):
  - ⬆ Latest <lib>: looks up the newest version on Maven/Google and updates it in build.gradle(.kts) and/or libs.versions.toml (also version.ref), then re-syncs
  - ＋ Repos: adds missing google() / mavenCentral() (and jitpack for com.github.*) to settings.gradle(.kts) or root build file, then re-syncs
  - 🔄 Refresh & retry: sync with --refresh-dependencies (drops cached "not found" results), online
- New files: SyncFixer.kt. GradleTuner: per-file hashes (SyncScope), --refresh-dependencies support

## v5 - sync + build in one run
- ▶ Build now chains the sync in front of the build in ONE Gradle invocation (`gradlew [:app:]ideResolveDeps assembleDebug`) when build files changed since the last good sync/build: one JVM start + one configuration instead of two, and a bad dependency fails in the sync step before any compile work. Module-scoped when only module build files changed, full otherwise; falls back to a full sync by itself if a module name does not match. Offline-first retry (online) keeps the chain.
- Nothing changed -> plain build, as before. New setting "Sync + build in one run" (Build settings, default ON); turn off for the old behaviour.
- Chained runs learn their own progress total (separate from plain builds). Configuration cache is skipped for chained runs only.

## v6 - background builds, more one-tap fixes, one-tap Run, version pre-flight
- Background build (BuildService.kt): a sync/build runs as a foreground service with a wake lock and an ongoing notification (live progress + Stop). Closing the project screen no longer kills the build; "Build finished / failed" notification when the screen is not in front. Fast Mode daemon is stopped when such a background build ends. New manifest entries: FOREGROUND_SERVICE, WAKE_LOCK, <service>.
- More one-tap fixes (BuildFixer.kt), shown in the same bar as ⬆ Latest / ＋ Repos / 🔄:
  - "Namespace not specified" -> ＋ namespace (package from AndroidManifest / applicationId)
  - minSdk / compileSdk too low for a library -> ↑ minSdk N / ↑ compileSdk N
  - Duplicate class -> ＋ Kotlin BOM (kotlin libs) and ⬆ Latest <older lib>
  - Unsupported class file major version / Unsupported Java -> ⬆ Gradle X for that Java, ☕ JDK manager
  - "requires Java N" -> ☕ Install JDK N;  missing platform / SDK -> 📦 SDK manager
  - Compose Compiler vs Kotlin -> ↻ Kotlin <version the compiler asks for>
  After an edit the failed action (sync or build) runs again by itself.
- One-tap Run: Build settings -> "After build: install + launch the app + open its log". Installs the APK, launches the app when the installer finishes (package broadcast), opens the Logcat tab. With root: pm install + monkey launch, logcat follows only that app's pid. Optional "use root" checkbox. The system installer popup is only used while the screen is in front.
- Version pre-flight (CompatChecker.kt): before sync/build, AGP <-> Gradle wrapper <-> installed JDK are compared (tables cover AGP 7.0-9.0 and Gradle 7.3-9.x; newer versions are never flagged). A mismatch shows a dialog with a fix (Gradle wrapper bump / JDK manager), "Run anyway" (not asked again this session) or Cancel.

## v7 - quick Check + build variants + release signing
- ✓ Check button (next to ▶): compiles the selected variant's sources only (`compile<Variant>Sources`, `classes` for non-Android) - no APK packaging, so errors show up much sooner. Same pre-sync chain, version pre-flight, background service and one-tap fixes as ▶; tapping ✓ while it runs stops it.
- Build variants (BuildVariants.kt): reads buildTypes x productFlavors (also several flavor dimensions) from the app module's build.gradle(.kts). Long-press ▶ (or 🏷 in the drawer) -> pick the variant and APK / AAB. ▶ then runs assemble<Variant> / bundle<Variant>; the chosen variant is remembered per project and shown in the status title. The APK/AAB of that variant is found by folder (no more installing the wrong variant's stale APK). ABI-only native build also applies to bundle tasks.
- Release signing (Signing.kt): in the variant dialog -> 🔑 Signing: create a keystore with the JDK's keytool (RSA 2048) or use an existing one. Details are stored owner-only in <home>/.gradle/ide-signing.properties; passwords reach keytool through environment variables. For release / non-debug runs an init script attaches the keystore to every non-debug build type that has no signingConfig of its own (a signingConfig written by hand in build.gradle always wins). Nothing in the project's build files is edited. "Turn off" removes the properties file (the keystore stays).
- An unsigned release APK is no longer offered for install; the status explains how to set up signing.

## v8 - SDK Manager upgrade (not compiled here - build and send me any errors)
- Live package list (SdkIndex.kt): versions come from Google's repository index (newest 15 per tab, stable only), cached 24h, works offline from the cache; the old hardcoded lists are only a fallback. Download size shown before install. "Update" badge + Update button when a newer revision of an installed package exists. SDK info dialog -> "Refresh list".
- Verify (SdkVerifier.kt): ELF header of aapt2/aapt/zipalign, adb/fastboot, cmake/ninja, NDK clang is compared with the phone's CPU. Wrong CPU (x86-64 on arm64) -> red "Incompatible" badge, reason in the bottom bar, and a check after every install. "Invalid" packages get a Repair button. The installer now prefers a linux aarch64 archive on arm64 phones when Google offers one.
- Install for project (SdkNeeds.kt): "📂 For project" reads compileSdk / buildToolsVersion / ndkVersion / CMake version from the project's build files (+ libs.versions.toml) and installs everything missing in one tap. BuildFixer's "📦 SDK manager" / "☕ JDK manager" now open the manager with the current project already checked.

## v9 - smarter New project screens (not compiled here - build and send me any errors)
- Template picker (TemplateActivity): search box, filter chips (All / View / Compose / Native / Other), "Recent" row with your last 3 templates, and a details sheet on tap (what it is, what you get, "Use this template").
- Create screen (ProjectConfigActivity): remembers package prefix, language, min SDK, location, Kotlin-DSL switch and the Git switch for the next project. New "Initialize Git repository" switch (git init + .gitignore + first commit, same name/email as the Git panel). "SDK ready" line next to Create: shows missing API level / NDK with size, tap -> SDK manager offers "Install all"; re-checked when you come back.
- ProjectTemplates.COMPILE_SDK / NDK_VERSION are now shared constants (used by the generated Gradle files and the SDK check).

## v10 - AAPT archive failure fix
- BuildFixer: new one-tap "🧹 Clean build & retry" when AAPT reports `failed to write ... to archive` / `Invalid entry name` / `No space left on device`. It deletes every module's build/ folder, reports free space, and re-runs the build (or stops with a "Low storage" message if less than 500 MB is free).
- BuildFixer.findAppBuildFile: `parentFile.name` -> `parentFile?.name` (Kotlin nullable error at line 119).

## v11 - first-run "SDK Installation" screen (not compiled here - build and send me any errors)
- New SetupActivity (dark Material 3, same layout as the reference screenshot): opens automatically the first time the app starts (after the permission prompts). Fields: Android SDK platform, Build Tools, JDK, NDK (default Skip) + "Install Platform Tools (adb)". Recommended versions are pre-selected (SDK = ProjectTemplates.COMPILE_SDK, newest Build Tools, JDK 17); lists come from the live Google index (SdkIndex) with the built-in lists as fallback. Shows download size and free space.
- "Automatic installation" ON = download the selected versions. OFF = offline mode, only components with "Use my ZIP" are installed.
- "Use my ZIP" on every component: pick your own ZIP (any file manager). SdkInstaller.installFromZip checks it is a ZIP, unpacks it, strips extra wrapper folders, checks the key files (aapt2 / android.jar / ndk-build / bin/java ...), writes source.properties if missing, and refuses a JDK built for the wrong CPU. SDK Manager then shows it as Installed.
- "Skip" (and Back) -> confirm, then never shown again; SDK Manager and "install what this project needs" keep working. SDK Manager > info (i) > "Setup wizard" reopens it. Not shown again if JDK + build-tools + a platform are already installed.
- SdkRepository: choices(), idOf(), isInstalled(). SdkVerifier also checks bin/java for JDK ZIPs. SdkNeeds.label knows JDK / Platform Tools.
- App start check: if Build Tools are already installed the app opens straight to the home screen; the SDK Installation page appears only when they are missing (and not skipped before).

## v12 - template upgrades: Material 3 / dark / dynamic color + template.json (not compiled here - build and send me any errors)
- View templates (Empty, Basic, BottomNavigation, Responsive, NavigationDrawer, NoActivity, NativeCpp): full Material 3 palette in values/colors.xml, dark palette in values-night/colors.xml, wired into Theme.AppTheme. MainActivity (Kotlin + Java) calls DynamicColors.applyToActivityIfAvailable(this) so Android 12+ uses the wallpaper colors; older phones use the palette.
- Compose templates (Empty, Navigation, Notes): new Theme.kt with AppTheme (system dark mode + dynamic color) used by MainActivity and the preview; values-night/themes.xml for a dark window background.
- template.json in every template (assets/template/<id>/): needs = platform / buildTools / jdk / ndk / cmake, optional minSdk and note. TemplateMeta.kt reads it. SdkNeeds.requirements() / forTemplate() are now data-driven and also check Build Tools and JDK (min version), not only platform + NDK. CMake is only required when CMake is chosen (the switch re-checks live).
- Template details sheet: new "NEEDS ON THIS PHONE" list (✔ present / ⚠ missing). "SDK ready" line on the create screen offers to install everything missing in one tap (incl. JDK).
- Fix: SDK Manager "install these ids" treated jdk;17 as never installed (it looked for a folder that does not exist); now uses SdkRepository.isInstalled.
- PlainEditor: template.json is not copied into the new project.

## v13 - fixes from screenshots (not compiled here)
- Template details sheet: forced light surface (was dark in dark mode with dark text = unreadable).
- Platform ids: android-37 and android-37.0 are treated as the same API; the install id is resolved from Google's index, so "API 37 ⚠" no longer shows when 37.0 is present and installing picks the id that really exists.
- SdkVerifier: build-tools checks only aapt2 (aapt/zipalign are not used by Gradle); platform-tools (adb) is a soft orange "adb: x86 only" badge instead of red Incompatible.

## v14
- Empty Gradle cache (first project): skips the `--offline` attempt and goes online directly (saved ~12s and a confusing red failure).
- Problems tab now shows the full "What went wrong" block (the indented lines with the real reason), not only 3 lines.

## v15
- BuildFixer: "🧹 Clean build & retry" is now also offered for AAPT "failed to parse proto XML" / corrupt .flat files (half-written intermediates on shared storage).

## v16 - new project drawer (☰), like the reference video
- New FileTree.kt: icon tab bar (⋮ menu, Files, Git, Dependency, Variant, Build settings), search box (live search through the project, collapse-all, hide build/.gradle/.cxx/.git filter), expandable tree with indent guide lines, chevron, folder icon + item count, per-folder actions (new file / new folder / refresh), file-type icons, type badges (GRADLE, PROPERTIES, PRO, BAT, JAR, TOML, LOCK) and file sizes. All icons come from the icon sheet (res/drawable-nodpi).
- Long-press any row: New file / New folder / Rename / Delete / Copy path (open tabs of renamed/deleted files are closed without writing them back).
- Binary-looking files (.lock, .jar, images, NUL bytes) now ask "Open file? ... Open anyway?" instead of just refusing.

## v17 - arm64 aapt2 (fixes "Incompatible" Build Tools)
- Google's build-tools only contain an x86-64 aapt2. New Aapt2Fix.kt installs the arm64 aapt2 from the Termux repo (with its dependencies, via the existing mini-apt in JdkInstaller.installPackage) into $PREFIX/bin, copies it over the wrong-CPU aapt2 in every installed build-tools folder, and GradleTuner passes -Pandroid.aapt2FromMavenOverride=<it> to every Gradle run.
- Runs automatically after a Build Tools install; for an "Incompatible" Build Tools the Reinstall button is now "Fix aapt2" (reinstalling the same x86 files could never help).

## Build fix

- `bg_sdk_circle_btn.xml` rewritten (same look) so Gradle recompiles it and replaces the corrupt cached `drawable_bg_sdk_circle_btn.xml.flat` ("failed to parse proto XML"). If any other `.flat` error appears, delete `app/build/intermediates/merged_res` and rebuild.

## Flutter install fix (.deb support) + Flutter environment

- The default repo `mumumusuc/termux-flutter` publishes only Termux `.deb` files (`flutter_X_aarch64.deb`), so install failed with "No arm64 .zip / .tar.gz". `FlutterInstaller` now also accepts a `.deb`: downloads it, unpacks it (ar + xz) in a staging folder, moves `opt/flutter` to `home/flutter`, and installs git / unzip / curl if missing. It also looks through the last 10 releases, not only "latest".
- Environment: `FLUTTER_ROOT`, `PUB_CACHE` and `<flutter>/bin` in PATH are now set in the Termux terminal (proot) and in the build shell when the SDK is installed.
- Not compiled here - build it and send me any errors.
