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
// The in-app mod manager: everything around "play this mod, not vanilla".
//
//   Installed tab  -- <gameFolder>/Mods/* with Play/Delete; the selected
//                     mod's path is written to <filesDir>/mod_launch.cfg,
//                     which SDL3Main.cpp reads and injects as -mod <dir>.
//   Browse tab     -- ModDB's C&C: Generals Zero Hour mod index (search +
//                     pagination + sorting); picking a mod opens its detail
//                     screen (description + image), then its file list;
//                     picking a file downloads and installs it.
//
// Every mod mounts through the engine's own -mod path at archive-overwrite
// priority. The game folder's retail .big files are never touched: mods
// live beside them, one folder each, removed with a single button. Vanilla
// play is "Clear Selection" (or clearing the mod's folder); nothing else
// changes.
//
// GeneralsX @feature 15/09/2026 Professional pass: install from device
// storage (SAF) for mods that aren't on ModDB, pre-launch .big integrity
// check with an override, resumable downloads (a failed download offers
// Retry and continues from the partial), sorted/rich browse cards with
// thumbnails, a mod detail screen before any download, and a free-space
// note beside the installed list.

package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
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
import java.util.List;
import java.util.Locale;

public class ModManagerActivity extends Activity {

    // Screens; a tiny hand-rolled stack because the flow is exactly 4 deep.
    private static final int SCREEN_INSTALLED = 0;
    private static final int SCREEN_BROWSE = 1;
    private static final int SCREEN_DETAIL = 2;
    private static final int SCREEN_FILES = 3;

    private static final int REQ_PICK_ARCHIVE = 4101;

