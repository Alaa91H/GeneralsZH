# Changelog

All notable changes to the GeneralsX Zero Hour Android launcher are
documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project versions per [Semantic Versioning](https://semver.org/):
`versionCode = major*10000 + minor*100 + patch` (see `android/app/build.gradle`).

## [1.6.0] - 2026-09-18

### Added

- **GenLauncher repository source in the Mods tab** — the curated
  [GenLauncherModsData](https://github.com/p0ls3r/GenLauncherModsData)
  manifest (Rise of the Reds, Contra, The End of Days, Shockwave, …26 mods)
  now browses and installs alongside ModDB. Versions resolve from each mod's
  own `GenLauncherData.yaml`; mods that ship S3 storage (MinIO at
  gen.insave.ovh:9000) stream as individual `.big` files straight into
  `Mods/<Mod>/<Version>/` with resumable per-object progress — verified
  on-device by downloading and running Contra 10.0.2 Beta 2 Patch 1
  (2.1 GB in ~2 min at 17 MB/s). The archive path (`SimpleDownloadLink`)
  remains the fallback for mods without S3 storage.

- **Full ModPatches / ModAddons layer support (GenLauncher parity)** —
  every repository mod's patch and addon manifests resolve on its detail
  page with per-layer Install / Update / Enable / Delete. Layers live
  pristine under `Mods/<Mod>/+layers/`, and activation merges the enabled
  set over the base into `Mods/<Mod>/+active/` (fingerprinted, rebuilt
  only when stale; the engine still mounts a single `-mod` dir, so no
  gameplay/determinism impact). Toggling a layer re-resolves a live
  launch in place. Verified on-device: repository lists 26 mods with
  correct per-mod layer counts (Contra 14, Shockwave 7).

- **Manifest-driven update checks** — installed components record their
  manifest URL + version (`.genlauncher_meta`); a newer release is simply
  a manifest whose `Version` differs. The quiet check feeds the in-app
  notice (tappable rows open the mod's page), per-card update badges, and
  per-layer update flags — no re-download to know what moved.

- **Per-card Play / Update / Versions / Delete** — every installed mod
  carries its own update entry (opens the page when flagged, otherwise
  runs a single-mod check and reports up to date) and whole-family
  delete (versions, layers, merged tree, sidecars, logo) in one confirmed
  step. Two rows of two, so Arabic labels never ellipsize (verified
  on-device).

- **Per-GPU backend recommendation** — the detected renderer chip is now
  joined by an advisory recommendation (Adreno → Vulkan, everything else
  → native GLES) with a one-tap Apply; nothing ever auto-switches a
  working setup. Picking Vulkan on a device with no Vulkan feature warns
  before saving. Verified on-device (Adreno 509 → Vulkan recommended
  and applied, Turnip/driver sections appeared).

- **Second home-screen icon restored** — the game-settings icon
  (`SetupShortcutActivity`, a plain trampoline that never touches SDL)
  is back next to the game icon; it opens Setup directly with none of the
  unconfigured-device crash risk that motivated the original removal
  (SetupActivity stays un-exported; the trampoline is the only entry).

- **Page transitions** — horizontal slide between the Home and Mods tabs,
  fade for the settings screen (snapshot-based, snapshot discarded on
  interruption); Back from settings returns to the originating tab.

### Fixed

- **Every archive install failed with "unsupported archive type"** —
  downloads staged as `<name>.part` (no extension) but `extract()`
  dispatches on the file name. The staged file is now sniffed (PK / Rar!
  / 7z / BIGF magic) and renamed to match before extraction.

- **GenLauncher installs preferred the RAR5 archive over working S3
  files** — mods that ship both (Contra) downloaded a 1 GB RAR the
  built-in extractor cannot open (`UnsupportedRarV5Exception`). The S3
  listing is now always attempted first; the archive is a fallback only
  when a mod has no S3 storage.

- **The GenLauncher MinIO endpoint 403'd over HTTPS** — verified the
  real storage serves plain HTTP on :9000 (the :443 front rejects object
  traffic); S3 listing/downloads moved there and cleartext is permitted
  for that host only in `network_security_config`.

- **WebView TLS failures on filtered networks (`net_error -202`)** —
  user-installed CAs are now trusted for WebView (same footing as the
  browser), and the visible-challenge flow no longer reports success on
  a TLS error page: it requires real ModDB content.

- **Mods detail screen was not scrollable** — the embedded (bottom-tab)
  panel built its page without a ScrollView, clipping long mod details;
  the page always scrolls now.

- **Setup screen duplicated Home's options** — the graphics/backend and
  launch-args sections moved to Home in the two-tab layout but were left
  behind in Setup; removed there (language, text size, logs,
  diagnostics, help, updates only).

### Fixed
- **Repository layer counts absorbed sibling lists** — the manifest
  list parser stopped a `ModPatches:` list at the next non-indented key,
  swallowing the `ModAddons:` URLs below it (Shockwave showed 13 layers
  instead of 7). Lists now end at the next sibling key; verified
  on-device against the live index.
- **Backend pick left the title on "Graphics"** — `onPickRenderBackend`
  rebuilt `TAB_GRAPHICS`, a page identity that maps to Home content;
  it now rebuilds `TAB_HOME`, so title and content agree (caught
  on-device when the recommendation button was tapped).
- **Infinite rebuild loop in the Mods panel** (critical) — mod families
  installed from storage (no ModDB origin) were skipped by the automatic
  update check without recording a verdict, leaving `updateByGroup` empty,
  so every `rebuild()` re-armed the check: the panel rebuilt itself forever
  (~80% CPU), the view tree was replaced between every touch DOWN and UP,
  and the whole Mods tab appeared dead. Skipped groups now record a
  verdict like any other.
- **Mods tab unresponsive on the test device** — same root cause as the
  loop above; touch works everywhere again (verified on device).
- **Misleading failure mode when Private DNS blocks ModDB** — with an
  ad-blocking Private DNS (e.g. `dns.adguard.com`, observed on the test
  device) `addons.moddb.com` / `image.moddb.com` return NXDOMAIN, every
  WebView challenge pass fails on its assets, and the edge answers the
  direct client 403. The error now names the resolver and the fix
  ("set Private DNS to Automatic") instead of a bare HTTP 403.
- **ChallengeActivity false success** — an in-WebView error page (not a
  challenge, not content either) was classified as "cleared" because the
  check only rejected the interstitial; the verification now requires
  real ModDB markup (size + domain) before declaring success.

### Changed

- **Settings page deduplicated** — the gear page repeated the graphics
  sections (render backend, custom driver, dxvk.conf, launch options)
  that moved to Home in the two-tab reorganization. Settings now opens
  directly with language / text scale / logs / diagnostics / help.
- **Trust user-added CAs for in-app WebViews** (`networkSecurityConfig`):
  on networks with a user-installed filtering CA, Chrome browses ModDB
  while WebView died with `net_error -202`; both now behave the same.
  Cleartext remains forbidden.
- **Bottom rail removed** — the tabs merged into one Home page, so the
  single-destination bar was chrome without a choice. Home is the page;
  the gear opens Settings. The slide transition died with the rail; the
  Settings fade remains, and Back still returns Home.
- **App-bar overline removed** — the brand line above the title was
  redundant on every page; the title alone names the screen.

### Removed

- **ModDB backend** — the mod manager is GenLauncher-repository only:
  `ModDbClient`, `ChallengeActivity`, `WebViewFetch`, the ModDB
  browse/detail/files screens, `.moddb_meta` sidecars and ~40 ModDB
  strings are gone. Pre-1.6 installs keep launching as update-unaware
  imports; reinstalling a mod via the repository re-attaches update
  tracking.

## [1.5.3] - 2026-09-17

### Added

- **GenLauncher-style mod import from storage** — mods can now be added
  whether they ship as an archive or already unpacked:
  - A dedicated **import extracted-folder** button on the Mods page picks
    a folder via the built-in picker and installs it as a mod: every
    `.big` at any depth is kept, companion files mods expect (`.ini`,
    `.txt`, `.skb`, `.map`, textures, `.csf`) ride along, and irrelevant
    heavyweight directories (screenshots, sources, tool dumps) are
    skipped instead of doubling the footprint.
  - Single-root archives and folders are **collapsed** the way
    GenLauncher does it: `ModName/Data/...` installs as `Data/...` under
    the mod, so the `-mod` root and the Installed list stay clean.
  - Single-version installs (folder import, local archive, ModDB
    download) also drop the redundant `Mods/<Mod>/<Mod>/` wrapper leaf,
    showing one clean row in the Installed list. Multi-version mods and
    older installs keep their layout.

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

- **Two-destination navigation with a Settings page** — the bottom bar
  is now just **Home** and **Mods**. Graphics (render backend, driver,
  dxvk.conf, launch options) merged into Home below the primary cards;
  everything configurational — interface (language, UI scale), tools,
  logs, diagnostics, help and the app-update check — lives on one
  **Settings** page opened from a gear icon in the top bar (replacing
  the logs shortcut, which moved into Settings). The former flat tabs
  remain as in-page sections; nothing was dropped.
- **Mods is a bottom page** — the full mod manager is embedded
  inline as the second tab, with Back wired through the panel's own
  navigation (files → detail → browse → installed), storage-import
  results forwarded, and the Installed list re-verified on every
  resume. The standalone Mods activity remains for deep links.

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
