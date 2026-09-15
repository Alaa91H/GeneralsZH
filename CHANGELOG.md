# Changelog

All notable changes to the GeneralsX Zero Hour Android launcher are
documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project versions per [Semantic Versioning](https://semver.org/):
`versionCode = major*10000 + minor*100 + patch` (see `android/app/build.gradle`).

## [Unreleased]

## [1.5.2] - 2026-09-16

### Fixed

- **Normal settings UI loads again on every device** — the launcher
  crashed into the plain-widget fallback screen ever since the Mods
  bottom-nav tab exceeded `BottomNavigationView`'s hard five-item cap
  (`IllegalArgumentException` while building the menu). Two root causes
  were fixed on-device:
  - The bottom bar is now a custom six-destination rail (icon over
    label, active-indicator pill, same visual language, LTR-pinned
    order) with no Material item cap.
  - The manifest exposed **two** launcher icons (game + settings).
    Tapping the game icon on a device with no configured game folder
    hit `GameActivity`'s early-exit path, and a plain `finish()` there
    throws `SuperNotCalledException` — while entering
    `SDLActivity.onCreate()` just to leave is not an option either (it
    dlopens `libmain.so` once and guards re-entry with `System.exit(0)`;
    a reflection `Method.invoke` "super bounce" dispatches virtually and
    recurses, also observed on-device). A new plain `SplashActivity` is
    now the single launcher entry and routes to Game or Setup before any
    SDL class exists; `GameActivity` keeps a `MethodHandle`-based
    `invokespecial` guard as defense in depth.

### Changed

- **Mods is a bottom page again** — the full mod manager is embedded
  inline as the sixth tab (`Home · Graphics · Interface · Tools ·
  Mods · Help`), with Back wired through the panel's own navigation
  (files → detail → browse → installed), storage-import results
  forwarded, and the Installed list re-verified on every resume. The
  standalone Mods activity remains for the Tools entry.

### Fixed

- **ModDB fetch "network blocked" resolved end-to-end** — on networks
  whose IP has earned a Cloudflare bot verdict (ModDB answers the plain
  HTTP client *and* the headless WebView with "Just a moment...")
  fetching never completes without a human. The fetch chain is now:
  direct request → headless WebView (runs the challenge JS, samples the
  DOM until it stops being the interstitial instead of grabbing the
  pre-JS challenge page) → if still blocked, a visible in-app browser
  (`ChallengeActivity`) where the user solves the challenge **once**;
  the earned `cf_clearance` cookie lands in the shared `CookieManager`
  and the direct path works again afterwards. Verified on-device: the
  ModDB listing (30 mods) loads inside the Mods page after the visible
  verification cleared the network-level block.

## [1.5.1] - 2026-09-16

### Added

- **Fixed private release signing key** — release APKs are now signed
  with a dedicated 4096-bit RSA key (`CN=Generals, OU=GeneralsX,
  O=Alaa91H`) instead of the well-known committed debug key. The
  keystore is never committed: locally it lives in the gitignored
  `secrets/` directory with its passwords in
  `secrets/signing.properties`; CI receives it through encrypted
  GitHub Actions secrets (`SIGNING_KEYSTORE`, `SIGNING_STORE_PASSWORD`,
  `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`) that are decoded to disk
  just before Gradle runs.
- **Signature verification in CI** — after packaging, the workflow
  verifies the APK's certificate DN with apksigner and fails the build
  if it is not the Generals release key, so a missing/wrong secret can
  never silently publish an APK that Android would refuse to install
  over a release-signed one. A run without the secret falls back to the
  committed debug key with a loud warning (fresh-clone builds still
  work; they simply cannot update a release-signed install).

### Changed

- Android treats the first release-signed APK as a new signature: users
  of previous builds (debug-key-signed) must uninstall once before
  installing 1.5.1 or later. All subsequent builds install over each
  other as before — that is the point of the fixed key.

## [1.5.0] - 2026-09-16

Full rebrand of the Android app: the launcher is now simply **Generals**
(package `com.Generals.app`), every app-layer file, class, and resource
bears a neutral name, and releases are cut by pushing a `vX.Y.Z` tag.

### Changed

- **App name** — the launcher icon and every user-visible title now read
  "Generals" (previously "Generals Zero Hour" / "GeneralsZH Settings").
- **Application id / package** — `com.generalsx.zerohour` →
  `com.Generals.app`. Android treats this as a new app installing
  alongside the old one; on first launch the new app migrates the game
  folder path (and mod/mods-browse settings) from the legacy package's
  prefs when that app is still installed, and the external
  `.generalszh_gamepath.txt` marker keeps covering fresh installs.
- **Neutral naming throughout the app layer** — Java package is now
  `com.Generals.app`; `GeneralsZHActivity` → `GameActivity`,
  `GeneralsOnlineActivity/Session` → `OnlineActivity/Session`; the
  `gzh_` resource prefix (colors, icons, dialog background) → `gen_`;
  `Theme.GeneralsZHSettings` → `Theme.Generals`; prefs file
  `generalszh_setup` → `generals_setup`. No engine or gameplay file
  was renamed — this is the launcher/app layer only.
- **Native crash-log path** — `AndroidCrashHandler` no longer hardcodes
  the package name; it derives the app's data directory from the
  process uid (userId*100000 + appId), so it stays correct across any
  future rebrand.
- **CI artifact names** — APK artifacts are now
  `Generals-android-run<N>.apk`.

### Added

- **Tag-driven versioning** — pushing a `vX.Y.Z` tag triggers the Build
  Android workflow, which passes `-PversionTag` to Gradle; versionName
  and versionCode (major*10000 + minor*100 + patch) are derived from
  the tag and the GitHub Release is published from the same tag, so
  the tag, the APK version, and the release can no longer drift apart.
  A malformed tag fails the build instead of shipping a wrong version.

### Notes

- Existing users: install the new APK over (or beside) the old one;
  the game-folder path and settings carry over automatically when the
  legacy app is present, otherwise via the external marker file. The
  old "Generals Zero Hour" icon can be uninstalled after the first
  successful launch of the rebranded app.

## [1.4.0] - 2026-09-16

GenLauncher-parity pass for the mod manager: the Mods screen becomes a
first-class bottom-navigation page in Setup (the standalone entry
remains), installed mods are grouped per ModDB profile with per-version
control, and updates are detected automatically.

### Added

- **Mods as a bottom-nav page** — a new "Mods" tab in Setup hosts the
  full mod manager inline (browse, install, update, launch); the
  standalone Mods screen is unchanged and both share one implementation.
- **Version groups, GenLauncher-style** — every downloaded release of a
  mod is its own version leaf under `Mods/<Mod>/<Version>/`, listed
  grouped by mod with each version separately playable and deletable.
  Switching versions never destroys another; pre-1.4 single-folder
  installs still appear and launch unchanged.
- **Automatic update detection** — installs record their ModDB origin
  in a per-version sidecar; the Mods page checks current releases on
  open (one paced request per mod) and badges mods whose installed
  release is no longer current, with a manual "Check for mod updates"
  action and a summary toast.
- **Detail page media** — mod profile pages now show a screenshots
  strip (horizontally scrolling gallery) plus rating and download-count
  chips alongside the hero image and description.

### Changed

- Entering a mod's release list from an update badge goes straight to
  the file list; Back from a browse results list returns to the
  Installed tab as the home root.

## [1.3.0] - 2026-09-15

The mod launcher professional pass: reliability for large downloads, a
richer browse experience, launcher customization, and in-app updates.
Five areas of work, each shipped in full.

### Added

- **Mod updates awareness of their integrity** — the launcher now
  validates every `.big` archive of a selected mod before launching
  (BIGF magic + directory-table sanity). A truncated or corrupt
  install shows exactly which file failed and offers an explicit
  "launch anyway" override instead of crashing the engine mid-load.
- **Install mods from device storage** — a new SAF file picker accepts
  zip, RAR4, 7z or bare `.big` files already on the phone and runs
  them through the same isolated install path as ModDB downloads.
- **7z archive support** — Apache Commons Compress joins the
  extractor; `.7z` releases (previously refused) install like zip/rar.
- **Resumable downloads** — an interrupted download continues from
  its partial file via HTTP Range requests instead of restarting a
  multi-gigabyte transfer. Failed installs offer a Retry that resumes;
  a server that refuses ranges falls back to a clean restart.
- **Storage hygiene** — free space is checked before a download
  starts (under 512 MB free refuses early with a clear message), and
  crash-orphaned temp files are swept automatically when the Mods
  screen opens, reporting how much was reclaimed.
- **Richer browse cards** — thumbnails, ratings and download counts
  on ModDB result cards, decoded through a small dependency-free
  disk+memory image cache. All fields are optional; pages without
  them render plainer cards.
- **Mod detail screen** — tapping a mod now shows its profile
  description and hero image before the file list; Back walks
  detail → browse → the installed list.
- **Sorting and pagination** — sort results by popularity, rating,
  recency or name (remembered across sessions), with "Load more"
  pagination instead of per-tap page hops.
- **Fixed resolution option** (Setup → Graphics) — presets from
  800×600 to 1600×1200, preferred by the engine over the automatic
  screen-matched resolution (the 600px-height floor is still
  enforced natively).
- **Advanced launch arguments** (Setup → Graphics) — one argument per
  line, appended to the game's command line after the launcher's own
  arguments so user values can override them.
- **In-app update check** (Setup → Help) — queries the project's
  GitHub releases, compares version codes, shows the changelog of the
  new version and downloads the APK via the system DownloadManager.
- **Crash reports identify the active mod** — when a mod is loaded,
  its path is recorded into `crash.log` alongside the build stamp.

### Changed

- ModDB requests are paced (≥ 1.2 s apart) with 429/503 back-off, so
  normal tapping can no longer trip the site's rate limiting and
  trigger Cloudflare challenges for the whole IP.
- The Mods screen shows free disk space next to the installed list.

### Fixed

- Back on a results list no longer silently re-downloads the same
  search; it now returns to the Installed tab as the home root.
- The temp-file sweep no longer deletes the download partial that a
  pending retry would resume from.
- Download counters on cards are anchored to the row's stats element,
  so a description mentioning "downloads" can no longer mislabel a card.

### Notes

- RAR5 archives remain unsupported and are refused with a clear
  message (the pure-Java RAR extractor covers RAR4 only).
- Online multiplayer while a mod is loaded desyncs against vanilla
  players, as on PC — unchanged, by design.

## [1.2.2] - 2026-09-14

### Added

- Mod launcher: browse every C&C: Generals Zero Hour mod on ModDB,
  download and install them in-app, and launch the game with one
  selected — each install isolated in its own folder under `Mods/`,
  never merged into the game files.
- Engine support: `-mod <dir>` injection on Android via
  `mod_launch.cfg`; mod-shipped videos no longer fall back to retail
  (backslash path fix in the FFmpeg video player).
- Arabic and English UI throughout the new screens.
