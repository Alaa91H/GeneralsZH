# Changelog

All notable changes to the GeneralsX Zero Hour Android launcher are
documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project versions per [Semantic Versioning](https://semver.org/):
`versionCode = major*10000 + minor*100 + patch` (see `android/app/build.gradle`).

## [Unreleased]

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
