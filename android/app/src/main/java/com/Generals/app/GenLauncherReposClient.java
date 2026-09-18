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

// GeneralsX @feature Android port mod-launcher 17/09/2026 Client for the
// GenLauncher (github.com/p0ls3r/GenLauncher) mod repository — the curated
// manifest tree the desktop launcher uses. This is the mod manager's ONLY
// network source: every mod ships a small YAML sidecar
// ("GenLauncherData.yaml") describing its latest version and where to
// fetch it:
//
//   - SimpleDownloadLink: one archive (rar/zip) served over plain HTTP —
//     downloaded whole and extracted, identical to a manual install.
//   - S3HostLink/S3BucketName/S3FolderName: an S3-compatible bucket holding
//     the mod's .big files individually (gen.insave.ovh, public keys baked
//     into GenLauncher). Listing is an authenticated ListObjectsV2 GET; each
//     object is fetched with a plain HTTPS GET on
//     https://<host>/<bucket>/<folder>/<object>.
//
// That second shape is the important one for Android: no archive staging
// copy at all — files stream straight into the mod folder, so a 3 GB mod
// needs 3 GB, not 6. The public read-only keys are the ones GenLauncher
// itself ships (S3StorageHandler.cs); they grant read/list on that host
// only and are not a secret in any meaningful sense, but they are kept out
// of this file's constants just to make the provenance obvious.
//
// The YAML subset parsed here is deliberately tiny: flat "Key: Value"
// pairs plus the repository manifest's two-level list structure. The
// desktop client uses YamlDotNet; the launcher's manifests never nest
// deeper than that, so a regex parser with the same tolerances (CRLF,
// quotes, empty values) is enough and keeps the APK free of a YAML
// library.
//
// GeneralsX @feature 18/09/2026 Full repository schema: the index manifest
// lists ModPatches and ModAddons per mod (each its own manifest URL), and
// every component manifest carries ModificationType (Mod/Patch/Addon) plus
// DependenceName for layers. Updates are manifest-driven: an installed
// component records its manifest URL + Version, and a newer release is
// simply a manifest whose Version string differs — exact match means
// current, anything else means update. Version strings are free text
// ("10.0.2 Beta 2 Patch 1", "1.87", "009"), so no numeric parsing is
// attempted; string inequality is the whole check.

