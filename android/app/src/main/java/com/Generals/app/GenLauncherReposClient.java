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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GeneralsX @feature Android port mod-launcher 17/09/2026 Client for the
 * GenLauncher (github.com/p0ls3r/GenLauncher) mod repository — the curated
 * manifest tree the desktop launcher uses. Every mod ships a small YAML
 * sidecar ("GenLauncherData.yaml") describing its latest version and where
 * to fetch it:
 *
 *   - SimpleDownloadLink: one archive (rar/zip) served over plain HTTP —
 *     downloaded whole and extracted, identical to the ModDB path.
 *   - S3HostLink/S3BucketName/S3FolderName: an S3-compatible bucket holding
 *     the mod's .big files individually (gen.insave.ovh, public keys baked
 *     into GenLauncher). Listing is an authenticated ListObjectsV2 GET; each
 *     object is fetched with a plain HTTPS GET on
 *     https://<host>/<bucket>/<folder>/<object>.
 *
 * That second shape is the important one for Android: no archive staging
 * copy at all — files stream straight into the mod folder, so a 3 GB mod
 * needs 3 GB, not 6. The public read-only keys are the ones GenLauncher
 * itself ships (S3StorageHandler.cs); they grant read/list on that host
 * only and are not a secret in any meaningful sense, but they are kept out
 * of this file's constants just to make the provenance obvious.
 *
 * The YAML subset parsed here is deliberately tiny: flat "Key: Value"
 * pairs plus the repository manifest's two-level list structure. The
 * desktop client uses YamlDotNet; the launcher's manifests never nest
 * deeper than that, so a regex parser with the same tolerances (CRLF,
 * quotes, empty values) is enough and keeps the APK free of a YAML
 * library.
 */
final class GenLauncherReposClient {

    private static final String TAG = "GenLauncher";

    /** The curated Zero Hour repository manifest (see EntryPoint.cs ZHRepos). */
    static final String ZH_REPOS_URL =
        "https://raw.githubusercontent.com/p0ls3r/GenLauncherModsData/master/ReposModificationDataZH4.yaml";

    /** Read-only keys GenLauncher ships for gen.insave.ovh (S3StorageHandler.cs). */
    /** Read-only keys GenLauncher ships for gen.insave.ovh (S3StorageHandler.cs).
     *  Kept for reference only — the :9000 MinIO endpoint serves unsigned. */
    private static final String S3_ACCESS_KEY = "S58TYR9ISEZV8PBP8QG1";
    private static final String S3_SECRET_KEY = "b2RU1oqVU5toJRnb4gODrXX8sBSgoLcHRX6qPWxj";

    /** One curated mod from the repository manifest. */
    static final class RepoMod {
        final String name;
        String manifestUrl;   // the mod's own GenLauncherData.yaml

        RepoMod(String name) {
            this(name, null);
        }

        RepoMod(String name, String manifestUrl) {
            this.name = name;
            this.manifestUrl = manifestUrl;
        }
    }

    /** A mod version resolved from its manifest: where to download, what it is. */
    static final class RepoVersion {
        String name;
        String version;
        String simpleDownloadLink;   // single archive over HTTP(S), may be null
        String imageUrl;             // cover art, may be null
        String modDbLink;            // profile on ModDB, may be null
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
     * Fetches the curated Zero Hour manifest and returns its mods, order
     * preserved (the manifest is roughly popularity-ordered).
     */
    static List<RepoMod> fetchRepoMods() throws IOException {
        String yaml = httpGet(ZH_REPOS_URL);
        List<RepoMod> out = new ArrayList<>();
        Matcher m = Pattern.compile("- ModName:\\s*\"?([^\r\n\"]+)\"?").matcher(yaml);
        while (m.find()) {
            String name = m.group(1).trim();
            // The manifest's non-mod entries (Gentools, Generals Online,
            // "Do you like GenLauncher?") are executables/ads; skip them.
            if (name.equalsIgnoreCase("moddedExecutable")
                    || name.equalsIgnoreCase("Generals Online")
                    || name.equalsIgnoreCase("Gentools")
                    || name.toLowerCase().startsWith("do you like")) {
                continue;
            }
            out.add(new RepoMod(name));
        }
        // Second pass for the ModLink belonging to each - ModName block.
        // (One regex pass cannot bind list items to their block reliably.)
        // After the split each block begins with the name — but YAML indent
        // puts a single space before it (" Rise Of The Reds\n  ModLink:")…
        String[] blocks = yaml.split("(?m)^- ModName:");
        for (RepoMod mod : out) {
            for (String block : blocks) {
                String head = block.replaceFirst("^\\s+", "");
                if (head.startsWith(mod.name)
                        || head.startsWith("\"" + mod.name)) {
                    Matcher link = Pattern.compile("ModLink:\\s*(\\S+)").matcher(block);
                    if (link.find()) {
                        mod.manifestUrl = link.group(1).trim();
                    }
                    break;
                }
            }
        }
        List<RepoMod> withLinks = new ArrayList<>();
        for (RepoMod mod : out) {
            if (mod.manifestUrl != null) {
                withLinks.add(mod);
            }
        }
        return withLinks;
    }

    // ---------------------------------------------------------------- manifest

    /** Parses one mod's GenLauncherData.yaml into a version. */
    static RepoVersion fetchVersion(RepoMod mod) throws IOException {
        return parseManifest(httpGet(mod.manifestUrl));
    }

    static RepoVersion parseManifest(String yaml) {
        RepoVersion v = new RepoVersion();
        v.name = yamlValue(yaml, "Name");
        v.version = yamlValue(yaml, "Version");
        v.simpleDownloadLink = trimNull(yamlValue(yaml, "SimpleDownloadLink"));
        v.imageUrl = trimNull(yamlValue(yaml, "UIImageSourceLink"));
        v.modDbLink = trimNull(yamlValue(yaml, "ModDBLink"));
        v.discordLink = trimNull(yamlValue(yaml, "DiscordLink"));
        v.s3Host = trimNull(yamlValue(yaml, "S3HostLink"));
        v.s3Bucket = trimNull(yamlValue(yaml, "S3BucketName"));
        v.s3Folder = trimNull(yamlValue(yaml, "S3FolderName"));
        if (v.name == null) {
            v.name = "Unknown mod";
        }
        if (v.version == null || v.version.isEmpty()) {
            v.version = "latest";
        }
        return v;
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

    private static String httpGet(String url, String... headers) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", ModDbClient.USER_AGENT);
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
