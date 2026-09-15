/*
** GeneralsX Android launcher shell
** Copyright 2026 Alaa91H
**
** This program is free software: you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation, either version 3 of the License, or
** (at your option) any later version.
*/

package com.Generals.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * GeneralsX @bugfix Android port 15/09/2026 The manifest LAUNCHER used to be
 * GameActivity (SDLActivity). On a device with no game folder configured,
 * GameActivity.onCreate() had to leave before SDL's super.onCreate() ran --
 * but a plain finish() throws SuperNotCalledException, and every trick to
 * satisfy the mCalled contract from inside a SDLActivity subclass is either
 * unsafe (entering SDLActivity.onCreate dlopens libmain.so exactly once and
 * guards re-entry with System.exit(0)) or broken (java.lang.reflect
 * Method.invoke dispatches VIRTUALLY, so a "call Activity.onCreate via
 * reflection" bounce re-enters GameActivity.onCreate -- infinite recursion,
 * observed on-device as the same SuperNotCalledException). This stub is the
 * supported shape instead: a tiny plain Activity that owns the routing
 * decision BEFORE any SDL class exists, so each branch can call
 * super.onCreate() lawfully and GameActivity is only ever started when the
 * game folder is valid.
 */
public class SplashActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String gamePath = SetupActivity.getSavedGamePath(this);
        boolean haveCustomPath = gamePath != null
            && SetupActivity.isValidGameFolder(new java.io.File(gamePath));

        Class<?> next = haveCustomPath ? GameActivity.class : SetupActivity.class;
        startActivity(new Intent(this, next));
        finish();
    }
}