    private static final int SORT_POPULAR = 0;
    private static final int SORT_RATING = 1;
    private static final int SORT_RECENT = 2;
    private static final int SORT_NAME = 3;

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

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.mods_window_title);
        ThumbCache.setCacheDir(getCacheDir());

        gameFolder = SetupActivity.getSavedGamePath(this);
        launchPath = readLaunchCfg();
        restoreBrowsePrefs();
        rebuild();
    }

    private void restoreBrowsePrefs() {
        android.content.SharedPreferences prefs =
            getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE);
        sortMode = clamp(prefs.getInt("mods_sort", SORT_POPULAR), SORT_POPULAR, SORT_NAME);
        // The list itself is not persisted (a stale copy would fight the live
        // site); only the tab the user was on, so re-entering lands sensibly.
        screen = prefs.getBoolean("mods_browsing", false) ? SCREEN_BROWSE : SCREEN_INSTALLED;
    }

    private void saveBrowsePrefs() {
        android.content.SharedPreferences prefs =
            getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
            .putInt("mods_sort", sortMode)
            .putBoolean("mods_browsing", screen != SCREEN_INSTALLED)
            .apply();
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }

    private void rebuild() {
        // Page-scoped views (install progress card) die with the page; drop
        // the references first so a late worker-thread callback can never
        // write into a view that is no longer on screen.
        clearPageReferences();
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(UiKit.color(this, R.color.gzh_background));
        setContentView(shell);
        InsetUtil.applySafeInsets(shell);

        UiKit.appBar(shell, getString(R.string.mods_overline),
            getString(R.string.mods_window_title),
            R.drawable.ic_gzh_refresh, getString(R.string.mods_refresh), this::onRefresh);

        LinearLayout page = UiKit.scrollingPage(shell);

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

        listHost = new LinearLayout(this);
        listHost.setOrientation(LinearLayout.VERTICAL);
        page.addView(listHost, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        switch (screen) {
            case SCREEN_BROWSE:
                buildBrowseSearch();
                if (results.isEmpty() && lastQuery != null) {
                    runBrowse(lastQuery, false);
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
        lp.setMarginStart(UiKit.dp(this, 3f));
        lp.setMarginEnd(UiKit.dp(this, 3f));
        android.widget.Button b = new android.widget.Button(this);
        b.setText(labelRes);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        b.setEnabled(!active); // active tab: pressed-in look via disabled state
        b.setAlpha(active ? 1f : 0.55f);
        row.addView(b, lp);
    }

    // ------------------------------------------------------ installed list

    private void buildInstalled() {
        if (gameFolder == null) {
            UiKit.supporting(listHost, getString(R.string.mods_no_game_folder));
            UiKit.button(listHost, UiKit.BTN_TONAL, R.drawable.ic_gzh_folder,
                getString(R.string.setup_button_select_game_folder), () -> {
                    startActivity(new Intent(this, SetupActivity.class));
                    finish();
                });
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

        List<ModInstaller.InstalledMod> mods = ModInstaller.listInstalled(gameFolder, launchPath);

        LinearLayout launchCard = UiKit.card(listHost);
        UiKit.sectionHeader(launchCard, R.drawable.ic_gzh_play,
            getString(R.string.mods_card_launch), false);
        if (launchPath != null) {
            UiKit.supporting(launchCard, getString(R.string.mods_launch_active,
                new File(launchPath).getName()));
            UiKit.button(launchCard, UiKit.BTN_PRIMARY, R.drawable.ic_gzh_play,
                getString(R.string.mods_button_launch), this::onLaunchGame);
            UiKit.button(launchCard, UiKit.BTN_DANGER, R.drawable.ic_gzh_broom,
                getString(R.string.mods_button_clear_launch), () -> {
                    clearLaunchCfg();
                    launchPath = null;
                    rebuild();
                });
        } else {
            UiKit.supporting(launchCard, getString(R.string.mods_launch_vanilla));
        }

        LinearLayout card = UiKit.card(listHost);
        UiKit.sectionHeader(card, R.drawable.ic_gzh_chip,
            getString(R.string.mods_card_installed, mods.size()), false);

        if (freed > 1024) {
            UiKit.supporting(card,
                getString(R.string.mods_storage_note, humanBytes(ModInstaller.freeBytes(gameFolder)))
                    + " \u00b7 " + getString(R.string.mods_storage_freed, humanBytes(freed)));
        } else {
            UiKit.supporting(card,
                getString(R.string.mods_storage_note, humanBytes(ModInstaller.freeBytes(gameFolder))));
        }

        if (mods.isEmpty()) {
            UiKit.supporting(card, getString(R.string.mods_none_installed));
            UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gzh_download,
                getString(R.string.mods_tab_browse), () -> {
                    screen = SCREEN_BROWSE;
                    saveBrowsePrefs();
                    rebuild();
                });
        } else {
            for (ModInstaller.InstalledMod mod : mods) {
                String supporting = getString(R.string.mods_entry_size, humanBytes(mod.bytesUsed));
                if (mod.selected) {
                    supporting += " \u00b7 " + getString(R.string.mods_installed_active_badge);
                }
                UiKit.listRow(card, R.drawable.ic_gzh_chip, mod.displayName, supporting,
                    () -> onModClicked(mod));
            }
        }
        UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gzh_folder,
            getString(R.string.mods_install_from_files), this::onInstallFromStorage);
        UiKit.supporting(card, getString(R.string.mods_installed_hint));
    }

    private void onModClicked(ModInstaller.InstalledMod mod) {
        if (mod.selected) {
            onLaunchGame(); // tapping the active mod launches
            return;
        }
        String[] options = {
            getString(R.string.mods_action_play),
            getString(R.string.mods_action_delete),
        };
        new android.app.AlertDialog.Builder(this)
                .setTitle(mod.displayName)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        selectForLaunch(mod.dirPath);
                    } else {
                        confirmDelete(mod);
                    }
                })
                .show();
    }

    private void confirmDelete(ModInstaller.InstalledMod mod) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.mods_delete_title, mod.displayName))
                .setMessage(getString(R.string.mods_delete_body, mod.displayName))
                .setPositiveButton(R.string.mods_delete_confirm, (d, w) -> {
                    ModInstaller.deleteRecursively(new File(mod.dirPath));
                    if (mod.selected) {
                        clearLaunchCfg();
                        launchPath = null;
                    }
                    rebuild();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void selectForLaunch(String dirPath) {
        try (FileWriter w = new FileWriter(new File(getFilesDir(), "mod_launch.cfg"), false)) {
            w.write(dirPath);
            w.write("\n");
            launchPath = dirPath;
            rebuild();
        } catch (Exception e) {
            toast(getString(R.string.mods_err_write_cfg, String.valueOf(e)));
        }
    }

    private void clearLaunchCfg() {
        new File(getFilesDir(), "mod_launch.cfg").delete();
    }

    private String readLaunchCfg() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                 new java.io.FileReader(new File(getFilesDir(), "mod_launch.cfg")))) {
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
    private void cleanupTempFilesPreservingResume() {
        if (gameFolder != null) {
            ModInstaller.cleanupTempFiles(gameFolder, installBase);
        }
    }

    private void onLaunchGame() {
        if (launchPath == null) {
            startActivity(new Intent(this, GeneralsZHActivity.class));
            finish();
            return;
        }
        cleanupTempFilesPreservingResume();
        final String problem = ModInstaller.validateMod(launchPath);
        if (problem.isEmpty()) {
            launchNow();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.mods_launch_warning_title)
                .setMessage(getString(R.string.mods_launch_warning_body, problem))
                .setPositiveButton(R.string.mods_launch_anyway, (d, w) -> launchNow())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void launchNow() {
        startActivity(new Intent(this, GeneralsZHActivity.class));
        finish();
    }

    // ------------------------------------------------- install from storage

    /**
     * SAF picker for archives/bigs already on the device. ACTION_OPEN_DOCUMENT
     * with openable types; the picked Uri is streamed to a temp file (the
     * installer operates on real Files) and installed like any download.
     */
    private void onInstallFromStorage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
            "application/zip", "application/x-rar-compressed", "application/x-7z-compressed",
            "application/octet-stream", // .big files report as generic binary
        });
        startActivityForResult(intent, REQ_PICK_ARCHIVE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_ARCHIVE || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        importPickedArchive(data.getData());
    }

    private void importPickedArchive(Uri uri) {
        if (gameFolder == null) {
            toast(getString(R.string.mods_no_game_folder));
            return;
        }
        String displayName = queryDisplayName(uri);
        File staging = new File(getCacheDir(), "import");
        if (!staging.isDirectory() && !staging.mkdirs()) {
            toast(getString(R.string.mods_err_install, "cannot stage import"));
            return;
        }
        File local = new File(staging, displayName);
        showStatus(getString(R.string.mods_loading));
        new Thread(() -> {
            Exception failure = null;
            try (InputStream in = getContentResolver().openInputStream(uri);
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
            runOnUiThread(() -> {
                hideStatus();
                if (copyError != null) {
                    toast(getString(R.string.mods_err_install, String.valueOf(copyError)));
                    return;
                }
                runLocalInstall(local, displayName);
            });
        }, "gx-mod-import").start();
    }

    private void runLocalInstall(File archive, String displayName) {
        showInstallCard(displayName);
        final ModInstaller.Listener listener = makeInstallListener();
        new Thread(() -> {
            String error = null;
            try {
                String modName = displayName;
                int dot = modName.lastIndexOf('.');
                if (dot > 0) {
                    modName = modName.substring(0, dot);
                }
                ModInstaller.installFromLocalFile(archive, gameFolder, modName, listener);
            } catch (Exception e) {
                error = (e.getMessage() != null) ? e.getMessage() : String.valueOf(e);
            } finally {
                archive.delete(); // staging copy no longer needed either way
            }
            final String finalError = error;
            runOnUiThread(() -> {
                statusBar.setVisibility(View.GONE);
                if (finalError != null) {
                    toast(getString(R.string.mods_err_install, finalError));
                    rebuild();
                    return;
                }
                toast(getString(R.string.mods_install_done, displayName));
                rebuild();
            });
        }, "gx-mod-install-local").start();
    }

    private String queryDisplayName(Uri uri) {
        String name = "mod.zip";
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
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

    // ---------------------------------------------------------- browse tab

    private void buildBrowseSearch() {
        LinearLayout card = UiKit.card(listHost);
        UiKit.sectionHeader(card, R.drawable.ic_gzh_globe,
            getString(R.string.mods_card_browse), false);
        UiKit.supporting(card, getString(R.string.mods_browse_hint));

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText query = new EditText(this);
        query.setHint(R.string.mods_search_hint);
        query.setInputType(InputType.TYPE_CLASS_TEXT);
        query.setSingleLine(true);
        query.setTextSize(15f);
        if (lastQuery != null) {
            query.setText(lastQuery);
        }
        query.setOnEditorActionListener((v, actionId, event) -> {
            runBrowse(query.getText().toString().trim(), true);
            return true;
        });
        searchRow.addView(query, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Button searchBtn = new android.widget.Button(this);
        searchBtn.setText(R.string.mods_search_button);
        searchBtn.setOnClickListener(v -> runBrowse(query.getText().toString().trim(), true));
        searchRow.addView(searchBtn, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(searchRow, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gzh_refresh,
            getString(R.string.mods_show_all), () -> runBrowse(null, false));

        // Sort row: persists across sessions, applies to whatever is shown.
        UiKit.caption(card, getString(R.string.mods_sort_label));
        CharSequence[] sortLabels = {
            getString(R.string.mods_sort_popular),
            getString(R.string.mods_sort_rating),
            getString(R.string.mods_sort_recent),
            getString(R.string.mods_sort_name),
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
    private void runBrowse(String query, boolean viaSearch) {
        showStatus(getString(R.string.mods_loading));
        listHost.removeAllViews();
        LinearLayout loading = UiKit.card(listHost);
        UiKit.supporting(loading, getString(R.string.mods_loading));
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
            runOnUiThread(() -> {
                hideStatus();
                if (!isFinishing()) {
                    if (finalError != null) {
                        renderError(getString(R.string.mods_err_fetch, finalError), true);
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
        final int nextPage = browsePage + 1;
        showStatus(getString(R.string.mods_loading));
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
            runOnUiThread(() -> {
                hideStatus();
                if (!isFinishing()) {
                    if (finalError == null && finalResults != null) {
                        browsePage = nextPage;
                        results.addAll(finalResults);
                        renderSummaries();
                    } else if (finalError != null) {
                        toast(getString(R.string.mods_err_fetch, finalError));
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
        listHost.removeAllViews();
        LinearLayout card = UiKit.card(listHost);
        UiKit.sectionHeader(card, R.drawable.ic_gzh_info,
            getString(R.string.mods_err_title), false);
        UiKit.supporting(card, message);
        if (offerRetry) {
            UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gzh_refresh,
                getString(R.string.mods_retry), () -> runBrowse(lastQuery, lastQuery != null));
        }
    }

    /**
     * Rich result cards: thumbnail (when the row carries one), rating and
     * download count as supporting metadata, blurb beneath. Everything
     * optional — a field the page didn't provide just doesn't render.
     */
    private void renderSummaries() {
        listHost.removeAllViews();
        LinearLayout card = UiKit.card(listHost);
        UiKit.sectionHeader(card, R.drawable.ic_gzh_globe,
            getString(R.string.mods_card_results, results.size()), false);
        if (results.isEmpty()) {
            UiKit.supporting(card, getString(R.string.mods_no_results));
            return;
        }
        for (ModDbClient.ModSummary mod : results) {
            card.addView(buildModCard(mod));
        }
        if (lastQuery == null && hasMorePages) {
            UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gzh_download,
                getString(R.string.mods_page_next), this::loadMore);
        }
    }

    /** One browse card: thumb column + name/metadata/blurb, tap = details. */
    private View buildModCard(ModDbClient.ModSummary mod) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> openModDetails(mod));
        int pad = UiKit.dp(this, 10f);
        row.setPadding(pad, pad, pad, pad);

        final ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setContentDescription(getString(R.string.mods_thumb_desc));
        int size = UiKit.dp(this, 64f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(size, size);
        tlp.setMarginEnd(UiKit.dp(this, 12f));
        row.addView(thumb, tlp);
        if (mod.imageUrl == null || mod.imageUrl.isEmpty()) {
            thumb.setImageResource(R.drawable.ic_gzh_chip);
            thumb.setImageTintList(android.content.res.ColorStateList.valueOf(
                UiKit.color(this, R.color.gzh_primary)));
        } else {
            ThumbCache.load(mod.imageUrl, bitmap -> runOnUiThread(() -> {
                if (bitmap != null && !isFinishing()) {
                    thumb.setImageBitmap(bitmap);
                    thumb.setImageTintList(null);
                }
            }));
        }

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        row.addView(textCol, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText(mod.name);
        title.setTextSize(15f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(UiKit.color(this, R.color.gzh_on_surface));
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
            TextView metaView = new TextView(this);
            metaView.setText(meta.toString());
            metaView.setTextSize(12f);
            metaView.setTextColor(UiKit.color(this, R.color.gzh_primary));
            textCol.addView(metaView);
        }

        String blurb = (mod.description != null && !mod.description.isEmpty())
            ? mod.description : getString(R.string.mods_no_description);
        TextView blurbView = new TextView(this);
        blurbView.setText(blurb);
        blurbView.setTextSize(13f);
        blurbView.setMaxLines(2);
        blurbView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        blurbView.setTextColor(UiKit.color(this, R.color.gzh_on_surface_variant));
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
        LinearLayout card = UiKit.card(listHost);
        UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gzh_chevron,
            getString(R.string.mods_back_to_browse), () -> {
                screen = SCREEN_BROWSE;
                rebuild();
            });

        if (detailData == null) {
            UiKit.sectionHeader(card, R.drawable.ic_gzh_chip, detailMod.name, false);
            UiKit.supporting(card, getString(R.string.mods_loading));
            final ModDbClient.ModSummary summary = detailMod;
            new Thread(() -> {
                ModDbClient.ModDetails fetched = null;
                String error = null;
                try {
                    fetched = ModDbClient.fetchModDetails(summary);
                } catch (Exception e) {
                    error = (e.getMessage() != null) ? e.getMessage() : String.valueOf(e);
                }
                final ModDbClient.ModDetails finalDetails = fetched;
                final String finalError = error;
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        if (finalDetails != null) {
                            detailData = finalDetails;
                            rebuild();
                        } else if (finalError != null) {
                            // Details are enrichment; a failure leaves a
                            // functional header + file list, not a dead end.
                            detailData = new ModDbClient.ModDetails(
                                summary.profilePath, summary.name, "", summary.imageUrl);
                            rebuild();
                        }
                    }
                });
            }, "gx-moddb-detail").start();
            return;
        }

        if (detailData.imageUrl != null && !detailData.imageUrl.isEmpty()) {
            LinearLayout hero = UiKit.card(listHost);
            final ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setContentDescription(getString(R.string.mods_thumb_desc));
            hero.addView(image, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(this, 170f)));
            ThumbCache.load(detailData.imageUrl, bitmap -> runOnUiThread(() -> {
                if (bitmap != null && !isFinishing()) {
                    image.setImageBitmap(bitmap);
                }
            }));
        }

        LinearLayout body = UiKit.card(listHost);
        UiKit.sectionHeader(body, R.drawable.ic_gzh_chip, detailData.name, false);
        String desc = (detailData.description != null && !detailData.description.isEmpty())
            ? detailData.description : getString(R.string.mods_no_description);
        UiKit.supporting(body, desc);
        UiKit.button(body, UiKit.BTN_PRIMARY, R.drawable.ic_gzh_download,
            getString(R.string.mods_open_details), this::openModFiles);
    }

    // --------------------------------------------------------- files screen

    private void openModFiles() {
        screen = SCREEN_FILES;
        rebuild();
    }

    private void buildFiles() {
        if (detailFiles == null) {
            LinearLayout card = UiKit.card(listHost);
            UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gzh_chevron,
                getString(R.string.mods_back_to_browse), () -> {
                    screen = SCREEN_DETAIL;
                    rebuild();
                });
            UiKit.sectionHeader(card, R.drawable.ic_gzh_chip, detailMod.name, false);
            UiKit.supporting(card, getString(R.string.mods_loading));
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
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        if (finalError != null) {
                            screen = SCREEN_BROWSE;
                            renderError(getString(R.string.mods_err_fetch, finalError), true);
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
        UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gzh_chevron,
            getString(R.string.mods_back_to_browse), () -> {
                screen = SCREEN_DETAIL;
                rebuild();
            });
        UiKit.sectionHeader(card, R.drawable.ic_gzh_download, detailMod.name, false);
        UiKit.supporting(card, getString(R.string.mods_files_count, detailFiles.size()));
        UiKit.supporting(card, getString(R.string.mods_files_hint));

        if (detailFiles.isEmpty()) {
            UiKit.supporting(card, getString(R.string.mods_no_files));
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
            UiKit.listRow(card, R.drawable.ic_gzh_download, file.name,
                supporting.length() > 0 ? supporting.toString()
                                        : getString(R.string.mods_no_description),
                () -> confirmDownload(detailMod, file));
        }
    }

    private void confirmDownload(ModDbClient.ModSummary mod, ModDbClient.ModFile file) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.mods_download_title, file.name))
                .setMessage(getString(R.string.mods_download_body,
                    mod.name, getString(R.string.mods_download_note)))
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
     * a 90%-complete 2 GB download.
     */
    private void startInstall(ModDbClient.ModSummary mod, ModDbClient.ModFile file) {
        if (gameFolder == null) {
            toast(getString(R.string.mods_no_game_folder));
            return;
        }
        installMod = mod;
        installFile = file;
        installBase = fileBaseName(file.name);
        // Cheap guard before a multi-GB download: refuse to start when the
        // drive is nearly full, so the failure happens before the wait, not
        // after 90% of it.
        if (ModInstaller.freeBytes(gameFolder) < 512L * 1024 * 1024) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.mods_err_title))
                    .setMessage(getString(R.string.mods_err_low_storage,
                        humanBytes(ModInstaller.freeBytes(gameFolder))))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        showInstallCard(installBase);
        runInstallThread(false);
    }

    private void clearPageReferences() {
        installPhaseView = null;
        installBar = null;
        installBytesView = null;
    }

    private void showInstallCard(String base) {
        clearPageReferences();
        listHost.removeAllViews();
        LinearLayout card = UiKit.card(listHost);
        UiKit.sectionHeader(card, R.drawable.ic_gzh_download,
            getString(R.string.mods_installing, base), false);
        installPhaseView = UiKit.supporting(card,
            getString(R.string.mods_phase_starting));
        installBar = new ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal);
        installBar.setMax(10000);
        card.addView(installBar, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        installBytesView = UiKit.supporting(card, "");

        statusBar.setVisibility(View.VISIBLE);
        statusBar.setText(getString(R.string.mods_installing_long, base));
    }

    private ModInstaller.Listener makeInstallListener() {
        // ModInstaller reports plain English phase words; map them to the
        // localized strings here so the extractor stays locale-free.
        final String extractText = getString(R.string.mods_phase_extracting);
        final String resumeText = getString(R.string.mods_phase_resuming);
        return new ModInstaller.Listener() {
            @Override
            public void onPhase(String text) {
                final String localized = "extracting".equals(text) ? extractText
                    : "resuming".equals(text) ? resumeText : text;
                runOnUiThread(() -> {
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
                runOnUiThread(() -> {
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

    private void runInstallThread(final boolean resumed) {
        final ModInstaller.Listener listener = makeInstallListener();
        final String base = installBase;
        new Thread(() -> {
            String error = null;
            try {
                String url = ModDbClient.resolveDownloadUrl(installFile.pagePath);
                ModInstaller.downloadAndInstall(url, gameFolder, installMod.name, base, listener);
            } catch (Exception e) {
                error = (e.getMessage() != null) ? e.getMessage() : String.valueOf(e);
            }
            final String finalError = error;
            runOnUiThread(() -> {
                statusBar.setVisibility(View.GONE);
                if (!isFinishing()) {
                    if (finalError != null) {
                        offerInstallRetry(finalError, resumed);
                        return;
                    }
                    toast(getString(R.string.mods_install_done, base));
                    screen = SCREEN_INSTALLED;
                    saveBrowsePrefs();
                    rebuild();
                }
            });
        }, "gx-mod-install").start();
    }

    /** Failed install: Retry resumes the partial; nothing restarts from zero. */
    private void offerInstallRetry(String error, boolean alreadyResumed) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.mods_err_title))
                .setMessage(getString(R.string.mods_err_install, error))
                .setPositiveButton(R.string.mods_retry, (d, w) -> {
                    showInstallCard(installBase);
                    runInstallThread(alreadyResumed);
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> rebuild())
                .show();
    }

    /** File base name for a ModDB title, used as folder name + tmp name. */
    private static String fileBaseName(String title) {
        String cleaned = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "mod" : cleaned;
    }

    private static String humanBytes(long bytes) {
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
            runBrowse(lastQuery, lastQuery != null);
        } else {
            rebuild();
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // rebuild() is cheap and idempotent; rebuild instead of fighting the
        // layout transition.
        rebuild();
    }

    @Override
    public void onBackPressed() {
        // Detail -> Browse -> tab root; Installed is the back root. Leaving
        // the results list via Back forgets the list but NOT the search term
        // (that only happens via the text box), and the cleared tab must not
        // auto-refetch — Back from results means "leave", not "reload".
        if (screen == SCREEN_FILES) {
            screen = SCREEN_DETAIL;
            rebuild();
            return;
        }
        if (screen == SCREEN_DETAIL) {
            screen = SCREEN_BROWSE;
            rebuild();
            return;
        }
        if (screen == SCREEN_BROWSE && !results.isEmpty()) {
            results = new ArrayList<>();
            hasMorePages = false;
            browsePage = 1;
            lastQuery = null;
            screen = SCREEN_INSTALLED;
            saveBrowsePrefs();
            rebuild();
            return;
        }
        super.onBackPressed();
    }
}
