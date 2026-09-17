/*
**	Command & Conquer Generals Zero Hour(tm)
**	Copyright 2025 Electronic Arts Inc.
**
**	This program is free software: you can redistribute it and/or modify
**	it under the terms of the GNU General Public License as published by
**	the Free Software Foundation, either version 3 of the License, or
**	(at your option) any later version.
**
**	This program is distributed in the hope that it will be useful,
**	but WITHOUT ANY WARRANTY; without even the implied warranty of
**	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**	GNU General Public License for more details.
**
**	You should have received a copy of the GNU General Public License
**	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

// GeneralsX @feature Android port mod-launcher 14/09/2026
//
// The mod manager, as a reusable page: everything around "play this mod,
// not vanilla". Hosted twice — full-screen by ModManagerActivity (standalone
// launcher-icon entry) and as Setup's Mods bottom-navigation tab — with the
// host supplying only three operations through Host: start the game with the
// current selection, offer the game-folder picker when none is set, and
// surface SAF results picked in onInstallFromStorage.
//
//   Installed view -- <gameFolder>/Mods/* grouped per ModDB profile,
//                     GenLauncher-style: the mod is the row, its downloaded
//                     versions expand beneath it, each separately playable
//                     and deletable, and a per-group update check compares
//                     the installed release against ModDB's current one.
//   Browse view    -- ModDB's C&C: Generals Zero Hour index (search +
//                     pagination + sorting); a card opens the detail page
//                     (description, screenshots strip, rating/downloads),
//                     then the release list; picking a release downloads and
//                     installs it as a NEW version leaf beside the others.
//
// Every version mounts through the engine's own -mod path at
// archive-overwrite priority. The game folder's retail .big files are never
// touched: a version is one folder, removed with one button; vanilla is
// "Clear Selection"; switching versions never destroys another.
//
// GeneralsX @feature 16/09/2026 GenLauncher-parity pass: grouped versions,
// update badges with one-tap access to the new release, screenshots and
// rating/downloads on the detail page, and origin sidecars
// (<version>.moddb_meta) that survive across sessions so update checks need
// no re-download to know what is installed.

package com.Generals.app;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ModsPanel extends LinearLayout {

    /** Operations only the hosting activity can provide. */
    interface Host {
        Activity activity();

        /** The panel validated the selection; the host starts the game. */
        void launchGame();

        /** No game folder configured; the host should offer the picker. */
        void requestGameFolder();
    }

    // Screens; a tiny hand-rolled stack because the flow is exactly 4 deep.
    private static final int SCREEN_INSTALLED = 0;
    private static final int SCREEN_BROWSE = 1;
    private static final int SCREEN_DETAIL = 2;
    private static final int SCREEN_FILES = 3;

    static final int REQ_PICK_ARCHIVE = 4101;
    static final int REQ_PICK_FOLDER = 4102;

    private static final int SORT_POPULAR = 0;
    private static final int SORT_RATING = 1;
    private static final int SORT_RECENT = 2;
    private static final int SORT_NAME = 3;

    private final Host host;
    private final boolean withAppBar; // standalone activity vs Setup tab

    private LinearLayout listHost;
    private TextView statusBar;
    private String gameFolder;
    private String launchPath; // mod dir currently in mod_launch.cfg, or null
    private int screen = SCREEN_INSTALLED;

    // Browse state; kept across rebuilds so rotation doesn't lose the list.
    private List<ModDbClient.ModSummary> results = new ArrayList<>();
    private int browsePage = 1;
    private boolean hasMorePages = false;
    private String lastQuery = null; // null = "show all", else search term
    private int sortMode = SORT_POPULAR;

    // Detail/files context.
    private ModDbClient.ModSummary detailMod;
    private ModDbClient.ModDetails detailData;
    private List<ModDbClient.ModFile> detailFiles;

    // Install context, kept so a failed install can offer resumable retry.
    private ModDbClient.ModSummary installMod;
    private ModDbClient.ModFile installFile;
    private String installBase;
    // Live views of the install progress card; nulled on rebuild so a stale
    // reference is never written to after the page changes.
    private TextView installPhaseView;
    private ProgressBar installBar;
    private TextView installBytesView;

    // GenLauncher-style installed state: expanded version lists and the
    // per-group update verdicts (null = not checked yet).
    private final HashSet<String> expandedGroups = new HashSet<>();
    private final Map<String, Boolean> updateByGroup = new HashMap<>();
    private boolean updateCheckRunning = false;

    ModsPanel(Activity activity, Host host, boolean withAppBar) {
        super(activity);
        this.host = host;
        this.withAppBar = withAppBar;
        setOrientation(LinearLayout.VERTICAL);
        ThumbCache.setCacheDir(activity.getCacheDir());
        gameFolder = SetupActivity.getSavedGamePath(activity);
        launchPath = readLaunchCfg(activity);
        restoreBrowsePrefs(activity);
        rebuild();
    }

    // ------------------------------------------------------------ lifecycle

    void notifyGameFolderMaybeChanged() {
        // Setup can change the game folder while this panel is alive.
        gameFolder = SetupActivity.getSavedGamePath(host.activity());
    }

    private void restoreBrowsePrefs(Activity activity) {
        android.content.SharedPreferences prefs =
            activity.getSharedPreferences(SetupActivity.PREFS_NAME, Activity.MODE_PRIVATE);
        sortMode = clamp(prefs.getInt("mods_sort", SORT_POPULAR), SORT_POPULAR, SORT_NAME);
        // The list itself is not persisted (a stale copy would fight the live
        // site); only the tab the user was on, so re-entering lands sensibly.
        // Embedded (Setup tab) always opens on Installed instead.
        if (withAppBar) {
            screen = prefs.getBoolean("mods_browsing", false) ? SCREEN_BROWSE : SCREEN_INSTALLED;
        }
    }

    private void saveBrowsePrefs() {
        android.content.SharedPreferences prefs =
            host.activity().getSharedPreferences(SetupActivity.PREFS_NAME, Activity.MODE_PRIVATE);
        prefs.edit()
            .putInt("mods_sort", sortMode)
            .putBoolean("mods_browsing", screen != SCREEN_INSTALLED)
            .apply();
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (!withAppBar) {
            // Embedded in Setup's scrolling column, the first measure pass
            // arrives with UNSPECIFIED height; the panel's inner scrolling
            // page needs a bounded viewport, so claim the visible screen
            // height. Setup's ScrollView re-measures with the real viewport
            // afterwards (fillViewport), which lands here as EXACTLY.
            int hMode = MeasureSpec.getMode(heightMeasureSpec);
            int hSize = MeasureSpec.getSize(heightMeasureSpec);
            if (hMode == MeasureSpec.UNSPECIFIED || hSize == 0) {
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                hSize = (int) (dm.heightPixels * 0.72f);
            }
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(hSize, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void rebuild() {
        // Page-scoped views (install progress card) die with the page; drop
        // the references first so a late worker-thread callback can never
        // write into a view that is no longer on screen.
        clearPageReferences();
        removeAllViews();

        Activity activity = host.activity();
        LinearLayout page = this;
        if (withAppBar) {
            setBackgroundColor(UiKit.color(activity, R.color.gen_background));
            InsetUtil.applySafeInsets(this);
            UiKit.appBar(this, activity.getString(R.string.mods_overline),
                activity.getString(R.string.mods_window_title),
                R.drawable.ic_gen_refresh, activity.getString(R.string.mods_refresh),
                this::onRefresh);
            page = UiKit.scrollingPage(this);
        }

        // Tab row: Installed | Browse
        LinearLayout tabs = UiKit.buttonRow(page);
        addTabButton(tabs, screen == SCREEN_INSTALLED, R.string.mods_tab_installed, v -> {
            screen = SCREEN_INSTALLED;
            saveBrowsePrefs();
            rebuild();
        });
        addTabButton(tabs, screen != SCREEN_INSTALLED, R.string.mods_tab_browse, v -> {
            if (screen == SCREEN_INSTALLED) {
                screen = SCREEN_BROWSE;
                saveBrowsePrefs();
            }
            rebuild();
        });

        statusBar = UiKit.body(page, null);
        statusBar.setVisibility(View.GONE);

        listHost = new LinearLayout(activity);
        listHost.setOrientation(LinearLayout.VERTICAL);
        page.addView(listHost, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        switch (screen) {
            case SCREEN_BROWSE:
                buildBrowseSearch();
                if (results.isEmpty() && lastQuery != null) {
                    runBrowse(lastQuery);
                }
                break;
            case SCREEN_DETAIL:
                buildDetail();
                break;
            case SCREEN_FILES:
                buildFiles();
                break;
            case SCREEN_INSTALLED:
            default:
                buildInstalled();
                break;
        }
    }

    private void addTabButton(LinearLayout row, boolean active, int labelRes,
                              View.OnClickListener listener) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(UiKit.dp(getContext(), 3f));
        lp.setMarginEnd(UiKit.dp(getContext(), 3f));
        android.widget.Button b = new android.widget.Button(getContext());
        b.setText(labelRes);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        b.setEnabled(!active); // active tab: pressed-in look via disabled state
        b.setAlpha(active ? 1f : 0.55f);
        row.addView(b, lp);
    }

    // ------------------------------------------------------ installed list

    private void buildInstalled() {
        Activity activity = host.activity();
        if (gameFolder == null) {
            UiKit.supporting(listHost, activity.getString(R.string.mods_no_game_folder));
            UiKit.button(listHost, UiKit.BTN_TONAL, R.drawable.ic_gen_folder,
                activity.getString(R.string.setup_button_select_game_folder),
                host::requestGameFolder);
            return;
        }

        // GeneralsX @bugfix 15/09/2026 sweep first: a killed session can leave
        // multi-GB .part.dl/extracted scratch behind — invisible junk the
        // user can't reach with a file manager (app-private dirs). A live
        // download's own partial is exempt (passed through to the sweep),
        // so a failed install can still resume after a detour here.
        final long sweptBefore = ModInstaller.freeBytes(gameFolder);
        ModInstaller.cleanupTempFiles(gameFolder, installBase);
        final long freed = ModInstaller.freeBytes(gameFolder) - sweptBefore;

        if (launchPath != null && !new File(launchPath).isDirectory()) {
            // Selected folder vanished (user deleted it in a file manager):
            // self-heal to vanilla rather than launching into a missing dir.
            clearLaunchCfg();
            launchPath = null;
        }

        List<ModInstaller.ModGroup> groups = ModInstaller.listGroups(gameFolder, launchPath);

        LinearLayout launchCard = UiKit.card(listHost);
        UiKit.sectionHeader(launchCard, R.drawable.ic_gen_play,
            activity.getString(R.string.mods_card_launch), false);
        if (launchPath != null) {
            UiKit.supporting(launchCard, activity.getString(R.string.mods_launch_active,
                new File(launchPath).getName()));
            UiKit.button(launchCard, UiKit.BTN_PRIMARY, R.drawable.ic_gen_play,
                activity.getString(R.string.mods_button_launch), this::onLaunchGame);
            UiKit.button(launchCard, UiKit.BTN_DANGER, R.drawable.ic_gen_broom,
                activity.getString(R.string.mods_button_clear_launch), () -> {
                    clearLaunchCfg();
                    launchPath = null;
                    rebuild();
                });
        } else {
            UiKit.supporting(launchCard, activity.getString(R.string.mods_launch_vanilla));
        }

        LinearLayout card = UiKit.card(listHost);
        UiKit.sectionHeader(card, R.drawable.ic_gen_chip,
            activity.getString(R.string.mods_card_installed, groups.size()), false);

        if (freed > 1024) {
            UiKit.supporting(card,
                activity.getString(R.string.mods_storage_note, humanBytes(ModInstaller.freeBytes(gameFolder)))
                    + " \u00b7 " + activity.getString(R.string.mods_storage_freed, humanBytes(freed)));
        } else {
            UiKit.supporting(card,
                activity.getString(R.string.mods_storage_note, humanBytes(ModInstaller.freeBytes(gameFolder))));
        }

        if (groups.isEmpty()) {
            UiKit.supporting(card, activity.getString(R.string.mods_none_installed));
            UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gen_download,
                activity.getString(R.string.mods_tab_browse), () -> {
                    screen = SCREEN_BROWSE;
                    saveBrowsePrefs();
                    rebuild();
                });
        } else {
            for (ModInstaller.ModGroup group : groups) {
                card.addView(buildGroupSection(group));
            }
            UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gen_refresh,
                activity.getString(R.string.mods_check_updates), this::checkUpdatesManually);
        }
        UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gen_folder,
            activity.getString(R.string.mods_install_from_files), this::onInstallFromStorage);
        UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gen_folder,
            activity.getString(R.string.mods_import_folder), this::onImportExtractedFolder);
        UiKit.supporting(card, activity.getString(R.string.mods_pick_archive_or_folder));
        UiKit.supporting(card, activity.getString(R.string.mods_installed_hint));

        // GenLauncher behavior: updates are noticed without the user asking.
        // Quiet on failure — a flaky network just leaves no badge, and the
        // manual check above reports loudly instead.
        if (!updateCheckRunning && !groups.isEmpty() && !updateByGroup.containsKey("__done__")
                && updateByGroup.isEmpty()) {
            checkUpdates(groups, null);
        }
    }

    /** One mod family: header row (tap = expand versions) + version rows. */
    private View buildGroupSection(ModInstaller.ModGroup group) {
        Activity activity = host.activity();
        LinearLayout section = new LinearLayout(activity);
        section.setOrientation(LinearLayout.VERTICAL);

        LinearLayout groupRow = new LinearLayout(activity);
        groupRow.setOrientation(LinearLayout.HORIZONTAL);
        groupRow.setGravity(Gravity.CENTER_VERTICAL);
        groupRow.setClickable(true);
        groupRow.setFocusable(true);
        int pad = UiKit.dp(activity, 10f);
        groupRow.setPadding(pad, pad, pad, pad);
        GradientBg.apply(groupRow);
        groupRow.setOnClickListener(v -> {
            if (!expandedGroups.remove(group.modName)) {
                expandedGroups.add(group.modName);
            }
            rebuild();
        });
        section.addView(groupRow, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView arrow = new TextView(activity);
        arrow.setText(expandedGroups.contains(group.modName) ? "\u25be" : "\u25b8");
        arrow.setTextSize(18f);
        arrow.setTextColor(UiKit.color(activity, R.color.gen_on_surface_faint));
        arrow.setPadding(0, 0, UiKit.dp(activity, 10f), 0);
        groupRow.addView(arrow);

        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        groupRow.addView(textCol, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(activity);
        title.setText(group.modName);
        title.setTextSize(15f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(UiKit.color(activity, R.color.gen_on_surface));
        textCol.addView(title);

        StringBuilder meta = new StringBuilder();
        meta.append(activity.getString(R.string.mods_group_versions, group.versions.size()));
        meta.append(" \u00b7 ").append(humanBytes(group.bytesUsed));
        if (group.anySelected) {
            meta.append(" \u00b7 ").append(activity.getString(R.string.mods_installed_active_badge));
        }
        TextView metaView = new TextView(activity);
        metaView.setText(meta.toString());
        metaView.setTextSize(12f);
        metaView.setTextColor(UiKit.color(activity, R.color.gen_on_surface_variant));
        textCol.addView(metaView);

        Boolean update = updateByGroup.get(group.modName);
        if (Boolean.TRUE.equals(update)) {
            TextView badge = new TextView(activity);
            badge.setText(R.string.mods_update_badge);
            badge.setTextSize(12f);
            badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            badge.setTextColor(UiKit.color(activity, R.color.gen_tertiary));
            badge.setPadding(UiKit.dp(activity, 8f), UiKit.dp(activity, 3f),
                UiKit.dp(activity, 8f), UiKit.dp(activity, 3f));
            GradientBg.applyTinted(badge, R.color.gen_tertiary_container);
            groupRow.addView(badge);
        }

        if (expandedGroups.contains(group.modName)) {
            LinearLayout versions = new LinearLayout(activity);
            versions.setOrientation(LinearLayout.VERTICAL);
            versions.setPadding(UiKit.dp(activity, 14f), 0, UiKit.dp(activity, 6f), 0);
            for (ModInstaller.InstalledMod version : group.versions) {
                String supporting = activity.getString(R.string.mods_entry_size,
                    humanBytes(version.bytesUsed));
                if (version.selected) {
                    supporting += " \u00b7 " + activity.getString(R.string.mods_installed_active_badge);
                }
                UiKit.listRow(versions, R.drawable.ic_gen_chip, version.displayName, supporting,
                    () -> onVersionClicked(group, version));
            }
            section.addView(versions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        return section;
    }

    private void onVersionClicked(ModInstaller.ModGroup group, ModInstaller.InstalledMod version) {
        if (version.selected) {
            onLaunchGame(); // tapping the active version launches
            return;
        }
        Activity activity = host.activity();
        String[] options = {
            activity.getString(R.string.mods_action_play),
            activity.getString(R.string.mods_action_delete),
        };
        new android.app.AlertDialog.Builder(activity)
                .setTitle(group.modName + " \u2014 " + version.displayName)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        selectForLaunch(version.dirPath);
                    } else {
                        confirmDelete(version);
                    }
                })
                .show();
    }

    private void confirmDelete(ModInstaller.InstalledMod version) {
        Activity activity = host.activity();
        new android.app.AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.mods_delete_title, version.displayName))
                .setMessage(activity.getString(R.string.mods_delete_body, version.displayName))
                .setPositiveButton(R.string.mods_delete_confirm, (d, w) -> {
                    ModInstaller.deleteRecursively(new File(version.dirPath));
                    if (version.selected) {
                        clearLaunchCfg();
                        launchPath = null;
                    }
                    rebuild();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // -------------------------------------------------------- update checks

    /**
     * GenLauncher-style update detection: for every installed version with a
     * ModDB origin sidecar, compare against the profile's current release
     * list. One request per mod family; the pacing gate in ModDbClient keeps
     * it well inside the site's limits. A group "has an update" when none of
     * its installed file pages match any current file — the releases were
     * replaced by a newer one.
     */
    private void checkUpdates(List<ModInstaller.ModGroup> groups, Runnable onDone) {
        updateCheckRunning = true;
        final List<ModInstaller.ModGroup> work = new ArrayList<>(groups);
        new Thread(() -> {
            for (ModInstaller.ModGroup group : work) {
                if (updateByGroup.containsKey(group.modName)) {
                    continue; // already checked this session
                }
                String profilePath = null;
                java.util.Set<String> installedPages = new HashSet<>();
                for (ModInstaller.InstalledMod version : group.versions) {
                    String meta = ModInstaller.readMeta(
                        new File(version.dirPath).getParentFile(), version.displayName);
                    if (meta != null) {
                        installedPages.add(meta);
                        int cut = meta.indexOf("/downloads/");
                        if (profilePath == null && cut > 0) {
                            profilePath = meta.substring(0, cut);
                        }
                    }
                }
                if (profilePath == null) {
                    continue; // storage import or pre-1.4 install: no origin
                }
                boolean update = false;
                try {
                    List<ModDbClient.ModFile> current = ModDbClient.fetchModFiles(profilePath);
                    if (!current.isEmpty()) {
                        update = true;
                        for (ModDbClient.ModFile f : current) {
                            if (installedPages.contains(f.pagePath)) {
                                update = false; // one installed release still current
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // Quiet: a failed check just leaves no badge this session.
                }
                updateByGroup.put(group.modName, update);
            }
            updateCheckRunning = false;
            Activity activity = host.activity();
            activity.runOnUiThread(() -> {
                if (!isFinishingSafe()) {
                    rebuild();
                    if (onDone != null) {
                        onDone.run();
                    }
                }
            });
        }, "gx-mod-update-check").start();
    }

    private void checkUpdatesManually() {
        if (gameFolder == null) {
            return;
        }
        updateByGroup.clear(); // force a fresh check even for cached verdicts
        List<ModInstaller.ModGroup> groups = ModInstaller.listGroups(gameFolder, launchPath);
        showStatus(host.activity().getString(R.string.mods_checking_updates));
        checkUpdates(groups, () -> {
            hideStatus();
            int found = 0;
            for (Boolean b : updateByGroup.values()) {
                if (Boolean.TRUE.equals(b)) {
                    found++;
                }
            }
            Toast.makeText(host.activity(),
                host.activity().getString(R.string.mods_updates_summary, found),
                Toast.LENGTH_SHORT).show();
        });
    }

    // ------------------------------------------------- selection + launching

    private void selectForLaunch(String dirPath) {
        Activity activity = host.activity();
        try (FileWriter w = new FileWriter(new File(activity.getFilesDir(), "mod_launch.cfg"), false)) {
            w.write(dirPath);
            w.write("\n");
            launchPath = dirPath;
            rebuild();
        } catch (Exception e) {
            toast(activity.getString(R.string.mods_err_write_cfg, String.valueOf(e)));
        }
    }

    private void clearLaunchCfg() {
        new File(host.activity().getFilesDir(), "mod_launch.cfg").delete();
    }

    static String readLaunchCfg(Activity activity) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                 new java.io.FileReader(new File(activity.getFilesDir(), "mod_launch.cfg")))) {
            String line = r.readLine();
            return (line != null && !line.trim().isEmpty()) ? line.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Launch gate: validate the selected mod's .big set first. A truncated
     * download used to crash the engine mid-INI-load; now the user sees what
     * is wrong and can still override — their device, their call.
     */
    private void onLaunchGame() {
        Activity activity = host.activity();
        if (launchPath == null) {
            host.launchGame();
            return;
        }
        if (gameFolder != null) {
            ModInstaller.cleanupTempFiles(gameFolder, installBase);
        }
        final String problem = ModInstaller.validateMod(launchPath);
        if (problem.isEmpty()) {
            host.launchGame();
            return;
        }
        new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.mods_launch_warning_title)
                .setMessage(activity.getString(R.string.mods_launch_warning_body, problem))
                .setPositiveButton(R.string.mods_launch_anyway, (d, w) -> host.launchGame())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------- install from storage

    /**
     * SAF picker for archives/bigs already on the device. ACTION_OPEN_DOCUMENT
     * with openable types; the picked Uri is streamed to a temp file (the
     * installer operates on real Files) and installed like any download.
     */
    private void onInstallFromStorage() {
        Activity activity = host.activity();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
            "application/zip", "application/x-rar-compressed", "application/x-7z-compressed",
            "application/octet-stream", // .big files report as generic binary
        });
        activity.startActivityForResult(intent, REQ_PICK_ARCHIVE);
    }

    /**
     * GenLauncher-style extracted-folder import: the user browses to the
     * folder with our own picker (real filesystem paths, no SAF tree dance)
     * and the installer copies the .big set (+ companions) into Mods/.
     */
    private void onImportExtractedFolder() {
        Activity activity = host.activity();
        if (gameFolder == null) {
            toast(activity.getString(R.string.mods_no_game_folder));
            return;
        }
        Intent intent = new Intent(activity, FolderPickerActivity.class);
        activity.startActivityForResult(intent, REQ_PICK_FOLDER);
    }

    /** Entry point for the host's onActivityResult forwarding. */
    void handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQ_PICK_ARCHIVE && data.getData() != null) {
            importPickedArchive(data.getData());
        } else if (requestCode == REQ_PICK_FOLDER) {
            String path = data.getStringExtra(FolderPickerActivity.EXTRA_SELECTED_PATH);
            if (path != null) {
                importPickedFolder(new File(path));
            }
        }
    }

    private void importPickedFolder(File dir) {
        Activity activity = host.activity();
        showInstallCard(dir.getName());
        final ModInstaller.Listener listener = makeInstallListener();
        new Thread(() -> {
            String error = null;
            try {
                File installed = ModInstaller.installFromExtractedFolder(
                    dir, gameFolder, dir.getName(), listener);
                // GenLauncher style: a folder-picked mod is its own single
                // version — collapse the duplicated <Mod>/<Mod>/ wrapper so
                // the Installed list shows one clean row.
                if (installed != null) {
                    ModInstaller.flattenSingleVersion(installed.getParentFile());
                }
            } catch (Exception e) {
                error = (e.getMessage() != null) ? e.getMessage() : String.valueOf(e);
            }
            final String finalError = error;
            activity.runOnUiThread(() -> {
                if (statusBar != null) {
                    statusBar.setVisibility(View.GONE);
                }
                if (!isFinishingSafe()) {
                    if (finalError != null) {
                        toast(activity.getString(R.string.mods_err_install, finalError));
                    }
                    rebuild();
                }
            });
        }, "gx-mod-folder-import").start();
    }

    private void importPickedArchive(Uri uri) {
        Activity activity = host.activity();
        if (gameFolder == null) {
            toast(activity.getString(R.string.mods_no_game_folder));
            return;
        }
        String displayName = queryDisplayName(uri);
        File staging = new File(activity.getCacheDir(), "import");
        if (!staging.isDirectory() && !staging.mkdirs()) {
            toast(activity.getString(R.string.mods_err_install, "cannot stage import"));
            return;
        }
        File local = new File(staging, displayName);
        showStatus(activity.getString(R.string.mods_loading));
        new Thread(() -> {
            Exception failure = null;
            try (InputStream in = activity.getContentResolver().openInputStream(uri);
                 OutputStream out = new java.io.FileOutputStream(local)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } catch (Exception e) {
                failure = e;
            }
            Exception copyError = failure;
            activity.runOnUiThread(() -> {
                hideStatus();
                if (copyError != null) {
                    toast(activity.getString(R.string.mods_err_install, String.valueOf(copyError)));
                    return;
                }
                runLocalInstall(local, displayName);
            });
        }, "gx-mod-import").start();
    }

    private void runLocalInstall(File archive, String displayName) {
        showInstallCard(displayName);
        final ModInstaller.Listener listener = makeInstallListener();
        Activity activity = host.activity();
        new Thread(() -> {
            String error = null;
            try {
                String modName = displayName;
                int dot = modName.lastIndexOf('.');
                if (dot > 0) {
                    modName = modName.substring(0, dot);
                }
                File installed = ModInstaller.installFromLocalFile(
                    archive, gameFolder, modName, listener);
                // GenLauncher style: single-version installs (archive or
                // ModDB download) collapse the redundant <Mod>/<Mod>/ leaf.
                if (installed != null) {
                    ModInstaller.flattenSingleVersion(installed.getParentFile());
                }
            } catch (Exception e) {
                error = (e.getMessage() != null) ? e.getMessage() : String.valueOf(e);
            } finally {
                archive.delete(); // staging copy no longer needed either way
            }
            final String finalError = error;
            activity.runOnUiThread(() -> {
                if (statusBar != null) {
                    statusBar.setVisibility(View.GONE);
                }
                if (!isFinishingSafe()) {
                    if (finalError != null) {
                        toast(activity.getString(R.string.mods_err_install, finalError));
                        rebuild();
                        return;
                    }
                    toast(activity.getString(R.string.mods_install_done, displayName));
                    screen = SCREEN_INSTALLED;
                    rebuild();
                }
            });
        }, "gx-mod-install-local").start();
    }

    private String queryDisplayName(Uri uri) {
        Activity activity = host.activity();
        String name = "mod.zip";
        Cursor cursor = null;
        try {
            cursor = activity.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && cursor.getString(idx) != null) {
                    name = cursor.getString(idx);
                }
            }
        } catch (Exception ignored) {
            // Fall back to the default name; content providers vary widely.
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return name;
    }

    // ---------------------------------------------------------- browse view

    private void buildBrowseSearch() {
        Activity activity = host.activity();
        LinearLayout card = UiKit.card(listHost);
        UiKit.sectionHeader(card, R.drawable.ic_gen_globe,
            activity.getString(R.string.mods_card_browse), false);
        UiKit.supporting(card, activity.getString(R.string.mods_browse_hint));

        LinearLayout searchRow = new LinearLayout(activity);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText query = new EditText(activity);
        query.setHint(R.string.mods_search_hint);
        query.setInputType(InputType.TYPE_CLASS_TEXT);
        query.setSingleLine(true);
        query.setTextSize(15f);
        if (lastQuery != null) {
            query.setText(lastQuery);
        }
        query.setOnEditorActionListener((v, actionId, event) -> {
            runBrowse(query.getText().toString().trim());
            return true;
        });
        searchRow.addView(query, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Button searchBtn = new android.widget.Button(activity);
        searchBtn.setText(R.string.mods_search_button);
        searchBtn.setOnClickListener(v -> runBrowse(query.getText().toString().trim()));
        searchRow.addView(searchBtn, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(searchRow, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gen_refresh,
            activity.getString(R.string.mods_show_all), () -> runBrowse(null));

        // Sort row: persists across sessions, applies to whatever is shown.
        UiKit.caption(card, activity.getString(R.string.mods_sort_label));
        CharSequence[] sortLabels = {
            activity.getString(R.string.mods_sort_popular),
            activity.getString(R.string.mods_sort_rating),
            activity.getString(R.string.mods_sort_recent),
            activity.getString(R.string.mods_sort_name),
        };
        UiKit.segmented(card, sortLabels, sortMode, idx -> {
            sortMode = idx;
            saveBrowsePrefs();
            if (!results.isEmpty()) {
                sortResults();
                renderSummaries();
            }
        });
    }

    private void sortResults() {
        Comparator<ModDbClient.ModSummary> cmp;
        switch (sortMode) {
            case SORT_NAME:
                cmp = (a, b) -> a.name.compareToIgnoreCase(b.name);
                break;
            case SORT_RECENT:
                // Site order (last updated) is the freshest-first order we
                // have without parsing dates; keep list order.
            case SORT_POPULAR:
            default:
                // ModDB list order is already popularity-ranked; keep it.
                return;
        }
        results.sort(cmp);
    }

    /** Fetches (or re-fetches) page 1 for a query; null query = show all. */
    private void runBrowse(String query) {
        Activity activity = host.activity();
        showStatus(activity.getString(R.string.mods_loading));
        listHost.removeAllViews();
        LinearLayout loading = UiKit.card(listHost);
        UiKit.supporting(loading, activity.getString(R.string.mods_loading));
        lastQuery = (query != null && !query.isEmpty()) ? query : null;
        browsePage = 1;
        new Thread(() -> {
            List<ModDbClient.ModSummary> fetched = null;
            String error = null;
            try {
                if (lastQuery != null) {
                    fetched = ModDbClient.searchMods(lastQuery);
                } else {
                    boolean[] more = new boolean[1];
                    fetched = ModDbClient.fetchModList(1, more);
                    hasMorePages = more[0];
                }
            } catch (Exception e) {
                error = (e.getMessage() != null) ? e.getMessage() : String.valueOf(e);
            }
            final List<ModDbClient.ModSummary> finalResults = fetched;
            final String finalError = error;
            activity.runOnUiThread(() -> {
                hideStatus();
                if (!isFinishingSafe()) {
                    if (finalError != null) {
                        renderError(activity.getString(R.string.mods_err_fetch, finalError), true);
                    } else {
                        results = finalResults != null ? finalResults : new ArrayList<>();
                        sortResults();
                        renderSummaries();
                    }
                }
            });
        }, "gx-moddb-browse").start();
    }

    /** Loads the next page of the "show all" list and appends it. */
    private void loadMore() {
        Activity activity = host.activity();
        final int nextPage = browsePage + 1;
        showStatus(activity.getString(R.string.mods_loading));
        new Thread(() -> {
            List<ModDbClient.ModSummary> fetched = null;
            String error = null;
            try {
                boolean[] more = new boolean[1];
                fetched = ModDbClient.fetchModList(nextPage, more);
                hasMorePages = more[0];
            } catch (Exception e) {
                error = (e.getMessage() != null) ? e.getMessage() : String.valueOf(e);
            }
            final List<ModDbClient.ModSummary> finalResults = fetched;
            final String finalError = error;
            activity.runOnUiThread(() -> {
                hideStatus();
                if (!isFinishingSafe()) {
                    if (finalError == null && finalResults != null) {
                        browsePage = nextPage;
                        results.addAll(finalResults);
                        renderSummaries();
                    } else if (finalError != null) {
                        toast(activity.getString(R.string.mods_err_fetch, finalError));
                    }
                }
            });
        }, "gx-moddb-more").start();
    }

    private void showStatus(String text) {
        if (statusBar != null) {
            statusBar.setVisibility(View.VISIBLE);
            statusBar.setText(text);
        }
    }

    private void hideStatus() {
        if (statusBar != null) {
            statusBar.setVisibility(View.GONE);
        }
    }

    private void renderError(String message, boolean offerRetry) {
        Activity activity = host.activity();
        listHost.removeAllViews();
        LinearLayout card = UiKit.card(listHost);
        UiKit.sectionHeader(card, R.drawable.ic_gen_info,
            activity.getString(R.string.mods_err_title), false);
        UiKit.supporting(card, message);
        if (offerRetry) {
            UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gen_refresh,
                activity.getString(R.string.mods_retry), () -> runBrowse(lastQuery));
        }
    }

    /**
     * Rich result cards: thumbnail (when the row carries one), rating and
     * download count as supporting metadata, blurb beneath. Everything
     * optional — a field the page didn't provide just doesn't render.
     */
    private void renderSummaries() {
        Activity activity = host.activity();
        listHost.removeAllViews();
        LinearLayout card = UiKit.card(listHost);
        UiKit.sectionHeader(card, R.drawable.ic_gen_globe,
            activity.getString(R.string.mods_card_results, results.size()), false);
        if (results.isEmpty()) {
            UiKit.supporting(card, activity.getString(R.string.mods_no_results));
            return;
        }
        for (ModDbClient.ModSummary mod : results) {
            card.addView(buildModCard(mod));
        }
        if (lastQuery == null && hasMorePages) {
            UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gen_download,
                activity.getString(R.string.mods_page_next), this::loadMore);
        }
    }

    /** One browse card: thumb column + name/metadata/blurb, tap = details. */
    private View buildModCard(ModDbClient.ModSummary mod) {
        Activity activity = host.activity();
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> openModDetails(mod));
        int pad = UiKit.dp(activity, 10f);
        row.setPadding(pad, pad, pad, pad);

        final ImageView thumb = new ImageView(activity);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setContentDescription(activity.getString(R.string.mods_thumb_desc));
        int size = UiKit.dp(activity, 64f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(size, size);
        tlp.setMarginEnd(UiKit.dp(activity, 12f));
        row.addView(thumb, tlp);
        if (mod.imageUrl == null || mod.imageUrl.isEmpty()) {
            thumb.setImageResource(R.drawable.ic_gen_chip);
            thumb.setImageTintList(android.content.res.ColorStateList.valueOf(
                UiKit.color(activity, R.color.gen_primary)));
        } else {
            ThumbCache.load(mod.imageUrl, bitmap -> activity.runOnUiThread(() -> {
                if (bitmap != null && !isFinishingSafe()) {
                    thumb.setImageBitmap(bitmap);
                    thumb.setImageTintList(null);
                }
            }));
        }

        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        row.addView(textCol, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(activity);
        title.setText(mod.name);
        title.setTextSize(15f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(UiKit.color(activity, R.color.gen_on_surface));
        textCol.addView(title);

        StringBuilder meta = new StringBuilder();
        if (mod.rating != null && !mod.rating.isEmpty()) {
            meta.append("\u2605 ").append(mod.rating);
        }
        if (mod.downloads != null && !mod.downloads.isEmpty()) {
            if (meta.length() > 0) {
                meta.append(" \u00b7 ");
            }
            meta.append(mod.downloads);
        }
        if (meta.length() > 0) {
            TextView metaView = new TextView(activity);
            metaView.setText(meta.toString());
            metaView.setTextSize(12f);
            metaView.setTextColor(UiKit.color(activity, R.color.gen_primary));
            textCol.addView(metaView);
        }

        String blurb = (mod.description != null && !mod.description.isEmpty())
            ? mod.description : activity.getString(R.string.mods_no_description);
        TextView blurbView = new TextView(activity);
        blurbView.setText(blurb);
        blurbView.setTextSize(13f);
        blurbView.setMaxLines(2);
        blurbView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        blurbView.setTextColor(UiKit.color(activity, R.color.gen_on_surface_variant));
        textCol.addView(blurbView);

        return row;
    }

    // ------------------------------------------------------- detail screen

    private void openModDetails(ModDbClient.ModSummary mod) {
        detailMod = mod;
        detailData = null;
        detailFiles = null;
        screen = SCREEN_DETAIL;
        rebuild();
    }

    private void buildDetail() {
        Activity activity = host.activity();
        LinearLayout card = UiKit.card(listHost);
        UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gen_chevron,
            activity.getString(R.string.mods_back_to_browse), () -> {
                screen = SCREEN_BROWSE;
                rebuild();
            });

        if (detailData == null) {
            UiKit.sectionHeader(card, R.drawable.ic_gen_chip, detailMod.name, false);
            UiKit.supporting(card, activity.getString(R.string.mods_loading));
            final ModDbClient.ModSummary summary = detailMod;
            new Thread(() -> {
                ModDbClient.ModDetails fetched = null;
                try {
                    fetched = ModDbClient.fetchModDetails(summary);
                } catch (Exception ignored) {
                    // Details are enrichment; a failure leaves a functional
                    // header + file list, not a dead end.
                }
                final ModDbClient.ModDetails finalDetails = fetched;
                activity.runOnUiThread(() -> {
                    if (!isFinishingSafe()) {
                        detailData = finalDetails != null ? finalDetails
                            : new ModDbClient.ModDetails(
                                summary.profilePath, summary.name, "", summary.imageUrl);
                        rebuild();
                    }
                });
            }, "gx-moddb-detail").start();
            return;
        }

        // Rating + downloads as chips (GenLauncher shows these on the mod page).
        LinearLayout chips = new LinearLayout(activity);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        int chipPad = UiKit.dp(activity, 4f);
        chips.setPadding(chipPad, 0, chipPad, 0);
        if (detailData.rating != null && !detailData.rating.isEmpty()) {
            UiKit.chip(chips, R.drawable.ic_gen_check, "\u2605 " + detailData.rating,
                R.color.gen_primary, R.color.gen_surface_container_high);
        }
        if (detailData.downloads != null && !detailData.downloads.isEmpty()) {
            UiKit.chip(chips, R.drawable.ic_gen_download, detailData.downloads,
                R.color.gen_on_surface_variant, R.color.gen_surface_container_high);
        }
        if (chips.getChildCount() > 0) {
            listHost.addView(chips, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        if (detailData.imageUrl != null && !detailData.imageUrl.isEmpty()) {
            LinearLayout hero = UiKit.card(listHost);
            final ImageView image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setContentDescription(activity.getString(R.string.mods_thumb_desc));
            hero.addView(image, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(activity, 170f)));
            ThumbCache.load(detailData.imageUrl, bitmap -> activity.runOnUiThread(() -> {
                if (bitmap != null && !isFinishingSafe()) {
                    image.setImageBitmap(bitmap);
                }
            }));
        }

        // Screenshots strip: horizontally scrolling gallery, GenLauncher-style.
        if (!detailData.screenshots.isEmpty()) {
            LinearLayout shotsCard = UiKit.card(listHost);
            UiKit.sectionHeader(shotsCard, R.drawable.ic_gen_display,
                activity.getString(R.string.mods_screenshots), false);
            HorizontalScrollView scroller = new HorizontalScrollView(activity);
            scroller.setHorizontalScrollBarEnabled(false);
            LinearLayout strip = new LinearLayout(activity);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            scroller.addView(strip, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));
            for (String shotUrl : detailData.screenshots) {
                final ImageView shot = new ImageView(activity);
                shot.setScaleType(ImageView.ScaleType.CENTER_CROP);
                shot.setContentDescription(activity.getString(R.string.mods_screenshot_desc));
                int h = UiKit.dp(activity, 120f);
                LinearLayout.LayoutParams slp =
                    new LinearLayout.LayoutParams(h * 16 / 9, h);
                slp.setMarginEnd(UiKit.dp(activity, 8f));
                strip.addView(shot, slp);
                ThumbCache.load(shotUrl, bitmap -> activity.runOnUiThread(() -> {
                    if (bitmap != null && !isFinishingSafe()) {
                        shot.setImageBitmap(bitmap);
                    }
                }));
            }
            shotsCard.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        LinearLayout body = UiKit.card(listHost);
        UiKit.sectionHeader(body, R.drawable.ic_gen_chip, detailData.name, false);
        String desc = (detailData.description != null && !detailData.description.isEmpty())
            ? detailData.description : activity.getString(R.string.mods_no_description);
        UiKit.supporting(body, desc);
        UiKit.button(body, UiKit.BTN_PRIMARY, R.drawable.ic_gen_download,
            activity.getString(R.string.mods_open_details), this::openModFiles);
    }

    // --------------------------------------------------------- files screen

    private void openModFiles() {
        screen = SCREEN_FILES;
        rebuild();
    }

    /** Opens the release list straight from an update badge on a group. */
    private void openFilesForUpdate(String modName, String profilePath) {
        detailMod = new ModDbClient.ModSummary(modName, profilePath, "", null, null, null);
        detailData = null;
        detailFiles = null;
        screen = SCREEN_FILES;
        rebuild();
    }

    private void buildFiles() {
        Activity activity = host.activity();
        if (detailFiles == null) {
            LinearLayout card = UiKit.card(listHost);
            UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gen_chevron,
                activity.getString(R.string.mods_back_to_browse), () -> {
                    screen = SCREEN_DETAIL;
                    rebuild();
                });
            UiKit.sectionHeader(card, R.drawable.ic_gen_chip, detailMod.name, false);
            UiKit.supporting(card, activity.getString(R.string.mods_loading));
            final ModDbClient.ModSummary summary = detailMod;
            new Thread(() -> {
                List<ModDbClient.ModFile> fetched = null;
                String error = null;
                try {
                    fetched = ModDbClient.fetchModFiles(summary.profilePath);
                } catch (Exception e) {
                    error = (e.getMessage() != null) ? e.getMessage() : String.valueOf(e);
                }
                final List<ModDbClient.ModFile> finalFiles = fetched;
                final String finalError = error;
                activity.runOnUiThread(() -> {
                    if (!isFinishingSafe()) {
                        if (finalError != null) {
                            screen = SCREEN_BROWSE;
                            renderError(activity.getString(R.string.mods_err_fetch, finalError), true);
                        } else {
                            detailFiles = finalFiles != null ? finalFiles : new ArrayList<>();
                            rebuild();
                        }
                    }
                });
            }, "gx-moddb-files").start();
            return;
        }

        listHost.removeAllViews();
        LinearLayout card = UiKit.card(listHost);
        UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gen_chevron,
            activity.getString(R.string.mods_back_to_browse), () -> {
                screen = SCREEN_DETAIL;
                rebuild();
            });
        UiKit.sectionHeader(card, R.drawable.ic_gen_download, detailMod.name, false);
        UiKit.supporting(card, activity.getString(R.string.mods_files_count, detailFiles.size()));
        UiKit.supporting(card, activity.getString(R.string.mods_files_hint));

        if (detailFiles.isEmpty()) {
            UiKit.supporting(card, activity.getString(R.string.mods_no_files));
            return;
        }
        for (ModDbClient.ModFile file : detailFiles) {
            StringBuilder supporting = new StringBuilder();
            if (file.category != null && !file.category.isEmpty()) {
                supporting.append(file.category);
            }
            if (file.date != null && !file.date.isEmpty()) {
                if (supporting.length() > 0) {
                    supporting.append(" \u00b7 ");
                }
                supporting.append(file.date);
            }
            if (file.sizeBytes != null && !file.sizeBytes.isEmpty()) {
                if (supporting.length() > 0) {
                    supporting.append(" \u00b7 ");
                }
                supporting.append(file.sizeBytes);
            }
            UiKit.listRow(card, R.drawable.ic_gen_download, file.name,
                supporting.length() > 0 ? supporting.toString()
                                        : activity.getString(R.string.mods_no_description),
                () -> confirmDownload(detailMod, file));
        }
    }

    private void confirmDownload(ModDbClient.ModSummary mod, ModDbClient.ModFile file) {
        Activity activity = host.activity();
        new android.app.AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.mods_download_title, file.name))
                .setMessage(activity.getString(R.string.mods_download_body,
                    mod.name, activity.getString(R.string.mods_download_note)))
                .setPositiveButton(R.string.mods_download_confirm, (d, w) -> {
                    startInstall(mod, file);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------ install pipeline

    /**
     * Swaps the page for a progress card and runs the download+extract on a
     * worker thread. On failure the card offers Retry (resuming from the
     * .part.dl partial) or Back — a dropped connection no longer throws away
     * a 90%-complete 2 GB download. The release installs as a NEW version
     * leaf beside any existing ones (GenLauncher's model), and its origin is
     * recorded in the sidecar for later update checks.
     */
    private void startInstall(ModDbClient.ModSummary mod, ModDbClient.ModFile file) {
        Activity activity = host.activity();
        if (gameFolder == null) {
            toast(activity.getString(R.string.mods_no_game_folder));
            return;
        }
        installMod = mod;
        installFile = file;
        installBase = fileBaseName(file.name);
        // Cheap guard before a multi-GB download: refuse to start when the
        // drive is nearly full, so the failure happens before the wait, not
        // after 90% of it.
        if (ModInstaller.freeBytes(gameFolder) < 512L * 1024 * 1024) {
            new android.app.AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.mods_err_title))
                    .setMessage(activity.getString(R.string.mods_err_low_storage,
                        humanBytes(ModInstaller.freeBytes(gameFolder))))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        showInstallCard(installBase);
        runInstallThread();
    }

    private void clearPageReferences() {
        installPhaseView = null;
        installBar = null;
        installBytesView = null;
    }

    private void showInstallCard(String base) {
        Activity activity = host.activity();
        clearPageReferences();
        listHost.removeAllViews();
        LinearLayout card = UiKit.card(listHost);
        UiKit.sectionHeader(card, R.drawable.ic_gen_download,
            activity.getString(R.string.mods_installing, base), false);
        installPhaseView = UiKit.supporting(card,
            activity.getString(R.string.mods_phase_starting));
        installBar = new ProgressBar(activity, null,
            android.R.attr.progressBarStyleHorizontal);
        installBar.setMax(10000);
        card.addView(installBar, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        installBytesView = UiKit.supporting(card, "");

        showStatus(activity.getString(R.string.mods_installing_long, base));
    }

    private ModInstaller.Listener makeInstallListener() {
        Activity activity = host.activity();
        // ModInstaller reports plain English phase words; map them to the
        // localized strings here so the extractor stays locale-free.
        final String extractText = activity.getString(R.string.mods_phase_extracting);
        final String resumeText = activity.getString(R.string.mods_phase_resuming);
        return new ModInstaller.Listener() {
            @Override
            public void onPhase(String text) {
                final String localized = "extracting".equals(text) ? extractText
                    : "resuming".equals(text) ? resumeText : text;
                activity.runOnUiThread(() -> {
                    if (installPhaseView != null) {
                        installPhaseView.setText(localized);
                    }
                    if (installBar != null) {
                        installBar.setIndeterminate(true);
                    }
                });
            }

            @Override
            public void onProgress(long done, long total) {
                activity.runOnUiThread(() -> {
                    if (installBar != null) {
                        if (total > 0) {
                            installBar.setIndeterminate(false);
                            installBar.setProgress((int) Math.min(10000, done * 10000 / total));
                        } else {
                            installBar.setIndeterminate(true);
                        }
                    }
                    if (installBytesView != null) {
                        installBytesView.setText(total > 0
                            ? humanBytes(done) + " / " + humanBytes(total) : "");
                    }
                });
            }
        };
    }

    private void runInstallThread() {
        Activity activity = host.activity();
        final ModInstaller.Listener listener = makeInstallListener();
        final String base = installBase;
        new Thread(() -> {
            String error = null;
            try {
                String url = ModDbClient.resolveDownloadUrl(installFile.pagePath);
                File installed = ModInstaller.downloadAndInstall(url, gameFolder,
                    installMod.name, base, installFile.pagePath, listener);
                // GenLauncher style: a single-version ModDB download collapses
                // its redundant <Mod>/<Mod>/ leaf exactly like local installs.
                if (installed != null) {
                    ModInstaller.flattenSingleVersion(installed.getParentFile());
                }
            } catch (Exception e) {
                error = (e.getMessage() != null) ? e.getMessage() : String.valueOf(e);
            }
            final String finalError = error;
            activity.runOnUiThread(() -> {
                if (statusBar != null) {
                    statusBar.setVisibility(View.GONE);
                }
                if (!isFinishingSafe()) {
                    if (finalError != null) {
                        offerInstallRetry(finalError);
                        return;
                    }
                    toast(activity.getString(R.string.mods_install_done, base));
                    screen = SCREEN_INSTALLED;
                    saveBrowsePrefs();
                    rebuild();
                }
            });
        }, "gx-mod-install").start();
    }

    /** Failed install: Retry resumes the partial; nothing restarts from zero. */
    private void offerInstallRetry(String error) {
        Activity activity = host.activity();
        new android.app.AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.mods_err_title))
                .setMessage(activity.getString(R.string.mods_err_install, error))
                .setPositiveButton(R.string.mods_retry, (d, w) -> {
                    showInstallCard(installBase);
                    runInstallThread();
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> rebuild())
                .show();
    }

    /** File base name for a ModDB title, used as folder name + tmp name. */
    private static String fileBaseName(String title) {
        String cleaned = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "mod" : cleaned;
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        for (String unit : new String[]{"KB", "MB", "GB", "TB"}) {
            v /= 1024.0;
            if (v < 1024) {
                return String.format(Locale.US, "%.1f %s", v, unit);
            }
        }
        return String.format(Locale.US, "%.1f PB", v);
    }

    private void onRefresh() {
        if (screen == SCREEN_BROWSE) {
            results = new ArrayList<>();
            runBrowse(lastQuery);
        } else if (screen == SCREEN_INSTALLED) {
            updateByGroup.clear(); // a refresh re-checks updates
            rebuild();
        } else {
            rebuild();
        }
    }

    private void toast(String text) {
        Toast.makeText(host.activity(), text, Toast.LENGTH_LONG).show();
    }

    private boolean isFinishingSafe() {
        Activity activity = host.activity();
        return activity == null || activity.isFinishing();
    }

    // ------------------------------------------------------------ navigation

    /**
     * Host back handling: returns true when Back was consumed by panel
     * navigation (files -> detail -> browse -> installed home); false means
     * the host should do its own Back (finish / switch tab).
     */
    boolean onBackPressed() {
        if (screen == SCREEN_FILES) {
            screen = SCREEN_DETAIL;
            rebuild();
            return true;
        }
        if (screen == SCREEN_DETAIL) {
            screen = SCREEN_BROWSE;
            rebuild();
            return true;
        }
        if (screen == SCREEN_BROWSE && !results.isEmpty()) {
            // Back from results means "leave the list", not "reload it":
            // drop the results without forgetting the search term.
            results = new ArrayList<>();
            hasMorePages = false;
            browsePage = 1;
            screen = SCREEN_INSTALLED;
            saveBrowsePrefs();
            rebuild();
            return true;
        }
        return false;
    }

    // ------------------------------------------------- background rounder

    /** Rounded-corner backgrounds for rows/badges (UiKit is card-scoped). */
    private static final class GradientBg {
        static void apply(View view) {
            android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bg.setCornerRadius(UiKit.dim(view.getContext(), R.dimen.gen_radius_row));
            bg.setColor(UiKit.color(view.getContext(), R.color.gen_surface_container_high));
            view.setBackground(bg);
        }

        static void applyTinted(View view, int colorRes) {
            android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bg.setCornerRadius(UiKit.dp(view.getContext(), 999));
            bg.setColor(UiKit.color(view.getContext(), colorRes));
            view.setBackground(bg);
        }
    }
}
