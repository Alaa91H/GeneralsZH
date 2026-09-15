# Mod launcher professional pass — 2026-09-15

Implementation report for the five-phase upgrade plan approved this session
(research basis: GenLauncher feature set and complaint history, Android
download-infrastructure best practice, ModDB page-shape verification from the
2026-09-14 session).

## What shipped

| Phase | Deliverables |
|---|---|
| 1 — Mod manager completeness | SAF storage import, 7z extraction (commons-compress 1.27.1), `ModInstaller.validateMod()` BIGF pre-launch check with override dialog, `active mod:` crash breadcrumb in `AndroidCrashHandler.cpp` |
| 2 — Reliable downloads | HTTP `Range` resume into `<name>.part.dl`, 416→restart fallback, free-space gate (512 MB) before download, `.part.dl`/`.extracted` sweep on Mods-screen open with reclaimed-bytes note |
| 3 — Browse & polish | Thumbnail/rating/downloads on result cards (`ThumbCache.java`: disk+memory LRU, ~32 MB disk budget), persisted sort (popular/rating/recent/name), "Load more" pagination, mod detail screen (profile description + hero image) before the file list, loading/error/retry states, proper Back stack |
| 4 — Launch experience | Setup → Graphics: fixed-resolution presets (written as `<internal>/fixed_resolution.cfg`, preferred by SDL3Main.cpp over Options.ini, height ≥ 600 enforced natively) and advanced launch-arguments box (`<internal>/launch_args.cfg`, appended after `-mod`, 16-arg cap, `#` comments) |
| 5 — Self-update & stability | `AppUpdateChecker.java`: GitHub `releases/latest` → versionCode compare (tag → major*10000+minor*100+patch, same scheme as build.gradle) → changelog dialog → DownloadManager APK install; crash breadcrumb from phase 1 completes the triage story |

## Files touched

- `GeneralsMD/Code/Main/SDL3Main.cpp` — fixed-resolution preference read inside the resolution block; new launch-args argv injection block after `-mod`
- `GeneralsMD/Code/Main/AndroidCrashHandler.cpp` — mod breadcrumb at handler install time
- `android/app/src/main/java/com/generalsx/zerohour/` — `ModDbClient` (pacing/backoff, rich parse, profile fetch, resumable stream API), `ModInstaller` (resume, 7z, local install, validation, sweep, free-space), `ModManagerActivity` (full rewrite: screens, cards, detail, import, retry), `ThumbCache` (new), `AppUpdateChecker` (new), `SetupActivity` (launch options + update card + marker writers)
- `android/app/build.gradle` — `org.apache.commons:commons-compress:1.27.1`
- `AndroidManifest.xml` — unchanged (SAF picker needs no permission)
- Strings: EN + AR for every new surface

## Design decisions worth keeping

- **No WorkManager** (deviation from the original research plan): downloads
  run in an activity-scoped thread and the failure path now offers a resuming
  Retry, which preserves the user's data — the property WorkManager was
  meant to buy — without a new framework, a foreground-service type, or a
  notification-permission prompt. Revisit only if background installs are
  ever requested.
- **No RAR5**: junrar is RAR4-only; rar5 archives still fail with a clear
  message rather than half-extracting.
- **ThumbCache over Glide/Coil**: one screen needs one thumbnail size; the
  hand-rolled cache is ~180 lines with no dex cost and no transitive deps.
- **Cloudflare reality**: this dev machine was hard-challenged during the
  session (curl gets the interstitial), so new parsing was written
  defensive-by-construction and NOT re-verified live. On-device testing is
  the gate; the pacing gate (1.2 s + 429/503 backoff) exists precisely to
  keep a phone from earning the same challenge.
- **versionCode, not tag strings, for update comparison** — matches the
  Android install gate exactly; tags remain display-only.

## Verification

- `:app:assembleRelease` passes (compile + resources + manifest merge +
  dexing) with SDL 3.4.2 Java staged as `src/main/java-sdl` (gitignored,
  normally staged by the packaging script).
- Native edits are stack-buffer C confined to the two proven argv-injection
  blocks and the crash-handler constructor; correctness rides on the same
  patterns as the shipped `mod_launch.cfg` read. CI (Build Android) is the
  native compile gate.
- Deferred to device testing: ModDB rich-field parsing live check, resume
  against the real CDN, SAF import on OEM file managers.

## Follow-ups (not in this pass)

- Update badges: store the installed ModDB file id per mod and diff it
  against the Browse list to badge "update available" (needs a small
  per-mod metadata file next to the install).
- Multi-select launch ("mod packs") — the engine mounts one `-mod` dir
  today; a combined overlay dir would need engine-side thought first.
- ReportFormatException logging hook for a dedicated mod-INI error path.
