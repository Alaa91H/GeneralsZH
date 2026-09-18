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

// GeneralsX @feature Android port mod-launcher 18/09/2026
//
// The mod manager, as a reusable page — GenLauncher-repository only. Every
// mod comes from the curated GenLauncher manifest tree
// (GenLauncherReposClient): the mod is the row, its downloaded base
// versions expand beneath it, and its ModPatches/ModAddons install as
// layers over the active base (ModInstaller.resolveLaunchDir merges them
// at activation; the engine still mounts a single -mod dir).
//
//   Installed view  -- <gameFolder>/Mods/* families: the mod is an
//                      independent card (logo, active version, size, date),
//                      an update badge when the manifest moved, and direct
//                      Play / Update / Versions actions.
//   Repository view -- the curated index (local search filter, manifest
//                      order); a row opens the detail page (hero art,
//                      version, base install/update, patch + addon layers
//                      with per-layer install/update/enable).
//
// Every component records its manifest URL + version in a sidecar
// (.genlauncher_meta), so update checks compare installed versions
// against live manifests with no re-download.

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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
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

    // Screens; a tiny hand-rolled stack because the flow is exactly 3 deep.
    private static final int SCREEN_INSTALLED = 0;
    private static final int SCREEN_REPO = 1;
    private static final int SCREEN_DETAIL = 2;

    static final int REQ_PICK_ARCHIVE = 4101;
    static final int REQ_PICK_FOLDER = 4102;

    private final Host host;
    private final boolean withAppBar; // standalone activity vs Setup tab

    private LinearLayout listHost;
    private LinearLayout resultsHost; // repository results, below the search card
    private TextView statusBar;
    private String gameFolder;
    private String launchPath; // resolved mod dir in mod_launch.cfg, or null
    private String launchBase; // pristine base dir (line 2), for selection state
    private int screen = SCREEN_INSTALLED;

    // Repository state. The index is fetched once per panel life and cached;
    // component manifests resolve on demand into manifestCache.
    private List<GenLauncherReposClient.RepoMod> repoMods;
    private boolean repoLoading = false;
    private String repoError;
    private String repoQuery = "";
    private final Map<String, GenLauncherReposClient.RepoVersion> manifestCache = new HashMap<>();

    // Detail context: the repository mod being shown.
    private String detailName;
    private GenLauncherReposClient.RepoMod detailMod;
    private GenLauncherReposClient.RepoVersion detailBase;
    private final List<LayerRow> detailLayers = new ArrayList<>();
    private ModInstaller.ModGroup detailGroup; // installed family, or null
    private boolean detailLoading = false;
    private int detailReturn = SCREEN_INSTALLED;

    /** One patch/addon row on the detail page. */
    private static final class LayerRow {
        String manifestUrl;
        int kind;
        GenLauncherReposClient.RepoVersion resolved; // null until fetched
        String installedVersion;  // "" when not installed
        boolean enabled;
        String error;
    }

    // Install context: one repository component at a time.
    private GenLauncherReposClient.RepoVersion installingComponent;
    private String installingModName;
    private boolean installingIsLayer;
    private String installBase;
    // Live views of the install progress card; nulled on rebuild so a stale
    // reference is never written to after the page changes.
    private TextView installPhaseView;
    private ProgressBar installBar;
    private TextView installBytesView;

    // Installed state: expanded version lists, per-family update verdicts
    // (missing = not checked yet), and per-layer verdicts keyed
    // "<mod>\0<layerDir>".
    private final HashSet<String> expandedGroups = new HashSet<>();
    private final Map<String, Boolean> updateByMod = new HashMap<>();
    private final Map<String, Boolean> layerUpdateByKey = new HashMap<>();
    private boolean updateCheckRunning = false;
    // Families with a newer release — the in-app update notice.
    private final List<String> modsWithUpdates = new ArrayList<>();
    // Installed base version per family (for "installed X · available Y").
    private final Map<String, String> installedVersionByMod = new HashMap<>();
    private final Map<String, String> availableVersionByMod = new HashMap<>();

    ModsPanel(Activity activity, Host host, boolean withAppBar) {
        super(activity);
        this.host = host;
        this.withAppBar = withAppBar;
        setOrientation(LinearLayout.VERTICAL);
        ThumbCache.setCacheDir(activity.getCacheDir());
        gameFolder = SetupActivity.getSavedGamePath(activity);
        launchPath = readLaunchCfg(activity);
        launchBase = readLaunchBase(activity);
        rebuild();
    }

    // ------------------------------------------------------------ lifecycle

    void notifyGameFolderMaybeChanged() {
        // Setup can change the game folder while this panel is alive.
        gameFolder = SetupActivity.getSavedGamePath(host.activity());
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // GeneralsX @refactor Android port launcher-ui 17/09/2026 The panel
        // now always lives inside a host scrolling page (the merged Home tab),
        // so it measures as a normal WRAP_CONTENT child — no synthetic height
        // claim (the old 0.72-screen hack) is needed or wanted: the host page
        // owns the viewport.
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void rebuild() {
        // Page-scoped views (install progress card) die with the page; drop
        // the references first so a late worker-thread callback can never
        // write into a view that is no longer on screen.
        clearPageReferences();
        removeAllViews();

        Activity activity = host.activity();
        LinearLayout page;
        if (withAppBar) {
            // Standalone activity: the panel owns the whole screen, so it
            // carries its own app bar and scroller.
            setBackgroundColor(UiKit.color(activity, R.color.gen_background));
            InsetUtil.applySafeInsets(this);
            UiKit.appBar(this, activity.getString(R.string.mods_overline),
                activity.getString(R.string.mods_window_title),
                R.drawable.ic_gen_refresh, activity.getString(R.string.mods_refresh),
                this::onRefresh);
            page = UiKit.scrollingPage(this);
        } else {
            // Embedded in the host's scrolling page (merged Home tab): build
            // flat — an inner ScrollView here would nest scrollers and
            // swallow the host's scroll. The host page owns scrolling.
            page = this;
            // Merged-Home section title: the launch/graphics cards above end
            // and the mod manager begins. Standalone mode already names the
            // screen in its app bar, so only the embedded page needs this.
            UiKit.sectionHeader(page, R.drawable.ic_gen_chip,
                activity.getString(R.string.mods_window_title), false);
        }

        // Tab row: Installed | Repository
        LinearLayout tabs = UiKit.buttonRow(page);
        addTabButton(tabs, screen == SCREEN_INSTALLED, R.string.mods_tab_installed, v -> {
            screen = SCREEN_INSTALLED;
            rebuild();
        });
        addTabButton(tabs, screen != SCREEN_INSTALLED, R.string.mods_tab_repository, v -> {
            if (screen == SCREEN_INSTALLED) {
                screen = SCREEN_REPO;
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
            case SCREEN_REPO:
                buildRepo();
                break;
            case SCREEN_DETAIL:
                buildDetail();
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

        List<ModInstaller.ModGroup> groups = ModInstaller.listGroups(gameFolder, selectionPath());

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

        // Header card: count + storage only. Each mod family gets its own
        // independent card below (buildGroupCard), so the list scans as
        // one mod = one card instead of rows nested in a shared container.
        LinearLayout header = UiKit.card(listHost);
        UiKit.sectionHeader(header, R.drawable.ic_gen_chip,
            activity.getString(R.string.mods_card_installed, groups.size()), false);

        if (freed > 1024) {
            UiKit.supporting(header,
                activity.getString(R.string.mods_storage_note, humanBytes(ModInstaller.freeBytes(gameFolder)))
                    + " \u00b7 " + activity.getString(R.string.mods_storage_freed, humanBytes(freed)));
        } else {
            UiKit.supporting(header,
                activity.getString(R.string.mods_storage_note, humanBytes(ModInstaller.freeBytes(gameFolder))));
        }

        if (groups.isEmpty()) {
            UiKit.supporting(header, activity.getString(R.string.mods_none_installed));
            UiKit.button(header, UiKit.BTN_TONAL, R.drawable.ic_gen_download,
                activity.getString(R.string.mods_tab_repository), () -> {
                    screen = SCREEN_REPO;
                    rebuild();
                });
        } else {
            // In-app update notice: the quiet check (or the manual one)
            // found newer releases. Each updated mod is its own tappable
            // row — one tap opens that mod's page, no browsing needed to
            // reach the new file.
            if (!modsWithUpdates.isEmpty()) {
                LinearLayout notice = UiKit.card(listHost);
                GradientBg.applyTinted(notice, R.color.gen_tertiary_container);
                UiKit.sectionHeader(notice, R.drawable.ic_gen_refresh,
                    activity.getString(R.string.mods_updates_notice_title,
                        modsWithUpdates.size()), false);
                UiKit.supporting(notice,
                    activity.getString(R.string.mods_updates_tap_hint));
                for (String updatedName : new ArrayList<>(modsWithUpdates)) {
                    UiKit.listRow(notice, R.drawable.ic_gen_download,
                        updatedName,
                        activity.getString(R.string.mods_update_badge),
                        () -> openRepoDetail(updatedName, SCREEN_INSTALLED));
                }
            }
            for (ModInstaller.ModGroup group : groups) {
                buildGroupCard(group);
            }
            UiKit.button(header, UiKit.BTN_TONAL, R.drawable.ic_gen_refresh,
                activity.getString(R.string.mods_check_updates), this::checkUpdatesManually);
        }
        // Install tools live in their own card so the header stays a
        // two-line summary and the per-mod cards stay uniform.
        LinearLayout tools = UiKit.card(listHost);
        UiKit.button(tools, UiKit.BTN_TONAL, R.drawable.ic_gen_folder,
            activity.getString(R.string.mods_install_from_files), this::onInstallFromStorage);
        UiKit.button(tools, UiKit.BTN_TONAL, R.drawable.ic_gen_folder,
            activity.getString(R.string.mods_import_folder), this::onImportExtractedFolder);
        UiKit.supporting(tools, activity.getString(R.string.mods_pick_archive_or_folder));
        UiKit.supporting(tools, activity.getString(R.string.mods_installed_hint));

        // GenLauncher behavior: updates are noticed without the user asking.
        // Quiet on failure — a flaky network just leaves no badge, and the
        // manual check above reports loudly instead.
        if (!updateCheckRunning && !groups.isEmpty() && updateByMod.isEmpty()) {
            checkRepoUpdates(groups, null);
        }
    }

    /**
     * One mod family as an independent card: logo, name, active version,
     * size, install date, a tappable update badge when a newer release
     * exists, and compact Play/Update/Versions actions — the version list
     * stays expandable for the multi-version case.
     */
    private void buildGroupCard(ModInstaller.ModGroup group) {
        Activity activity = host.activity();
        // Independent card per mod (not a row nested in the header card),
        // so the Installed list scans as one mod = one card.
        LinearLayout card = UiKit.card(listHost);

        // Header: logo + name/meta column + update badge.
        LinearLayout headerRow = new LinearLayout(activity);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(headerRow, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final ImageView logo = new ImageView(activity);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setContentDescription(group.modName);
        int logoSize = UiKit.dp(activity, 48f);
        android.graphics.drawable.GradientDrawable logoBg =
            new android.graphics.drawable.GradientDrawable();
        logoBg.setCornerRadius(UiKit.dp(activity, 12f));
        logoBg.setColor(UiKit.color(activity, R.color.gen_surface_container_highest));
        logo.setBackground(logoBg);
        logo.setImageResource(R.drawable.ic_gen_chip);
        logo.setImageTintList(android.content.res.ColorStateList.valueOf(
            UiKit.color(activity, R.color.gen_on_surface_faint)));
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(logoSize, logoSize);
        logoLp.setMarginEnd(UiKit.dp(activity, 12f));
        headerRow.addView(logo, logoLp);
        String logoUrl = ModInstaller.readLogo(gameFolder, group.modName);
        if (logoUrl != null) {
            ThumbCache.load(logoUrl, bmp -> logo.post(() -> {
                if (bmp != null && !bmp.isRecycled()) {
                    logo.setImageBitmap(bmp);
                    logo.setImageTintList(null);
                }
            }));
        }

        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        headerRow.addView(textCol, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(activity);
        title.setText(group.modName);
        title.setTextSize(15f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(UiKit.color(activity, R.color.gen_on_surface));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(title);

        // Meta line: active version (or count) + size + install date —
        // short by design; the version list carries the rest.
        StringBuilder meta = new StringBuilder();
        ModInstaller.InstalledMod active = null;
        for (ModInstaller.InstalledMod v : group.versions) {
            if (v.selected) {
                active = v;
                break;
            }
        }
        if (active != null) {
            meta.append(activity.getString(R.string.mods_row_active, active.displayName));
        } else if (group.versions.size() == 1) {
            meta.append(group.versions.get(0).displayName);
        } else {
            meta.append(activity.getString(R.string.mods_group_versions, group.versions.size()));
        }
        meta.append(" \u00b7 ").append(humanBytes(group.bytesUsed));
        if (group.latestInstalledAt > 0) {
            meta.append(" \u00b7 ").append(android.text.format.DateFormat.getDateFormat(activity)
                .format(new java.util.Date(group.latestInstalledAt)));
        }
        TextView metaView = new TextView(activity);
        metaView.setText(meta.toString());
        metaView.setTextSize(12f);
        metaView.setSingleLine(true);
        metaView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        metaView.setTextColor(UiKit.color(activity, R.color.gen_on_surface_variant));
        textCol.addView(metaView);

        final boolean hasUpdate = Boolean.TRUE.equals(updateByMod.get(group.modName));
        if (hasUpdate) {
            TextView badge = new TextView(activity);
            badge.setText(R.string.mods_update_badge);
            badge.setTextSize(12f);
            badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            badge.setSingleLine(true);
            badge.setTextColor(UiKit.color(activity, R.color.gen_tertiary));
            badge.setPadding(UiKit.dp(activity, 8f), UiKit.dp(activity, 3f),
                UiKit.dp(activity, 8f), UiKit.dp(activity, 3f));
            GradientBg.applyTinted(badge, R.color.gen_tertiary_container);
            // The badge is the update entry point: one tap opens the
            // mod's page on the new release.
            badge.setClickable(true);
            badge.setFocusable(true);
            badge.setOnClickListener(v -> openRepoDetail(group.modName, SCREEN_INSTALLED));
            headerRow.addView(badge);
        }

        // Compact actions, two rows of two: four equal buttons in one row
        // squeeze Arabic labels into ellipsis (observed on-device), while
        // full-width stacked buttons would triple the card height. Play +
        // Update first (the moves that matter), Versions + Delete second.
        LinearLayout row1 = new LinearLayout(activity);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(row1, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        if (group.anySelected) {
            addCardAction(row1, true, R.drawable.ic_gen_play,
                activity.getString(R.string.mods_action_play_short),
                () -> selectAndLaunch(group));
        } else {
            addCardAction(row1, false, R.drawable.ic_gen_play,
                activity.getString(R.string.mods_action_set_active), () -> {
                    selectForLaunch(group.modName, group.versions.get(0).dirPath, null);
                });
        }
        addCardAction(row1, false, R.drawable.ic_gen_download,
            activity.getString(R.string.mods_action_update),
            () -> onUpdateCard(group));
        LinearLayout row2 = new LinearLayout(activity);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(row2, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addCardAction(row2, false, R.drawable.ic_gen_broom,
            activity.getString(R.string.mods_action_versions), () -> {
                if (!expandedGroups.remove(group.modName)) {
                    expandedGroups.add(group.modName);
                }
                rebuild();
            });
        addCardAction(row2, false, R.drawable.ic_gen_trash,
            activity.getString(R.string.mods_action_delete),
            () -> confirmDeleteFamily(group));

        // Layers entry: patches & addons live on the detail page; the row
        // shows how many are installed and how many are enabled.
        int layerCount = ModInstaller.listLayers(gameFolder, group.modName).size();
        if (layerCount > 0 || hasUpdate) {
            int enabled = ModInstaller.readEnabledLayers(gameFolder, group.modName).size();
            String supporting = layerCount == 0
                ? activity.getString(R.string.mods_update_badge)
                : activity.getString(R.string.mods_layers_state, enabled, layerCount);
            UiKit.listRow(card, R.drawable.ic_gen_chip,
                activity.getString(R.string.mods_layers_title), supporting,
                () -> openRepoDetail(group.modName, SCREEN_INSTALLED));
        }

        if (expandedGroups.contains(group.modName)) {
            LinearLayout versions = new LinearLayout(activity);
            versions.setOrientation(LinearLayout.VERTICAL);
            versions.setPadding(0, UiKit.dp(activity, 8f), 0, 0);
            for (ModInstaller.InstalledMod version : group.versions) {
                String supporting = humanBytes(version.bytesUsed)
                    + (version.installedAt > 0
                        ? " \u00b7 " + android.text.format.DateFormat.getDateFormat(activity)
                              .format(new java.util.Date(version.installedAt))
                        : "")
                    + (version.selected
                        ? " \u00b7 " + activity.getString(R.string.mods_installed_active_badge)
                        : "");
                UiKit.listRow(versions, R.drawable.ic_gen_chip, version.displayName, supporting,
                    () -> onVersionClicked(group, version));
            }
            card.addView(versions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    /**
     * One compact weighted action for a mod card's horizontal row: small
     * pill button, single-line label, equal share of the width.
     */
    private void addCardAction(LinearLayout row, boolean primary, int iconRes,
                               String label, Runnable onClick) {
        Activity activity = host.activity();
        com.google.android.material.button.MaterialButton b =
            new com.google.android.material.button.MaterialButton(activity);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13f);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setMaxLines(1);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setCornerRadius(UiKit.dp(activity, 20));
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(UiKit.dp(activity, 40));
        int hPad = UiKit.dp(activity, 10);
        int vPad = UiKit.dp(activity, 8);
        b.setPadding(hPad, vPad, hPad, vPad);
        b.setGravity(Gravity.CENTER);
        b.setElevation(0f);
        b.setStateListAnimator(null);
        b.setStrokeWidth(0);
        if (iconRes != 0) {
            android.graphics.drawable.Drawable icon =
                androidx.core.content.ContextCompat.getDrawable(activity, iconRes);
            b.setIcon(icon);
            b.setIconSize(UiKit.dp(activity, 18));
            b.setIconPadding(UiKit.dp(activity, 6));
            b.setIconGravity(
                com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START);
        }
        if (primary) {
            b.setBackgroundTintList(UiKit.tint(activity,
                R.color.gen_primary, R.color.gen_container_disabled));
            b.setTextColor(UiKit.tint(activity,
                R.color.gen_on_primary, R.color.gen_on_surface_disabled));
            b.setIconTint(UiKit.tint(activity,
                R.color.gen_on_primary, R.color.gen_on_surface_disabled));
        } else {
            b.setBackgroundTintList(UiKit.tint(activity,
                R.color.gen_surface_container_high, R.color.gen_container_disabled));
            b.setTextColor(UiKit.tint(activity,
                R.color.gen_on_surface, R.color.gen_on_surface_disabled));
            b.setIconTint(UiKit.tint(activity,
                R.color.gen_primary, R.color.gen_on_surface_disabled));
        }
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (row.getChildCount() > 0) {
            lp.setMarginStart(UiKit.dp(activity, 6f));
        }
        lp.topMargin = UiKit.dp(activity, 10f);
        row.addView(b, lp);
    }

    private void onVersionClicked(ModInstaller.ModGroup group, ModInstaller.InstalledMod version) {
        if (version.selected) {
            selectAndLaunch(group); // tapping the active version launches
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
                        selectForLaunch(group.modName, version.dirPath, this::onLaunchGame);
                    } else {
                        confirmDelete(group, version);
                    }
                })
                .show();
    }

    private void confirmDelete(ModInstaller.ModGroup group, ModInstaller.InstalledMod version) {
        Activity activity = host.activity();
        new android.app.AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.mods_delete_title, version.displayName))
                .setMessage(activity.getString(R.string.mods_delete_body, version.displayName))
                .setPositiveButton(R.string.mods_delete_confirm, (d, w) -> {
                    ModInstaller.deleteRecursively(new File(version.dirPath));
                    // The merged tree (if any) is stale without its base, and
                    // a launch pointer into this family no longer resolves.
                    ModInstaller.dropActiveDir(gameFolder, group.modName);
                    if (launchPath != null && launchPath.startsWith(
                            new File(gameFolder, "Mods" + File.separator + group.modName)
                                .getAbsolutePath())) {
                        clearLaunchCfg();
                        launchPath = null;
                        launchBase = null;
                    }
                    rebuild();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * GeneralsX @feature 18/09/2026 Whole-family delete from the card: every
     * version, every layer, the merged tree, sidecars and logo go in one
     * confirmed step. The retail game files are elsewhere and untouched.
     */
    private void confirmDeleteFamily(ModInstaller.ModGroup group) {
        Activity activity = host.activity();
        new android.app.AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.mods_delete_title, group.modName))
                .setMessage(activity.getString(R.string.mods_delete_body,
                    group.modName + " (" + activity.getString(
                        R.string.mods_group_versions, group.versions.size()) + ")"))
                .setPositiveButton(R.string.mods_delete_confirm, (d, w) -> {
                    ModInstaller.deleteRecursively(new File(gameFolder,
                        "Mods" + File.separator + group.modName));
                    new File(ModInstaller.modsRoot(gameFolder),
                        group.modName + ".logo").delete();
                    if (launchPath != null && launchPath.startsWith(
                            new File(gameFolder, "Mods" + File.separator + group.modName)
                                .getAbsolutePath())) {
                        clearLaunchCfg();
                        launchPath = null;
                        launchBase = null;
                    }
                    updateByMod.remove(group.modName);
                    modsWithUpdates.remove(group.modName);
                    rebuild();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * GeneralsX @feature 18/09/2026 Per-card update entry. When the quiet
     * check already flagged this mod, one tap opens its page on the new
     * release; otherwise a single-family manifest check runs first and the
     * card either gains its badge (page opens) or reports up to date.
     */
    private void onUpdateCard(ModInstaller.ModGroup group) {
        if (Boolean.TRUE.equals(updateByMod.get(group.modName))) {
            openRepoDetail(group.modName, SCREEN_INSTALLED);
            return;
        }
        Activity activity = host.activity();
        showStatus(activity.getString(R.string.mods_checking_updates));
        updateByMod.remove(group.modName);
        new Thread(() -> {
            ensureIndexLoaded();
            checkOneFamily(group);
            activity.runOnUiThread(() -> {
                hideStatus();
                if (isFinishingSafe()) {
                    return;
                }
                rebuild();
                if (Boolean.TRUE.equals(updateByMod.get(group.modName))) {
                    openRepoDetail(group.modName, SCREEN_INSTALLED);
                } else {
                    Toast.makeText(activity, R.string.mods_up_to_date,
                        Toast.LENGTH_SHORT).show();
                }
            });
        }, "gx-mod-update-one").start();
    }

    // -------------------------------------------------------- update checks

    /**
     * Manifest-driven update detection: for every installed component with
     * a repository sidecar, fetch its manifest and compare versions. One
     * cheap YAML GET per component; failures stay quiet (no badge) and the
     * manual check reports loudly instead.
     */
    private void checkRepoUpdates(List<ModInstaller.ModGroup> groups, Runnable onDone) {
        updateCheckRunning = true;
        final List<ModInstaller.ModGroup> work = new ArrayList<>(groups);
        new Thread(() -> {
            ensureIndexLoaded();
            for (ModInstaller.ModGroup group : work) {
                if (updateByMod.containsKey(group.modName)) {
                    continue; // already checked this session
                }
                checkOneFamily(group);
            }
            updateCheckRunning = false;
            Activity activity = host.activity();
            final List<String> updated = new ArrayList<>();
            for (ModInstaller.ModGroup g : work) {
                if (Boolean.TRUE.equals(updateByMod.get(g.modName))) {
                    updated.add(g.modName);
                }
            }
            activity.runOnUiThread(() -> {
                if (!isFinishingSafe()) {
                    modsWithUpdates.clear();
                    modsWithUpdates.addAll(updated);
                    rebuild();
                    if (!updated.isEmpty()) {
                        Toast.makeText(activity,
                            activity.getString(R.string.mods_updates_notice,
                                updated.size()),
                            Toast.LENGTH_LONG).show();
                    }
                    if (onDone != null) {
                        onDone.run();
                    }
                }
            });
        }, "gx-mod-update-check").start();
    }

    /** Checks one family: base versions plus installed layers. */
    private void checkOneFamily(ModInstaller.ModGroup group) {
        boolean update = false;
        // Base: any installed MOD-kind sidecar whose manifest moved.
        for (ModInstaller.InstalledMod version : group.versions) {
            ModInstaller.ComponentMeta meta = ModInstaller.readMeta(
                new File(version.dirPath).getParentFile(), version.displayName);
            if (meta == null || meta.manifestUrl.isEmpty()
                    || meta.kind != GenLauncherReposClient.KIND_MOD) {
                continue;
            }
            installedVersionByMod.put(group.modName, meta.version);
            try {
                GenLauncherReposClient.RepoVersion current =
                    cachedManifest(meta.manifestUrl);
                if (current != null) {
                    availableVersionByMod.put(group.modName, current.version);
                    if (GenLauncherReposClient.isUpdate(current.version, meta.version)) {
                        update = true;
                    }
                }
            } catch (Exception ignored) {
                // Quiet: a failed check just leaves no badge this session.
            }
        }
        // Layers: each installed layer compares against its own manifest.
        for (String layer : ModInstaller.listLayers(gameFolder, group.modName)) {
            ModInstaller.ComponentMeta meta = ModInstaller.readMeta(
                ModInstaller.layersRoot(gameFolder, group.modName), layer);
            String key = group.modName + " " + layer;
            if (meta == null || meta.manifestUrl.isEmpty()) {
                layerUpdateByKey.put(key, false);
                continue;
            }
            try {
                GenLauncherReposClient.RepoVersion current =
                    cachedManifest(meta.manifestUrl);
                boolean layerUpdate = current != null
                    && GenLauncherReposClient.isUpdate(current.version, meta.version);
                layerUpdateByKey.put(key, layerUpdate);
                update |= layerUpdate;
            } catch (Exception ignored) {
                layerUpdateByKey.put(key, false);
            }
        }
        updateByMod.put(group.modName, update);
    }

    /** Manifest fetch with an in-memory cache (per panel life). */
    private GenLauncherReposClient.RepoVersion cachedManifest(String url) throws IOException {
        GenLauncherReposClient.RepoVersion cached = manifestCache.get(url);
        if (cached != null) {
            return cached;
        }
        GenLauncherReposClient.RepoVersion fetched =
            GenLauncherReposClient.fetchVersion(url);
        if (fetched.isDownloadable()) {
            GenLauncherReposClient.fillS3Objects(fetched);
        }
        manifestCache.put(url, fetched);
        return fetched;
    }

    /** Loads the repository index in the background if needed. */
    private void ensureIndexLoaded() {
        if (repoMods != null || repoLoading) {
            return;
        }
        try {
            repoMods = GenLauncherReposClient.fetchRepoMods();
            repoError = null;
        } catch (Exception e) {
            repoMods = null;
            repoError = (e.getMessage() != null) ? e.getMessage() : String.valueOf(e);
        }
    }

    private void checkUpdatesManually() {
        if (gameFolder == null) {
            return;
        }
        updateByMod.clear(); // force a fresh check even for cached verdicts
        layerUpdateByKey.clear();
        modsWithUpdates.clear();
        installedVersionByMod.clear();
        availableVersionByMod.clear();
        List<ModInstaller.ModGroup> groups = ModInstaller.listGroups(gameFolder, selectionPath());
        showStatus(host.activity().getString(R.string.mods_checking_updates));
        checkRepoUpdates(groups, () -> {
            hideStatus();
            int found = 0;
            for (Boolean b : updateByMod.values()) {
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

    /**
     * Activates a base version: merges enabled layers over it (or selects
     * it directly when none are enabled) and records the resolved dir plus
     * the pristine base dir (second line, new in 1.6 — old one-line files
     * still read fine) in mod_launch.cfg.
     */
    private void selectForLaunch(String modName, String baseDirPath, Runnable after) {
        Activity activity = host.activity();
        showStatus(activity.getString(R.string.mods_launch_resolving));
        new Thread(() -> {
            String resolved = baseDirPath;
            String error = null;
            try {
                resolved = ModInstaller.resolveLaunchDir(gameFolder, modName, baseDirPath);
            } catch (Exception e) {
                error = (e.getMessage() != null) ? e.getMessage() : String.valueOf(e);
            }
            final String finalResolved = resolved;
            final String finalError = error;
            activity.runOnUiThread(() -> {
                hideStatus();
                if (isFinishingSafe()) {
                    return;
                }
                if (finalError != null) {
                    toast(activity.getString(R.string.mods_err_write_cfg, finalError));
                    return;
                }
                try (FileWriter w = new FileWriter(
                        new File(activity.getFilesDir(), "mod_launch.cfg"), false)) {
                    w.write(finalResolved);
                    w.write("\n");
                    w.write(baseDirPath);
                    w.write("\n");
                    launchPath = finalResolved;
                    launchBase = baseDirPath;
                    rebuild();
                    if (after != null) {
                        after.run();
                    }
                } catch (Exception e) {
                    toast(activity.getString(R.string.mods_err_write_cfg, String.valueOf(e)));
                }
            });
        }, "gx-mod-activate").start();
    }

    /** Play path: activate (merge layers) first, then run the launch gate. */
    private void selectAndLaunch(ModInstaller.ModGroup group) {
        ModInstaller.InstalledMod base = null;
        for (ModInstaller.InstalledMod v : group.versions) {
            if (v.selected) {
                base = v;
                break;
            }
        }
        if (base == null) {
            base = group.versions.get(0);
        }
        final String baseDir = base.dirPath;
        // Already pointing at this family's resolved dir: just launch.
        if (launchPath != null && base.selected
                && launchPath.startsWith(new File(baseDir).getParent())) {
            onLaunchGame();
            return;
        }
        selectForLaunch(group.modName, baseDir, this::onLaunchGame);
    }

    private void clearLaunchCfg() {
        new File(host.activity().getFilesDir(), "mod_launch.cfg").delete();
        launchBase = null;
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
     * Pristine base dir behind the resolved launch (line 2, written since
     * 1.6). Falls back to line 1 for older files, so selection state keeps
     * working across the upgrade.
     */
    static String readLaunchBase(Activity activity) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                 new java.io.FileReader(new File(activity.getFilesDir(), "mod_launch.cfg")))) {
            r.readLine();
            String base = r.readLine();
            if (base != null && !base.trim().isEmpty()) {
                return base.trim();
            }
        } catch (Exception ignored) {
            // fall through to line 1
        }
        return readLaunchCfg(activity);
    }

    /** Version dir used for selection flags (base, not the merged tree). */
    private String selectionPath() {
        return launchBase != null ? launchBase : launchPath;
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

    // ---------------------------------------------------------- repository

    private void buildRepo() {
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
        if (!repoQuery.isEmpty()) {
            query.setText(repoQuery);
        }
        query.setOnEditorActionListener((v, actionId, event) -> {
            repoQuery = query.getText().toString().trim();
            rebuild();
            return true;
        });
        searchRow.addView(query, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Button searchBtn = new android.widget.Button(activity);
        searchBtn.setText(R.string.mods_search_button);
        searchBtn.setOnClickListener(v -> {
            repoQuery = query.getText().toString().trim();
            rebuild();
        });
        searchRow.addView(searchBtn, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(searchRow, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        resultsHost = new LinearLayout(activity);
        resultsHost.setOrientation(LinearLayout.VERTICAL);
        listHost.addView(resultsHost, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (repoMods == null && !repoLoading) {
            UiKit.supporting(card, activity.getString(R.string.mods_loading));
            repoLoading = true;
            new Thread(() -> {
                ensureIndexLoaded();
                activity.runOnUiThread(() -> {
                    repoLoading = false;
                    if (!isFinishingSafe()) {
                        rebuild();
                    }
                });
            }, "gx-repos-index").start();
            return;
        }
        if (repoMods == null) {
            UiKit.supporting(card, activity.getString(R.string.mods_loading));
            return;
        }
        if (repoError != null) {
            renderError(activity.getString(R.string.mods_err_fetch, repoError), true);
            return;
        }
        renderRepoList();
    }

    /** The curated list: logo, name, layer counts; tap = detail page. */
    private void renderRepoList() {
        Activity activity = host.activity();
        if (resultsHost == null) {
            return;
        }
        resultsHost.removeAllViews();
        String q = repoQuery.toLowerCase(Locale.US);
        List<GenLauncherReposClient.RepoMod> shown = new ArrayList<>();
        for (GenLauncherReposClient.RepoMod mod : repoMods) {
            if (q.isEmpty() || mod.name.toLowerCase(Locale.US).contains(q)) {
                shown.add(mod);
            }
        }
        LinearLayout card = UiKit.card(resultsHost);
        UiKit.sectionHeader(card, R.drawable.ic_gen_chip,
            activity.getString(R.string.mods_repo_title, shown.size()), false);
        UiKit.supporting(card, activity.getString(R.string.mods_repo_hint));
        if (shown.isEmpty()) {
            UiKit.supporting(card, activity.getString(R.string.mods_repo_no_match));
            return;
        }
        for (GenLauncherReposClient.RepoMod mod : shown) {
            card.addView(buildRepoRow(mod));
        }
    }

    private View buildRepoRow(GenLauncherReposClient.RepoMod mod) {
        Activity activity = host.activity();
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> openRepoDetail(mod.name, SCREEN_REPO));
        int pad = UiKit.dp(activity, 10f);
        row.setPadding(pad, pad, pad, pad);

        final ImageView thumb = new ImageView(activity);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setContentDescription(mod.name);
        int size = UiKit.dp(activity, 52f);
        android.graphics.drawable.GradientDrawable thumbBg =
            new android.graphics.drawable.GradientDrawable();
        thumbBg.setCornerRadius(UiKit.dp(activity, 10f));
        thumbBg.setColor(UiKit.color(activity, R.color.gen_surface_container_highest));
        thumb.setBackground(thumbBg);
        thumb.setImageResource(R.drawable.ic_gen_chip);
        thumb.setImageTintList(android.content.res.ColorStateList.valueOf(
            UiKit.color(activity, R.color.gen_on_surface_faint)));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(size, size);
        tlp.setMarginEnd(UiKit.dp(activity, 12f));
        row.addView(thumb, tlp);
        // The list carries the base logo once its manifest resolved
        // (progressively after the index load); until then the placeholder.
        GenLauncherReposClient.RepoVersion cached = manifestCache.get(mod.manifestUrl);
        String logoUrl = cached != null ? cached.imageUrl
            : ModInstaller.readLogo(gameFolder, mod.name);
        if (logoUrl != null) {
            ThumbCache.load(logoUrl, bitmap -> thumb.post(() -> {
                if (bitmap != null && !bitmap.isRecycled()) {
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
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTextColor(UiKit.color(activity, R.color.gen_on_surface));
        textCol.addView(title);

        StringBuilder meta = new StringBuilder();
        if (cached != null && cached.version != null) {
            meta.append(cached.version);
        }
        if (mod.layerCount() > 0) {
            if (meta.length() > 0) {
                meta.append(" \u00b7 ");
            }
            meta.append(activity.getString(R.string.mods_layers_count, mod.layerCount()));
        }
        Boolean update = updateByMod.get(mod.name);
        if (meta.length() == 0 && !Boolean.TRUE.equals(update)) {
            meta.append(activity.getString(R.string.mods_tap_for_details));
        }
        TextView metaView = new TextView(activity);
        metaView.setText(meta.toString());
        metaView.setTextSize(12f);
        metaView.setSingleLine(true);
        metaView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        metaView.setTextColor(UiKit.color(activity,
            Boolean.TRUE.equals(update) ? R.color.gen_tertiary
                                        : R.color.gen_on_surface_variant));
        textCol.addView(metaView);

        if (Boolean.TRUE.equals(update)) {
            TextView badge = new TextView(activity);
            badge.setText(R.string.mods_update_badge);
            badge.setTextSize(12f);
            badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            badge.setSingleLine(true);
            badge.setTextColor(UiKit.color(activity, R.color.gen_tertiary));
            badge.setPadding(UiKit.dp(activity, 8f), UiKit.dp(activity, 3f),
                UiKit.dp(activity, 8f), UiKit.dp(activity, 3f));
            GradientBg.applyTinted(badge, R.color.gen_tertiary_container);
            row.addView(badge);
        }
        return row;
    }

    // ------------------------------------------------------- detail screen

    /** Opens a mod's page (base + patches + addons) from list/notice/badge. */
    private void openRepoDetail(String modName, int returnScreen) {
        detailName = modName;
        detailReturn = returnScreen;
        detailMod = null;
        detailBase = null;
        detailLayers.clear();
        detailGroup = null;
        detailLoading = true;
        if (screen != SCREEN_DETAIL) {
            screen = SCREEN_DETAIL;
        }
        rebuild();
        new Thread(() -> {
            GenLauncherReposClient.RepoMod mod = null;
            GenLauncherReposClient.RepoVersion base = null;
            List<LayerRow> layers = new ArrayList<>();
            String error = null;
            try {
                ensureIndexLoaded();
                if (repoMods != null) {
                    for (GenLauncherReposClient.RepoMod m : repoMods) {
                        if (m.name.equals(detailName)) {
                            mod = m;
                            break;
                        }
                    }
                }
                if (mod == null) {
                    // Installed but not in the index (storage import): the
                    // page still shows installed components below.
                    mod = new GenLauncherReposClient.RepoMod(detailName);
                }
                if (mod.manifestUrl != null) {
                    base = cachedManifest(mod.manifestUrl);
                    ModInstaller.writeLogo(gameFolder, mod.name, base.imageUrl);
                    for (String url : mod.patchUrls) {
                        layers.add(resolveLayer(url, GenLauncherReposClient.KIND_PATCH));
                    }
                    for (String url : mod.addonUrls) {
                        layers.add(resolveLayer(url, GenLauncherReposClient.KIND_ADDON));
                    }
                }
            } catch (Exception e) {
                error = (e.getMessage() != null) ? e.getMessage() : String.valueOf(e);
            }
            if (mod == null) {
                // Total failure still renders a page (installed parts +
                // error), never a stuck spinner.
                mod = new GenLauncherReposClient.RepoMod(detailName);
            }
            final GenLauncherReposClient.RepoMod finalMod = mod;
            final GenLauncherReposClient.RepoVersion finalBase = base;
            final List<LayerRow> finalLayers = layers;
            final String finalError = error;
            Activity activity = host.activity();
            activity.runOnUiThread(() -> {
                if (isFinishingSafe() || !detailName.equals(finalMod != null
                        ? finalMod.name : detailName)) {
                    return;
                }
                detailMod = finalMod;
                detailBase = finalBase;
                detailLayers.clear();
                detailLayers.addAll(finalLayers);
                detailGroup = findGroup(detailName);
                stampLayerStates();
                detailLoading = false;
                if (finalError != null && finalBase == null && finalLayers.isEmpty()) {
                    repoError = finalError;
                }
                rebuild();
            });
        }, "gx-repos-detail").start();
    }

    private LayerRow resolveLayer(String url, int kind) {
        LayerRow row = new LayerRow();
        row.manifestUrl = url;
        row.kind = kind;
        row.installedVersion = "";
        try {
            row.resolved = cachedManifest(url);
            row.kind = row.resolved.kind;
        } catch (Exception e) {
            row.error = (e.getMessage() != null) ? e.getMessage() : String.valueOf(e);
        }
        return row;
    }

    /** Fills installed/enabled state for the detail's layer rows. */
    private void stampLayerStates() {
        if (detailName == null || gameFolder == null) {
            return;
        }
        List<String> enabled =
            ModInstaller.readEnabledLayers(gameFolder, detailName);
        for (LayerRow row : detailLayers) {
            String layerDir = layerDirName(row);
            ModInstaller.ComponentMeta meta = ModInstaller.readMeta(
                ModInstaller.layersRoot(gameFolder, detailName), layerDir);
            row.installedVersion = meta != null ? meta.version : "";
            row.enabled = enabled.contains(layerDir);
        }
    }

    /** Installed dir name for a layer: its resolved name, else URL hash. */
    private String layerDirName(LayerRow row) {
        if (row.resolved != null && row.resolved.name != null
                && !row.resolved.name.isEmpty()) {
            return row.resolved.name;
        }
        return "layer-" + Math.abs(row.manifestUrl.hashCode());
    }

    private ModInstaller.ModGroup findGroup(String modName) {
        if (gameFolder == null) {
            return null;
        }
        for (ModInstaller.ModGroup g
                : ModInstaller.listGroups(gameFolder, selectionPath())) {
            if (g.modName.equals(modName)) {
                return g;
            }
        }
        return null;
    }

    private void buildDetail() {
        Activity activity = host.activity();
        LinearLayout card = UiKit.card(listHost);
        UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gen_chevron,
            activity.getString(R.string.mods_back_to_repo), () -> {
                screen = detailReturn;
                rebuild();
            });

        if (detailLoading || detailMod == null) {
            UiKit.sectionHeader(card, R.drawable.ic_gen_chip,
                detailName != null ? detailName : "", false);
            UiKit.supporting(card, activity.getString(R.string.mods_loading));
            return;
        }

        // Hero art + title + version.
        if (detailBase != null && detailBase.imageUrl != null
                && !detailBase.imageUrl.isEmpty()) {
            LinearLayout hero = UiKit.card(listHost);
            final ImageView image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setContentDescription(detailMod.name);
            hero.addView(image, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(activity, 170f)));
            ThumbCache.load(detailBase.imageUrl, bitmap -> activity.runOnUiThread(() -> {
                if (bitmap != null && !isFinishingSafe()) {
                    image.setImageBitmap(bitmap);
                }
            }));
        }

        LinearLayout body = UiKit.card(listHost);
        UiKit.sectionHeader(body, R.drawable.ic_gen_chip, detailMod.name, false);
        if (detailBase != null) {
            String installed = installedVersionByMod.get(detailName);
            if (installed == null && detailGroup != null) {
                installed = installedBaseVersion(detailGroup);
            }
            StringBuilder meta = new StringBuilder(detailBase.version);
            if (installed != null && !installed.isEmpty()) {
                meta.append(" \u00b7 ").append(activity.getString(
                    R.string.mods_version_installed, installed));
            }
            UiKit.supporting(body, meta.toString());
            boolean baseUpdate = detailGroup != null
                && Boolean.TRUE.equals(updateByMod.get(detailName));
            if (detailGroup == null) {
                UiKit.button(body, UiKit.BTN_PRIMARY, R.drawable.ic_gen_download,
                    activity.getString(R.string.mods_detail_install),
                    () -> startComponentInstall(detailMod.name, detailBase, false));
            } else if (baseUpdate) {
                UiKit.button(body, UiKit.BTN_PRIMARY, R.drawable.ic_gen_download,
                    activity.getString(R.string.mods_action_update),
                    () -> startComponentInstall(detailMod.name, detailBase, false));
                UiKit.button(body, UiKit.BTN_TONAL, R.drawable.ic_gen_play,
                    activity.getString(R.string.mods_action_play_short),
                    () -> selectAndLaunch(detailGroup));
            } else {
                UiKit.button(body, UiKit.BTN_TONAL, R.drawable.ic_gen_play,
                    activity.getString(R.string.mods_action_play_short),
                    () -> selectAndLaunch(detailGroup));
            }
        } else {
            UiKit.supporting(body, activity.getString(R.string.mods_not_in_repo));
        }

        // Patches & addons as layers over the base.
        LinearLayout layers = UiKit.card(listHost);
        int enabledCount = gameFolder != null
            ? ModInstaller.readEnabledLayers(gameFolder, detailName).size() : 0;
        UiKit.sectionHeader(layers, R.drawable.ic_gen_chip,
            activity.getString(R.string.mods_layers_title, enabledCount,
                detailLayers.size()), false);
        UiKit.supporting(layers, activity.getString(R.string.mods_layers_hint));
        if (detailLayers.isEmpty()) {
            UiKit.supporting(layers, activity.getString(R.string.mods_no_layers));
        }
        for (LayerRow row : detailLayers) {
            buildLayerRow(layers, row);
        }
    }

    private void buildLayerRow(LinearLayout parent, LayerRow row) {
        Activity activity = host.activity();
        String title = row.resolved != null ? row.resolved.name : row.manifestUrl;
        StringBuilder supporting = new StringBuilder();
        String kindLabel = activity.getString(row.kind == GenLauncherReposClient.KIND_PATCH
            ? R.string.mods_kind_patch : R.string.mods_kind_addon);
        if (row.resolved != null) {
            supporting.append(row.resolved.version).append(" \u00b7 ").append(kindLabel);
        } else {
            supporting.append(kindLabel);
        }
        if (!row.installedVersion.isEmpty()) {
            supporting.append(" \u00b7 ").append(activity.getString(
                R.string.mods_version_installed, row.installedVersion));
        }
        String key = detailName + " " + layerDirName(row);
        if (Boolean.TRUE.equals(layerUpdateByKey.get(key))) {
            supporting.append(" \u00b7 ")
                .append(activity.getString(R.string.mods_update_badge));
        }
        if (row.error != null && row.resolved == null) {
            supporting.append(" \u00b7 ").append(row.error);
        }
        UiKit.Row uiRow = UiKit.listRow(parent, R.drawable.ic_gen_download,
            title, supporting.toString(), null);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(actions, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        if (row.installedVersion.isEmpty()) {
            if (row.resolved != null && row.resolved.isDownloadable()) {
                addCardAction(actions, false, R.drawable.ic_gen_download,
                    activity.getString(R.string.mods_detail_install),
                    () -> startComponentInstall(detailName, row.resolved, true));
            }
        } else {
            // Two rows of two at most (see the installed card): three
            // squeezed buttons ellipsize Arabic labels on-device.
            LinearLayout rowB = new LinearLayout(activity);
            rowB.setOrientation(LinearLayout.HORIZONTAL);
            parent.addView(rowB, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            if (row.resolved != null && GenLauncherReposClient.isUpdate(
                    row.resolved.version, row.installedVersion)) {
                addCardAction(actions, true, R.drawable.ic_gen_download,
                    activity.getString(R.string.mods_action_update),
                    () -> startComponentInstall(detailName, row.resolved, true));
            }
            boolean isEnabled = row.enabled;
            addCardAction(actions, false, isEnabled ? R.drawable.ic_gen_check
                    : R.drawable.ic_gen_chip,
                activity.getString(isEnabled ? R.string.mods_layer_enabled
                                             : R.string.mods_layer_enable),
                () -> toggleLayer(row));
            addCardAction(rowB, false, R.drawable.ic_gen_trash,
                activity.getString(R.string.mods_action_delete),
                () -> confirmDeleteLayer(row));
        }
        // Keep the row's chevron from implying navigation: rows act through
        // the buttons beneath them.
        uiRow.root.setClickable(false);
        uiRow.root.setFocusable(false);
    }

    private void toggleLayer(LayerRow row) {
        if (gameFolder == null || detailName == null) {
            return;
        }
        List<String> enabled = ModInstaller.readEnabledLayers(gameFolder, detailName);
        String dir = layerDirName(row);
        if (!enabled.remove(dir)) {
            enabled.add(dir);
        }
        ModInstaller.writeEnabledLayers(gameFolder, detailName, enabled);
        if (enabled.isEmpty()) {
            ModInstaller.dropActiveDir(gameFolder, detailName);
        }
        // Re-resolve the live launch if it points into this family so the
        // toggle takes effect without reselecting the mod.
        refreshFamilyActivation(detailName);
        stampLayerStates();
        detailGroup = findGroup(detailName);
        rebuild();
    }

    private void confirmDeleteLayer(LayerRow row) {
        Activity activity = host.activity();
        String dir = layerDirName(row);
        new android.app.AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.mods_delete_title,
                    row.resolved != null ? row.resolved.name : dir))
                .setMessage(activity.getString(R.string.mods_delete_body, dir))
                .setPositiveButton(R.string.mods_delete_confirm, (d, w) -> {
                    ModInstaller.deleteRecursively(
                        new File(ModInstaller.layersRoot(gameFolder, detailName), dir));
                    new File(ModInstaller.layersRoot(gameFolder, detailName),
                        dir + ModInstaller.META_SUFFIX).delete();
                    List<String> enabled =
                        ModInstaller.readEnabledLayers(gameFolder, detailName);
                    if (enabled.remove(dir)) {
                        ModInstaller.writeEnabledLayers(gameFolder, detailName, enabled);
                    }
                    ModInstaller.dropActiveDir(gameFolder, detailName);
                    refreshFamilyActivation(detailName);
                    stampLayerStates();
                    detailGroup = findGroup(detailName);
                    rebuild();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Re-merges +active when the live launch points into this family. */
    private void refreshFamilyActivation(String modName) {
        if (launchPath == null || gameFolder == null) {
            return;
        }
        String familyRoot = new File(gameFolder,
            "Mods" + File.separator + modName).getAbsolutePath();
        if (!launchPath.startsWith(familyRoot)) {
            return;
        }
        String base = readLaunchBase(host.activity());
        if (base == null || !new File(base).isDirectory()) {
            return;
        }
        try {
            launchPath = ModInstaller.resolveLaunchDir(gameFolder, modName, base);
            try (FileWriter w = new FileWriter(
                    new File(host.activity().getFilesDir(), "mod_launch.cfg"), false)) {
                w.write(launchPath);
                w.write("\n");
                w.write(base);
                w.write("\n");
            }
        } catch (Exception ignored) {
            // Next activation rebuilds; the current pointer stays valid.
        }
    }

    /** Installed base version from sidecars (MOD kind), or "". */
    private String installedBaseVersion(ModInstaller.ModGroup group) {
        for (ModInstaller.InstalledMod v : group.versions) {
            ModInstaller.ComponentMeta meta = ModInstaller.readMeta(
                new File(v.dirPath).getParentFile(), v.displayName);
            if (meta != null && meta.kind == GenLauncherReposClient.KIND_MOD
                    && !meta.version.isEmpty()) {
                return meta.version;
            }
        }
        return "";
    }

    // ------------------------------------------------------ install pipeline

    /**
     * Installs one repository component: base versions beside any existing
     * ones (GenLauncher's model), layers into +layers/. S3 folder mods
     * stream file-by-file with no staging copy; archives go through the
     * shared download+extract pipeline.
     */
    private void startComponentInstall(String modName,
                                       GenLauncherReposClient.RepoVersion version,
                                       boolean isLayer) {
        Activity activity = host.activity();
        if (gameFolder == null) {
            toast(activity.getString(R.string.mods_no_game_folder));
            return;
        }
        installingComponent = version;
        installingModName = modName;
        installingIsLayer = isLayer;
        installBase = fileBaseName(version.version);
        long total = 0;
        for (GenLauncherReposClient.S3Object o : version.s3Objects) {
            total += o.size;
        }
        final long totalBytes = total;
        if (ModInstaller.freeBytes(gameFolder) < Math.max(512L * 1024 * 1024, totalBytes)) {
            new android.app.AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.mods_err_title))
                    .setMessage(activity.getString(R.string.mods_err_low_storage,
                        humanBytes(ModInstaller.freeBytes(gameFolder))))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        showInstallCard(modName + " " + version.version);
        final ModInstaller.Listener listener = makeInstallListener();
        new Thread(() -> {
            String error = null;
            try {
                if (!version.s3Objects.isEmpty()) {
                    installS3Component(modName, version, isLayer, totalBytes, listener);
                } else {
                    installArchiveComponent(modName, version, isLayer, listener);
                }
            } catch (Exception e) {
                error = (e.getMessage() != null) ? e.getMessage() : String.valueOf(e);
            }
            final String finalError = error;
            activity.runOnUiThread(() -> {
                if (statusBar != null) {
                    statusBar.setVisibility(View.GONE);
                }
                if (isFinishingSafe()) {
                    return;
                }
                if (finalError != null) {
                    toast(activity.getString(R.string.mods_err_install, finalError));
                    rebuild();
                    return;
                }
                updateByMod.remove(modName); // re-check this family next time
                toast(activity.getString(R.string.mods_install_done,
                    modName + " " + version.version));
                if (screen == SCREEN_DETAIL) {
                    openRepoDetail(modName, detailReturn);
                } else {
                    screen = SCREEN_INSTALLED;
                    rebuild();
                }
            });
        }, "gx-repos-install").start();
    }

    private void installS3Component(String modName,
                                    GenLauncherReposClient.RepoVersion version,
                                    boolean isLayer, long totalBytes,
                                    ModInstaller.Listener listener) throws IOException {
        File target;
        if (isLayer) {
            target = ModInstaller.prepareLayerDir(gameFolder, modName,
                safeLayerName(version));
        } else {
            target = ModInstaller.prepareVersionDir(gameFolder, modName, version.version);
        }
        long[] done = {0};
        int count = 0;
        for (GenLauncherReposClient.S3Object o : version.s3Objects) {
            String fileName = o.key.substring(o.key.lastIndexOf('/') + 1);
            if (fileName.isEmpty()) {
                continue;
            }
            listener.onPhase(fileName);
            listener.onProgress(done[0], totalBytes);
            ModInstaller.streamToFolder(
                GenLauncherReposClient.s3ObjectUrl(version, o),
                new File(target, fileName),
                o.size,
                (delta) -> listener.onProgress(done[0] + delta, totalBytes));
            done[0] += o.size;
            count++;
        }
        if (count == 0) {
            throw new IOException("no files in this mod's storage");
        }
        ModInstaller.writeMeta(target, version.manifestUrl, version.version, version.kind);
        if (!isLayer) {
            ModInstaller.writeLogo(gameFolder, modName, version.imageUrl);
        }
    }

    private void installArchiveComponent(String modName,
                                         GenLauncherReposClient.RepoVersion version,
                                         boolean isLayer,
                                         ModInstaller.Listener listener) throws Exception {
        if (isLayer) {
            File target = ModInstaller.prepareLayerDir(gameFolder, modName,
                safeLayerName(version));
            ModInstaller.downloadAndExtractTo(version.simpleDownloadLink, target,
                fileBaseName(version.name), listener);
            ModInstaller.writeMeta(target, version.manifestUrl, version.version,
                version.kind);
        } else {
            File installed = ModInstaller.downloadAndInstall(version.simpleDownloadLink,
                gameFolder, modName, fileBaseName(version.version),
                version.manifestUrl, version.version, version.kind, listener);
            ModInstaller.writeLogo(gameFolder, modName, version.imageUrl);
            if (installed != null) {
                ModInstaller.flattenSingleVersion(installed.getParentFile());
            }
        }
    }

    private static String safeLayerName(GenLauncherReposClient.RepoVersion version) {
        String name = version.name != null ? version.name : "layer";
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "layer" : cleaned;
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
     * Extracted-folder import: the user browses to the folder with our own
     * picker (real filesystem paths, no SAF tree dance) and the installer
     * copies the .big set (+ companions) into Mods/.
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

    // ------------------------------------------------------ install progress

    private void clearPageReferences() {
        installPhaseView = null;
        installBar = null;
        installBytesView = null;
        resultsHost = null;
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
        LinearLayout host = resultsHost != null ? resultsHost : listHost;
        host.removeAllViews();
        LinearLayout card = UiKit.card(host);
        UiKit.sectionHeader(card, R.drawable.ic_gen_info,
            activity.getString(R.string.mods_err_title), false);
        UiKit.supporting(card, message);
        if (offerRetry) {
            UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gen_refresh,
                activity.getString(R.string.mods_retry), () -> {
                    repoMods = null;
                    repoError = null;
                    rebuild();
                });
        }
    }

    /** File base name for a version title, used as folder name + tmp name. */
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
        if (screen == SCREEN_INSTALLED) {
            updateByMod.clear(); // a refresh re-checks updates
            layerUpdateByKey.clear();
            modsWithUpdates.clear();
            installedVersionByMod.clear();
            availableVersionByMod.clear();
        } else if (screen == SCREEN_REPO) {
            repoMods = null;
            repoError = null;
            manifestCache.clear();
        }
        rebuild();
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
     * Host back handling: detail returns to where it was opened from;
     * anything else means the host should do its own Back.
     */
    boolean onBackPressed() {
        if (screen == SCREEN_DETAIL) {
            screen = detailReturn;
            rebuild();
            return true;
        }
        if (screen == SCREEN_REPO && !repoQuery.isEmpty()) {
            repoQuery = "";
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
