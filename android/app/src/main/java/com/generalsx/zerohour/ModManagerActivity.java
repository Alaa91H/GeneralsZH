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
//                     pagination); picking a mod lists its files; picking a
//                     file downloads and installs it.
//
// Every mod mounts through the engine's own -mod path at archive-overwrite
// priority. The game folder's retail .big files are never touched: mods
// live beside them, one folder each, removed with a single button. Vanilla
// play is "Clear Selection" (or clearing the mod's folder); nothing else
// changes.

package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.util.List;

public class ModManagerActivity extends Activity {

    private static final String TAG = "GeneralsZH";

    private LinearLayout listHost;
    private TextView statusBar;
    private String gameFolder;
    private String launchPath; // mod dir currently in mod_launch.cfg, or null
    private boolean browsing = false;
    private boolean inFileList = false; // showing one mod's files (back pops this first)

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.mods_window_title);

        gameFolder = SetupActivity.getSavedGamePath(this);
        launchPath = readLaunchCfg();
        rebuild();
    }

    private void rebuild() {
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
        addTabButton(tabs, !browsing, R.string.mods_tab_installed, v -> {
            browsing = false;
            rebuild();
        });
        addTabButton(tabs, browsing, R.string.mods_tab_browse, v -> {
            browsing = true;
            rebuild();
        });

        statusBar = UiKit.body(page, null);
        statusBar.setVisibility(View.GONE);

        listHost = new LinearLayout(this);
        listHost.setOrientation(LinearLayout.VERTICAL);
        page.addView(listHost, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (browsing) {
            buildBrowse();
        } else {
            buildInstalled();
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
        b.setEnabled(true);
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

        if (mods.isEmpty()) {
            UiKit.supporting(card, getString(R.string.mods_none_installed));
            UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gzh_download,
                getString(R.string.mods_tab_browse), () -> {
                    browsing = true;
                    rebuild();
                });
            return;
        }
        for (ModInstaller.InstalledMod mod : mods) {
            String supporting = getString(R.string.mods_entry_size, humanBytes(mod.bytesUsed));
            UiKit.listRow(card, R.drawable.ic_gzh_chip, mod.displayName, supporting, () -> {
                onModClicked(mod);
            });
        }
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

    private void onLaunchGame() {
        startActivity(new Intent(this, GeneralsZHActivity.class));
        finish();
    }

    // ---------------------------------------------------------- browse tab

    private void buildBrowse() {
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
        searchRow.addView(query, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Button searchBtn = new android.widget.Button(this);
        searchBtn.setText(R.string.mods_search_button);
        searchBtn.setOnClickListener(v -> runBrowse(
            () -> ModDbClient.searchMods(query.getText().toString().trim())));
        searchRow.addView(searchBtn, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(searchRow, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gzh_refresh,
            getString(R.string.mods_show_all), () -> runBrowse(
                () -> ModDbClient.fetchModList(1, null)));
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

    /** Fetches the Browse list off-thread and renders it into listHost. */
    private void runBrowse(FetchList fetch) {
        showStatus(getString(R.string.mods_loading));
        listHost.removeAllViews();
        // Keep the search card out of the way while loading.
        LinearLayout loading = UiKit.card(listHost);
        UiKit.supporting(loading, getString(R.string.mods_loading));
        new Thread(() -> {
            List<ModDbClient.ModSummary> results = null;
            String error = null;
            try {
                results = fetch.fetch();
            } catch (Exception e) {
                error = (e.getMessage() != null) ? e.getMessage() : String.valueOf(e);
            }
            final List<ModDbClient.ModSummary> finalResults = results;
            final String finalError = error;
            runOnUiThread(() -> {
                hideStatus();
                if (finalError != null) {
                    renderError(getString(R.string.mods_err_fetch, finalError));
                } else if (finalResults == null || finalResults.isEmpty()) {
                    renderError(getString(R.string.mods_no_results));
                } else {
                    renderSummaries(finalResults);
                }
            });
        }, "gx-moddb-browse").start();
    }

    private interface FetchList {
        List<ModDbClient.ModSummary> fetch() throws Exception;
    }

    private void renderError(String message) {
        listHost.removeAllViews();
        LinearLayout card = UiKit.card(listHost);
        UiKit.sectionHeader(card, R.drawable.ic_gzh_info,
            getString(R.string.mods_err_title), false);
        UiKit.supporting(card, message);
    }

    private void renderSummaries(List<ModDbClient.ModSummary> mods) {
        listHost.removeAllViews();
        LinearLayout card = UiKit.card(listHost);
        UiKit.sectionHeader(card, R.drawable.ic_gzh_globe,
            getString(R.string.mods_card_results, mods.size()), false);
        for (ModDbClient.ModSummary mod : mods) {
            String supporting = (mod.description != null && !mod.description.isEmpty())
                ? mod.description : getString(R.string.mods_no_description);
            UiKit.listRow(card, R.drawable.ic_gzh_chip, mod.name, supporting,
                () -> openModFiles(mod));
        }
    }

    /** Lists a mod's downloadable files, then handles a picked file. */
    private void openModFiles(ModDbClient.ModSummary mod) {
        inFileList = true;
        showStatus(getString(R.string.mods_loading));
        new Thread(() -> {
            List<ModDbClient.ModFile> files = null;
            String error = null;
            try {
                files = ModDbClient.fetchModFiles(mod.profilePath);
            } catch (Exception e) {
                error = (e.getMessage() != null) ? e.getMessage() : String.valueOf(e);
            }
            final List<ModDbClient.ModFile> finalFiles = files;                final String finalError = error;
                runOnUiThread(() -> {
                    hideStatus();
                    if (finalError != null) {
                        inFileList = false;
                        renderError(getString(R.string.mods_err_fetch, finalError));
                    } else if (finalFiles == null || finalFiles.isEmpty()) {
                        inFileList = false;
                        renderError(getString(R.string.mods_no_files));
                    } else {
                        renderFiles(mod, finalFiles);
                    }
                });
        }, "gx-moddb-files").start();
    }

    private void renderFiles(ModDbClient.ModSummary mod, List<ModDbClient.ModFile> files) {
        listHost.removeAllViews();
        LinearLayout card = UiKit.card(listHost);
        UiKit.sectionHeader(card, R.drawable.ic_gzh_download, mod.name, false);
        UiKit.supporting(card, getString(R.string.mods_files_hint));        UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gzh_chevron,
            getString(R.string.mods_back_to_results), () -> {
                inFileList = false;
                rebuild();
            });
        for (ModDbClient.ModFile file : files) {
            String supporting = (file.category != null && !file.category.isEmpty())
                ? file.category : "";
            if (file.date != null && !file.date.isEmpty()) {
                supporting = supporting.isEmpty() ? file.date
                    : supporting + " \u00b7 " + file.date;
            }
            if (file.sizeBytes != null && !file.sizeBytes.isEmpty()) {
                supporting = supporting.isEmpty() ? file.sizeBytes
                    : supporting + " \u00b7 " + file.sizeBytes;
            }
            UiKit.listRow(card, R.drawable.ic_gzh_download, file.name,
                supporting.isEmpty() ? getString(R.string.mods_no_description) : supporting,
                () -> confirmDownload(mod, file));
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

    private void startInstall(ModDbClient.ModSummary mod, ModDbClient.ModFile file) {
        if (gameFolder == null) {
            toast(getString(R.string.mods_no_game_folder));
            return;
        }
        String base = fileBaseName(file.name);
        // Swap the whole page for a progress card; we rebuild from scratch on
        // completion, so no stale references survive the await.
        listHost.removeAllViews();
        LinearLayout card = UiKit.card(listHost);
        UiKit.sectionHeader(card, R.drawable.ic_gzh_download,
            getString(R.string.mods_installing, base), false);
        final TextView phaseView = UiKit.supporting(card,
            getString(R.string.mods_phase_starting));
        final android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal);
        bar.setMax(10000);
        card.addView(bar, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        final TextView bytesView = UiKit.supporting(card, "");

        statusBar.setVisibility(View.VISIBLE);
        statusBar.setText(getString(R.string.mods_installing_long, base));

        // ModInstaller reports plain English phase words; map them to the
        // localized strings here so the extractor stays locale-free.
        final String extractText = getString(R.string.mods_phase_extracting);

        new Thread(() -> {
            ModInstaller.Listener listener = new ModInstaller.Listener() {
                @Override
                public void onPhase(String text) {
                    final String localized = "extracting".equals(text) ? extractText : text;
                    runOnUiThread(() -> phaseView.setText(localized));
                }

                @Override
                public void onProgress(long done, long total) {
                    runOnUiThread(() -> {
                        if (total > 0) {
                            bar.setIndeterminate(false);
                            bar.setProgress((int) Math.min(10000, done * 10000 / total));
                            bytesView.setText(humanBytes(done) + " / " + humanBytes(total));
                        } else {
                            bar.setIndeterminate(true);
                            bytesView.setText("");
                        }
                    });
                }
            };
            String error = null;
            try {
                String url = ModDbClient.resolveDownloadUrl(file.pagePath);
                ModInstaller.downloadAndInstall(url, gameFolder, mod.name, base, listener);
            } catch (Exception e) {
                error = (e.getMessage() != null) ? e.getMessage() : String.valueOf(e);
            }
            final String finalError = error;
            runOnUiThread(() -> {
                statusBar.setVisibility(View.GONE);
                if (finalError != null) {
                    toast(getString(R.string.mods_err_install, finalError));
                    rebuild();
                    return;
                }
                toast(getString(R.string.mods_install_done, base));
                browsing = false; // land on Installed with the new entry
                rebuild();
            });
        }, "gx-mod-install").start();
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
                return String.format(java.util.Locale.US, "%.1f %s", v, unit);
            }
        }
        return String.format(java.util.Locale.US, "%.1f PB", v);
    }

    private void onRefresh() {
        rebuild();
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
        // A file list ("inside" a mod) pops back to the browse list before
        // leaving the activity; the browse list itself is the back root.
        if (browsing && inFileList) {
            inFileList = false;
            rebuild();
            return;
        }
        super.onBackPressed();
    }
}