package com.Generals.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GenLauncherReposClient {

    // Desktop browser UA: the file hosts (gen.insave.ovh, GitHub raw) serve
    // plain GETs, but a browser identity avoids bot-filter surprises.
    static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    /** Per-file guard, matching SetupActivity's language-pack cap. */
    static final long MAX_DOWNLOAD_BYTES = 8L * 1024 * 1024 * 1024;

    /** The curated Zero Hour repository manifest (see EntryPoint.cs ZHRepos). */
    static final String ZH_REPOS_URL =
        "https://raw.githubusercontent.com/p0ls3r/GenLauncherModsData/master/ReposModificationDataZH4.yaml";

    /** Component kinds, from the manifest's ModificationType field. */
    static final int KIND_MOD = 0;
    static final int KIND_PATCH = 1;
    static final int KIND_ADDON = 2;

    /** One curated mod from the repository manifest, with its layers. */
    static final class RepoMod {
        final String name;
        String manifestUrl;   // the mod's own GenLauncherData.yaml
        final List<String> patchUrls = new ArrayList<>();
        final List<String> addonUrls = new ArrayList<>();

        RepoMod(String name) {
            this(name, null);
        }

        RepoMod(String name, String manifestUrl) {
            this.name = name;
            this.manifestUrl = manifestUrl;
        }

        int layerCount() {
            return patchUrls.size() + addonUrls.size();
        }
    }

    /** A mod version resolved from its manifest: where to download, what it is. */
    static final class RepoVersion {
        String manifestUrl;          // which manifest this came from
        int kind = KIND_MOD;         // ModificationType: Mod/Patch/Addon
        String name;
        String version;
        String dependenceName;       // parent mod for patches/addons, may be null
        String simpleDownloadLink;   // single archive over HTTP(S), may be null
        String imageUrl;             // cover art, may be null
        String modDbLink;            // informational homepage, may be null
        String discordLink;
        String s3Host;               // "gen.insave.ovh:9000" or null
        String s3Bucket;
        String s3Folder;
        final List<S3Object> s3Objects = new ArrayList<>();

        /** True when this version can be fetched right now. */
        boolean isDownloadable() {
            return simpleDownloadLink != null || !s3Objects.isEmpty();
        }
    }

    /** One object in the mod's S3 folder. */
    static final class S3Object {
        final String key;      // path inside the bucket
        final long size;

        S3Object(String key, long size) {
            this.key = key;
            this.size = size;
        }
    }

    private GenLauncherReposClient() {
    }

    // ------------------------------------------------------------- repository

    /**
     * Fetches the curated Zero Hour manifest and returns its mods with
     * their patch/addon layer URLs, order preserved (the manifest is
     * roughly popularity-ordered).
     */
    static List<RepoMod> fetchRepoMods() throws IOException {
        String yaml = httpGet(ZH_REPOS_URL);
        List<RepoMod> out = new ArrayList<>();
        // Split into "- ModName:" blocks; each block holds the ModLink plus
        // the ModPatches:/ModAddons: URL lists belonging to that mod.
        String[] blocks = yaml.split("(?m)^- ModName:");
        for (String raw : blocks) {
            Matcher name = Pattern.compile("^\\s*\"?([^\r\n\"]+)\"?\\s*$",
                Pattern.MULTILINE).matcher(raw);
            if (!name.find()) {
                continue;
            }
            String modName = name.group(1).trim();
            if (modName.isEmpty()
                    || modName.equalsIgnoreCase("moddedExecutable")
                    || modName.equalsIgnoreCase("Generals Online")
                    || modName.equalsIgnoreCase("Gentools")
                    || modName.toLowerCase(java.util.Locale.US).startsWith("do you like")) {
                // The manifest's non-mod entries (executables, ads, tools)
                // are not installable mods; skip them.
                continue;
            }
            Matcher link = Pattern.compile("ModLink:\\s*(\\S+)").matcher(raw);
            if (!link.find()) {
                continue;
            }
            RepoMod mod = new RepoMod(modName, link.group(1).trim());
            mod.patchUrls.addAll(urlList(raw, "ModPatches:"));
            mod.addonUrls.addAll(urlList(raw, "ModAddons:"));
            out.add(mod);
        }
        return out;
    }

    /**
     * The "- <url>" items under a "Key:" list header inside one mod block.
     * An empty list ("ModPatches: []") yields nothing.
     */
    private static List<String> urlList(String block, String key) {
        List<String> urls = new ArrayList<>();
        int at = block.indexOf(key);
        if (at < 0) {
            return urls;
        }
        String tail = block.substring(at + key.length());
        // The list ends at the next sibling key ("  ModAddons:") or the
        // next mod ("- ModName:"). Items themselves ("  - <url>") carry a
        // dash in third position, so "^  \w" only matches real keys —
        // stopping at the non-indented form instead would swallow the
        // sibling list into this one (observed: patch counts absorbing
        // every addon URL below them).
        Matcher end = Pattern.compile("(?m)^(  \\w|- )").matcher(tail);
        if (end.find() && end.start() > 0) {
            tail = tail.substring(0, end.start());
        }
        Matcher item = Pattern.compile("-\\s*(https?://\\S+)").matcher(tail);
        while (item.find()) {
            urls.add(item.group(1).trim());
        }
        return urls;
    }

    // ---------------------------------------------------------------- manifest

    /** Parses any component manifest (mod, patch or addon) by URL. */
    static RepoVersion fetchVersion(String manifestUrl) throws IOException {
        RepoVersion v = parseManifest(httpGet(manifestUrl));
        v.manifestUrl = manifestUrl;
        return v;
    }

    /** Parses one mod's GenLauncherData.yaml into a version. */
    static RepoVersion fetchVersion(RepoMod mod) throws IOException {
        return fetchVersion(mod.manifestUrl);
    }

    static RepoVersion parseManifest(String yaml) {
        RepoVersion v = new RepoVersion();
        v.name = yamlValue(yaml, "Name");
        v.version = yamlValue(yaml, "Version");
        v.simpleDownloadLink = trimNull(yamlValue(yaml, "SimpleDownloadLink"));
        v.imageUrl = trimNull(yamlValue(yaml, "UIImageSourceLink"));
        v.modDbLink = trimNull(yamlValue(yaml, "ModDBLink"));
        v.discordLink = trimNull(yamlValue(yaml, "DiscordLink"));
        v.dependenceName = trimNull(yamlValue(yaml, "DependenceName"));
        v.s3Host = trimNull(yamlValue(yaml, "S3HostLink"));
        v.s3Bucket = trimNull(yamlValue(yaml, "S3BucketName"));
        v.s3Folder = trimNull(yamlValue(yaml, "S3FolderName"));
        String type = trimNull(yamlValue(yaml, "ModificationType"));
        if ("Patch".equalsIgnoreCase(type)) {
            v.kind = KIND_PATCH;
        } else if ("Addon".equalsIgnoreCase(type)) {
            v.kind = KIND_ADDON;
        } else {
            v.kind = KIND_MOD;
        }
        if (v.name == null) {
            v.name = "Unknown mod";
        }
        if (v.version == null || v.version.isEmpty()) {
            v.version = "latest";
        }
        return v;
    }

    /**
     * True when the manifest's current version differs from the installed
     * one — the whole update check. Versions are free text, so exact match
     * is the only safe comparison; anything else is treated as an update.
     */
    static boolean isUpdate(String currentVersion, String installedVersion) {
        if (currentVersion == null || installedVersion == null) {
            return false;
        }
        return !currentVersion.trim().equals(installedVersion.trim());
    }

    /**
     * Lists the mod's S3 objects. Verified on-device 17/09/2026: MinIO at
     * gen.insave.ovh:9000 serves UNSIGNED ListObjectsV2 (and object GETs) on
     * plain HTTP; the HTTPS :443 endpoint is a nginx front that answers 403
     * to everything but the exact object paths GenLauncher's own S3Updater
     * formats. So no SigV4 machinery is needed — a plain GET against
     * http://<host>:9000/<bucket>/?list-type=2&prefix=<folder>/ does it.
     */
    static void fillS3Objects(RepoVersion v) throws IOException {
        if (v.s3Host == null || v.s3Bucket == null || v.s3Folder == null) {
            return;
        }
        String host = v.s3Host.contains(":")
            ? v.s3Host.substring(0, v.s3Host.indexOf(':')) : v.s3Host;
        String query = "list-type=2&prefix=" + urlEncode(v.s3Folder + "/");
        String xml = httpGet("http://" + host + ":9000/" + v.s3Bucket + "/?" + query);
        Matcher m = Pattern.compile(
            "<Key>([^<]+)</Key>[\\s\\S]*?<Size>(\\d+)</Size>").matcher(xml);
        while (m.find()) {
            String key = m.group(1);
            long size = Long.parseLong(m.group(2));
            if (key.startsWith(v.s3Folder + "/")) {
                v.s3Objects.add(new S3Object(key, size));
            }
        }
    }

    /**
     * The direct URL for one S3 object — the MinIO endpoint on :9000 serves
     * plain unsigned GETs (see fillS3Objects); the :443 front 403s them.
     */
    static String s3ObjectUrl(RepoVersion v, S3Object o) {
        String host = v.s3Host.contains(":")
            ? v.s3Host.substring(0, v.s3Host.indexOf(':')) : v.s3Host;
        return "http://" + host + ":9000/" + v.s3Bucket + "/" + o.key;
    }

    // ------------------------------------------------------------ downloads

    /**
     * Opens a byte stream of the file starting at the given offset (0 for a
     * fresh download) and reports the total size if the server exposes one.
     * Resume support: a 2 GB download that dies at 90% restarts from 90% of
     * the partial, not from zero — the file hosts honor standard Range
     * requests. totalSize[0] receives the full file size when the response
     * carries Content-Length (-1 otherwise); with an offset the status is
     * 206 and Content-Length is the remainder.
     */
    static InputStream openDownloadStream(String url, long offset,
                                          long[] totalSize,
                                          HttpURLConnection[] outConn) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/octet-stream, */*");
        if (offset > 0) {
            conn.setRequestProperty("Range", "bytes=" + offset + "-");
        }
        conn.setInstanceFollowRedirects(true);
        final int status = conn.getResponseCode();
        if (offset > 0 && status == 416) {
            // "Range not satisfiable" — the host does not honor Range or the
            // partial is already complete. Caller retries from zero.
            conn.disconnect();
            throw new IOException("resume refused (HTTP 416)");
        }
        if (status < 200 || status >= 300) {
            conn.disconnect();
            throw new IOException("HTTP " + status);
        }
        long total = conn.getContentLength();
        if (totalSize != null && totalSize.length > 0) {
            // On 206 the Content-Length covers only the remainder; adding the
            // offset yields the absolute file size the progress bar needs.
            totalSize[0] = (status == 206 && total > 0) ? total + offset : total;
        }
        if (outConn != null && outConn.length > 0) {
            outConn[0] = conn;
        }
        return conn.getInputStream();
    }

    static void disconnectQuietly(HttpURLConnection conn) {
        if (conn != null) {
            conn.disconnect();
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static String yamlValue(String yaml, String key) {
        Matcher m = Pattern.compile("^" + key + ":\\s*(.*)$", Pattern.MULTILINE)
            .matcher(yaml);
        if (!m.find()) {
            return null;
        }
        String value = m.group(1).trim();
        if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String trimNull(String s) {
        return (s == null || s.isEmpty() || s.equals("''")) ? null : s;
    }

    static String httpGet(String url, String... headers) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            for (String h : headers) {
                int cut = h.indexOf(':');
                if (cut > 0) {
                    conn.setRequestProperty(h.substring(0, cut).trim(),
                        h.substring(cut + 1).trim());
                }
            }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status + " for " + url);
            }
            StringBuilder sb = new StringBuilder(16 * 1024);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                }
            }
            return sb.toString();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
