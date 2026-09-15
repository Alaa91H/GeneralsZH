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

// GeneralsX @feature Android port app-update 15/09/2026
//
// In-app self-update for a sideloaded APK: query the project's GitHub
// releases (api.github.com/repos/Alaa91H/GeneralsZH/releases/latest), parse
// the tag_name and body (changelog), compare the release's versionCode with
// the installed one, and offer the newest .apk asset. No Play Store, no
// third-party update library -- one HTTP GET and one download, matching how
// this app is already distributed.
//
// Why versionCode, not the tag string: the tag is human text ("v1.3.0"),
// but Android's update gate is versionCode (1.2.2 -> 10202 here, a strictly
// increasing scheme documented in build.gradle). The release's versionCode
// is recovered from the tag's a.b.c via the same formula build.gradle
// documents (major*10000 + minor*100 + patch); the APK asset is offered
// directly and installs over the existing app because every build signs
// with the same fixed in-repo key (see build.gradle's signingConfigs note).

package com.generalsx.zerohour;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.ProgressDialog;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AppUpdateChecker {

    private static final String RELEASES_API =
        "https://api.github.com/repos/Alaa91H/GeneralsZH/releases/latest";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    /** What a release check concluded; drives the caller's dialog. */
    static final class Result {
        final String tagName;
        final long releaseVersionCode;
        final String changelog;   // release body, trimmed; may be empty
        final String apkUrl;      // newest .apk asset's browser_download_url

        Result(String tagName, long releaseVersionCode, String changelog, String apkUrl) {
            this.tagName = tagName;
            this.releaseVersionCode = releaseVersionCode;
            this.changelog = changelog;
            this.apkUrl = apkUrl;
        }
    }

    /**
     * Fetches the latest release and returns it, or throws with a readable
     * message. Network only; call off the UI thread.
     */
    static Result fetchLatest() throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(RELEASES_API).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "GeneralsX-Android");
            int status = conn.getResponseCode();
            if (status == 404) {
                throw new Exception("no releases published yet");
            }
            if (status != 200) {
                throw new Exception("HTTP " + status);
            }
            StringBuilder body = new StringBuilder(16 * 1024);
            try (BufferedReader r = new BufferedReader(
                     new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[8 * 1024];
                int n;
                while ((n = r.read(buf)) > 0) {
                    body.append(buf, 0, n);
                }
            }
            JSONObject json = new JSONObject(body.toString());
            String tag = json.optString("tag_name", "");
            String releaseBody = json.optString("body", "");
            String apkUrl = "";
            org.json.JSONArray assets = json.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String name = asset.optString("name", "");
                    if (name.toLowerCase(java.util.Locale.US).endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url", "");
                        break; // assets[0] is the newest upload
                    }
                }
            }
            return new Result(tag, versionCodeFromTag(tag), releaseBody, apkUrl);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Same scheme build.gradle documents: major*10000 + minor*100 + patch. */
    static long versionCodeFromTag(String tag) {
        if (tag == null) {
            return -1;
        }
        Matcher m = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(tag);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1)) * 10000L
                     + Long.parseLong(m.group(2)) * 100L
                     + Long.parseLong(m.group(3));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    static long installedVersionCode(Activity activity) {
        try {
            return activity.getPackageManager()
                .getPackageInfo(activity.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Full flow, run from the Help tab's button: check, then either report
     * up-to-date or show the changelog dialog and offer the APK download
     * through the system DownloadManager (visible progress, lands in
     * Downloads/, tap to install).
     */
    static void checkAndOffer(final Activity activity) {
        final ProgressDialog progress = new ProgressDialog(activity);
        progress.setMessage(activity.getString(R.string.mods_loading));
        progress.setCanceledOnTouchOutside(false);
        progress.show();
        new Thread(() -> {
            Result result = null;
            String error = null;
            try {
                result = fetchLatest();
            } catch (Exception e) {
                error = (e.getMessage() != null) ? e.getMessage() : String.valueOf(e);
            }
            final Result finalResult = result;
            final String finalError = error;
            activity.runOnUiThread(() -> {
                progress.dismiss();
                if (finalError != null) {
                    Toast.makeText(activity,
                        activity.getString(R.string.update_check_failed, finalError),
                        Toast.LENGTH_LONG).show();
                    return;
                }
                long installed = installedVersionCode(activity);
                if (finalResult.releaseVersionCode <= installed) {
                    String label = (finalResult.tagName != null && !finalResult.tagName.isEmpty())
                        ? finalResult.tagName : String.valueOf(installed);
                    Toast.makeText(activity,
                        activity.getString(R.string.update_up_to_date, label),
                        Toast.LENGTH_LONG).show();
                    return;
                }
                new android.app.AlertDialog.Builder(activity)
                        .setTitle(R.string.update_available_title)
                        .setMessage(activity.getString(R.string.update_available_body,
                            finalResult.tagName, String.valueOf(installed),
                            finalResult.changelog == null ? "" : finalResult.changelog))
                        .setPositiveButton(R.string.update_button_download, (d, w) -> {
                            if (finalResult.apkUrl == null || finalResult.apkUrl.isEmpty()) {
                                Toast.makeText(activity, R.string.update_download_failed,
                                    Toast.LENGTH_LONG).show();
                                return;
                            }
                            enqueueApkDownload(activity, finalResult.apkUrl, finalResult.tagName);
                        })
                        .setNegativeButton(R.string.update_button_later, null)
                        .show();
            });
        }, "gx-update-check").start();
    }

    /**
     * Hands the APK to the system DownloadManager: it streams with its own
     * progress notification, survives the activity, and the completed file
     * opens with the package-installer intent (Android asks the user to
     * allow installs from this app on first use -- the standard sideload
     * flow, no special permission beyond the internet one).
     */
    private static void enqueueApkDownload(Activity activity, String apkUrl, String tag) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
            request.setTitle("GeneralsX " + (tag != null ? tag : "update"));
            request.setDescription(activity.getString(R.string.update_downloading));
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                "GeneralsX-update.apk");
            request.addRequestHeader("User-Agent", "GeneralsX-Android");
            DownloadManager dm =
                (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                throw new IllegalStateException("no DownloadManager");
            }
            dm.enqueue(request);
            Toast.makeText(activity, R.string.update_download_done, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(activity,
                activity.getString(R.string.update_download_failed, String.valueOf(e)),
                Toast.LENGTH_LONG).show();
        }
    }

    private AppUpdateChecker() {
    }
}
