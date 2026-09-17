/*
** GeneralsX @feature Android port launcher-icons 17/09/2026 Restore the second
** home-screen icon (game settings). The 15/09/2026 change collapsed the two
** LAUNCHER entries into one because SetupActivity as a LAUNCHER entry crashed
** on unconfigured devices: a LAUNCHER intent lands directly in
** SetupActivity.onCreate(), whose "no game folder" path tried to leave before
** the activity was lawfully entered. That failure mode is gone -- the crash
** was fixed by routing, not by hiding the icon -- but re-exposing
** SetupActivity itself as a LAUNCHER would regress the fix (adb can no longer
** start it either: it is intentionally not exported).
**
** So the icon points at this trampoline instead: a plain Activity (no SDL
** machinery) that forwards to SetupActivity and finishes. It is safe on any
** device state: SetupActivity itself redirects to its game-folder picker when
** nothing is configured, and SplashActivity still handles the game icon.
*/
package com.Generals.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class SetupShortcutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, SetupActivity.class));
        finish();
    }
}
