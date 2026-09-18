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
// The standalone, full-screen entry to the mod manager: a thin host around
// ModsPanel (the reusable page also embedded in Setup's Home tab). All mod
// logic lives in the panel; this activity supplies only the operations a
// panel cannot do itself — start the game, hand over to Setup's folder
// picker when no game folder is configured, and forward SAF results from
// the storage-import file picker.
//
// GeneralsX @refactor 18/09/2026 ModDB backend removed: the repository needs
// no host activity (plain HTTPS, no challenge WebView), so the host wiring
// is gone and this file is just panel + navigation.

package com.Generals.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class ModManagerActivity extends Activity implements ModsPanel.Host {

    private ModsPanel panel;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.mods_window_title);
        panel = new ModsPanel(this, this, true);
        setContentView(panel);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The game folder may have been changed while Setup was foreground;
        // the panel re-reads it so the Installed list can't show a deleted
        // mod as playable.
        panel.notifyGameFolderMaybeChanged();
    }

    @Override
    public Activity activity() {
        return this;
    }

    @Override
    public void launchGame() {
        android.util.Log.i("ModDb", "ModManager finish: launchGame");
        startActivity(new Intent(this, GameActivity.class));
        finish();
    }

    @Override
    public void requestGameFolder() {
        android.util.Log.i("ModDb", "ModManager finish: requestGameFolder");
        startActivity(new Intent(this, SetupActivity.class));
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        panel.handleActivityResult(requestCode, resultCode, data);
    }
}
