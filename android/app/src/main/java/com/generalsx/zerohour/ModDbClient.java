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
// A tiny read-only ModDB client for the in-app mod manager. It browses the
// public "mods for C&C: Generals Zero Hour" index and per-mod download
// listings, and resolves a file page to a direct download URL. No API key,
// no login, nothing written back to the site — ModDB is fetched exactly like
// a browser tab would be (desktop User-Agent; its default response to an
// unknown client is a Cloudflare challenge page, which would break parsing).
//
// Parsing is regex-over-HTML on purpose: ModDB has no public JSON API, the
// markup has been stable for years, and pulling in a real HTML parser
// (jsoup) would triple this app's method count for one screen. Every parse
// result is defensive: an unexpected page shape yields an empty list or a
// null field and a user-visible error string, never a crash.
//
// GeneralsX @performance 15/09/2026 Request pacing: ModDB fronts everything
// with Cloudflare, and a burst of page GETs (list -> profile -> downloads,
// one per tap) can trip a soft rate limit that then serves challenge pages
// for the whole IP for a while. All page fetches now go through one gated
// entry point that keeps >= 1.2s between requests, and a 429/503 response
// backs off (30s) instead of hammering. The app's own fetch pattern is
// human-paced to begin with; this just removes the accidental burst paths.

package com.generalsx.zerohour;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ModDbClient {

    static final String SITE = "https://www.moddb.com";
    // The game's own index page. Everything the Browse tab shows comes from here.
    private static final String MODS_LIST_URL = SITE + "/games/cc-generals-zero-hour/mods";
    // Package-visible: ThumbCache reuses the same UA for imagehost fetches.
    static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    /** Per-file guard, matching SetupActivity's language-pack cap. */
    static final long MAX_DOWNLOAD_BYTES = 8L * 1024 * 1024 * 1024;

    // GeneralsX @performance 15/09/2026 The pacing gate. Static because the
    // client is stateless and every screen shares the same site; the guard
    // object only ever protects two long fields.
    private static final long MIN_PAGE_INTERVAL_MS = 1200;
    private static final long RATE_LIMIT_BACKOFF_MS = 30_000;
    private static final Object PACING_LOCK = new Object();
    private static long s_lastPageFetchMs = 0;
    private static long s_backoffUntilMs = 0;

    // ------------------------------------------------------------ data types

    /** One row of the Browse list: a mod on ModDB, not a local install. */
    static final class ModSummary {
        final String name;        // display name, decoded
        final String profilePath; // site path of the mod's profile, e.g. /mods/cc-shockwave
        final String description; // one-line blurb from the list row (may be empty)
        // GeneralsX @feature 15/09/2026 Optional rich fields for the card
        // layout. Every one is nullable/empty-safe: an older or reshaped page
        // simply renders a plainer card, it never fails the list.
        final String imageUrl;    // row thumbnail (ModDB imagehost CDN), or null
        final String rating;      // "9.2" style text, or null
        final String downloads;   // page-scoped download count as shown, or null

        ModSummary(String name, String profilePath, String description,
                   String imageUrl, String rating, String downloads) {
            this.name = name;
            this.profilePath = profilePath;
            this.description = description;
            this.imageUrl = imageUrl;
            this.rating = rating;
            this.downloads = downloads;
        }
    }

    /** One downloadable file of a mod (a release, patch, or addon). */
    static final class ModFile {
        final String name;
        final String category;   // "Full Version", "Patch", "Addon", ...
        final String date;       // human-readable post date as ModDB shows it
        final String sizeBytes;  // size line as ModDB shows it, e.g. "282.14mb"
        final String pagePath;   // site path of the file's page; download resolves from here

        ModFile(String name, String category, String date, String sizeBytes, String pagePath) {
            this.name = name;
            this.category = category;
            this.date = date;
            this.sizeBytes = sizeBytes;
            this.pagePath = pagePath;
        }
    }

    /**
     * GeneralsX @feature 15/09/2026 A mod's profile detail: the full
     * description and hero image shown on the detail screen before the user
     * commits to a download. Fetched from the same profile page whose
     * downloads tab the file list already comes from — no new endpoint.
     */
    static final class ModDetails {
        final String profilePath;
        final String name;
        final String description; // profile page's description section, decoded
        final String imageUrl;    // profile hero/imagehost URL, or null

        ModDetails(String profilePath, String name, String description, String imageUrl) {
            this.profilePath = profilePath;
            this.name = name;
            this.description = description;
            this.imageUrl = imageUrl;
        }
    }

    // --------------------------------------------------------- page fetching

    /** Single entry point for HTML page GETs: pacing + rate-limit backoff. */
    static String fetchPage(String url) throws IOException {
        synchronized (PACING_LOCK) {
            long wait = s_backoffUntilMs - System.currentTimeMillis();
            if (wait <= 0) {
                wait = s_lastPageFetchMs + MIN_PAGE_INTERVAL_MS - System.currentTimeMillis();
            }
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("cancelled");
                }
            }
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            final int status = conn.getResponseCode();
            if (status == 429 || status == 503) {
                synchronized (PACING_LOCK) {
                    s_backoffUntilMs = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS;
                }
                throw new IOException("site is rate-limiting us — try again in a minute");
            }
            synchronized (PACING_LOCK) {
                s_lastPageFetchMs = System.currentTimeMillis();
            }
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status);
            }
            StringBuilder body = new StringBuilder(256 * 1024);
            try (BufferedReader r = new BufferedReader(
                     new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[16 * 1024];
                int n;
                while ((n = r.read(buf)) > 0) {
                    body.append(buf, 0, n);
                    if (body.length() > 16 * 1024 * 1024) {
                        throw new IOException("page too large");
                    }
                }
            }
            String html = body.toString();
            if (html.contains("Just a moment...") && html.contains("challenge-platform")) {
                // Cloudflare interstitial: a bot verdict, not a page we can parse.
                throw new IOException("site is temporarily blocking automated access");
            }
            return html;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ------------------------------------------------------------ list pages

    /**
     * Page N (1-based) of all mods for Zero Hour, sorted by last update.
     * ModDB paginates at 30 rows/page; hasMore says whether a next page exists.
     */
    static List<ModSummary> fetchModList(int page, boolean[] hasMore) throws IOException {
        String url = page <= 1 ? MODS_LIST_URL : MODS_LIST_URL + "/page/" + page;
        String html = fetchPage(url);
        List<ModSummary> out = new ArrayList<>();
        Matcher row = ROW_START.matcher(html);
        while (row.find()) {
            String block = rowBlock(html, row);
            String href = firstMatch(block, MOD_PROFILE_HREF);
            if (href == null || href.contains("/add")) {
                continue; // "Add mod" row and fragments without a profile link
            }
            String name = decodeEntities(firstMatch(block, H4_TITLE));
            if (name == null || name.isEmpty()) {
                name = decodeEntities(attr(block, "title"));
            }
            if (name == null || name.isEmpty()) {
                continue;
            }
            String desc = decodeEntities(firstMatch(block, ROW_BLURB));
            out.add(new ModSummary(name, href, desc == null ? "" : desc,
                                   firstMatch(block, ROW_THUMB),
                                   decodeEntities(firstMatch(block, ROW_RATING)),
                                   decodeEntities(firstMatch(block, ROW_DOWNLOADS))));
        }
        if (hasMore != null && hasMore.length > 0) {
            hasMore[0] = html.contains("/page/" + (page + 1));
        }
        return out;
    }

    /** Search the game's mod index by keyword; same row shape as the plain list. */
    static List<ModSummary> searchMods(String query) throws IOException {
        String url = MODS_LIST_URL + "?filter=t&kw=" + URLEncoder.encode(query, "UTF-8");
        String html = fetchPage(url);
        List<ModSummary> out = new ArrayList<>();
        Matcher row = ROW_START.matcher(html);
        while (row.find()) {
            String block = rowBlock(html, row);
            String href = firstMatch(block, MOD_PROFILE_HREF);
            if (href == null || href.contains("/add")) {
                continue;
            }
            String name = decodeEntities(firstMatch(block, H4_TITLE));
            if (name == null || name.isEmpty()) {
                continue;
            }
            String desc = decodeEntities(firstMatch(block, ROW_BLURB));
            out.add(new ModSummary(name, href, desc == null ? "" : desc,
                                   firstMatch(block, ROW_THUMB),
                                   decodeEntities(firstMatch(block, ROW_RATING)),
                                   decodeEntities(firstMatch(block, ROW_DOWNLOADS))));
        }
        return out;
    }

    /** All downloadable files of one mod, newest first as ModDB orders them. */
    static List<ModFile> fetchModFiles(String profilePath) throws IOException {
        // The downloads tab of the mod's profile. Trailing "#downloadsform"
        // anchors seen on the site are stripped by the href pattern itself.
        String html = fetchPage(SITE + profilePath + "/downloads");
        List<ModFile> out = new ArrayList<>();
        Matcher row = ROW_START.matcher(html);
        while (row.find()) {
            String block = rowBlock(html, row);
            String page = firstMatch(block, DOWNLOAD_PAGE_HREF);
            if (page == null) {
                continue;
            }
            String name = decodeEntities(firstMatch(block, H4_TITLE));
            if (name == null || name.isEmpty()) {
                continue;
            }
            String category = decodeEntities(firstMatch(block, SUBHEADING_CATEGORY));
            String date = firstMatch(block, ROW_DATE);
            // The file page itself carries the exact size; the list row does
            // not. Leave it null here and fill it in when the user opens the
            // detail view (a later fetchFileSize could add it).
            out.add(new ModFile(name, category == null ? "" : category,
                                date == null ? "" : date, null, page));
        }
        return out;
    }

    /**
     * GeneralsX @feature 15/09/2026 Fetches a mod's profile page for the
     * detail screen: full description + hero image. The description section
     * is <div id="introwrap"> on mod profiles; a reshaped page yields empty
     * fields and the detail screen renders what it has.
     */
    static ModDetails fetchModDetails(ModSummary summary) throws IOException {
        String html = fetchPage(SITE + summary.profilePath);
        String desc = "";
        Matcher intro = PROFILE_INTRO.matcher(html);
        if (intro.find()) {
            String raw = html.substring(intro.end(),
                Math.min(html.length(), intro.end() + 8192));
            int close = raw.indexOf("</div>");
            if (close >= 0) {
                raw = raw.substring(0, close);
            }
            desc = stripHtml(raw);
            if (desc.length() > 1200) {
                desc = desc.substring(0, 1200) + "\u2026";
            }
        }
        String hero = firstMatch(html, PROFILE_HERO);
        if (hero == null) {
            hero = firstMatch(html, PROFILE_HERO_META);
        }
        return new ModDetails(summary.profilePath, summary.name, desc,
                              hero != null ? hero : summary.imageUrl);
    }

    /** Truncates the markup after a list-row start marker into a parse block. */
    private static String rowBlock(String html, Matcher row) {
        int end = html.indexOf("</div>\t</div>", row.end());
        if (end < 0) {
            end = Math.min(html.length(), row.end() + 4096);
        }
        return html.substring(row.end(), end);
    }

    // --------------------------------------------------------- download path

    /**
     * Resolves a file page (/mods/<mod>/downloads/<file>) to a direct CDN URL
     * by walking the chain the site itself uses in a browser:
     *   file page -> /downloads/start/<id> (mirror chooser)
     *            -> /downloads/mirror/<id>/<m>/<hash> (302)
     *            -> https://<cdn>/dl/... (the file)
     * The mirror step is a plain redirect; we follow Location headers by hand
     * so the final URL survives for the caller's own streamed GET.
     */
    static String resolveDownloadUrl(String filePagePath) throws IOException {
        String pageHtml = fetchPage(SITE + filePagePath);
        String startHref = firstMatch(pageHtml, DOWNLOAD_START_HREF);
        if (startHref == null) {
            throw new IOException("no download button on the file page");
        }
        String url = SITE + startHref;
        for (int hop = 0; hop < 5; hop++) {
            String next = peekRedirect(url);
            if (next == null) {
                return url; // this URL serves the bytes; done
            }
            url = next;
        }
        throw new IOException("too many redirects");
    }

    /**
     * Opens a byte stream of the file starting at the given offset (0 for a
     * fresh download) and reports the total size if the server exposes one.
     * GeneralsX @feature 15/09/2026 Resume support: a 2 GB download that dies
     * at 90% restarts from 90% of the .dl partial, not from zero — the
     * ModDB CDN honors standard Range requests. totalSize[0] receives the
     * full file size when the response carries Content-Length (-1 otherwise);
     * with an offset the status is 206 and Content-Length is the remainder.
     */
    static InputStream openDownloadStream(String url, long offset,
                                          long[] totalSize,
                                          HttpURLConnection[] outConn) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/octet-stream, */*");
        if (offset > 0) {
            conn.setRequestProperty("Range", "bytes=" + offset + "-");
        }
        conn.setInstanceFollowRedirects(true);
        final int status = conn.getResponseCode();
        if (offset > 0 && status == 416) {
            // "Range not satisfiable" — most often the CDN does not honor
            // Range at all or the partial is already complete. Caller retries
            // from zero.
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

    /**
     * One manual redirect hop: returns the Location target when the URL
     * redirects, or null when this URL itself serves content.
     */
    private static String peekRedirect(String url) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setInstanceFollowRedirects(false);
            final int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_MOVED_TEMP
                    || status == HttpURLConnection.HTTP_MOVED_PERM
                    || status == 307 || status == 308) {
                String location = conn.getHeaderField("Location");
                if (location == null) {
                    throw new IOException("redirect without Location");
                }
                return location;
            }
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status);
            }
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ----------------------------------------------------------- html tools

    /** A mod/download list row: everything after the marker until the row's close. */
    private static final Pattern ROW_START =
        Pattern.compile("class=\"row rowcontent");

    private static final Pattern H4_TITLE =
        Pattern.compile("<h4><a href=\"[^\"]+\"[^>]*>([^<]+)</a>");
    private static final Pattern ROW_BLURB =
        Pattern.compile("<p>([^<]{8,400})</p>");
    private static final Pattern ROW_DATE =
        Pattern.compile("<time datetime=\"[^\"]*\">([^<]+)</time>");
    private static final Pattern SUBHEADING_CATEGORY =
        Pattern.compile("subheading\">\\s*(?:<time[^>]*>[^<]*</time>)?\\s*([A-Za-z ][^<]{2,40})<");
    // GeneralsX @feature 15/09/2026 Row thumbnails: ModDB's imagehost CDN URLs
    // in the row's img src. imagethumb URLs are small (<100KB) and stable.
    private static final Pattern ROW_THUMB =
        Pattern.compile("src=\"(https://[^\"]*(?:imagehost|moddb)\\.com/[^\"]*(?:imagethumb|thumb)[^\"]*\\.(?:jpg|png|jpeg))\"");
    private static final Pattern ROW_RATING =
        Pattern.compile("class=\"rating\"[^>]*>\\s*([0-9.]+)");
    // GeneralsX @bugfix 15/09/2026 Anchored to the row's own stats span:
    // the unanchored "N downloads" pattern could match any sentence in the
    // row (e.g. a blurb "over 100,000 downloads"), mislabeling cards.
    // ModDB wraps each row stat as <span class="...">N downloads</span>, so
    // requiring the tag before the number keeps it to the real counter.
    private static final Pattern ROW_DOWNLOADS =
        Pattern.compile("<span[^>]*>\\s*([0-9][0-9,.]*)\\s*downloads</span>");
    // GeneralsX @feature 15/09/2026 Profile-page description + hero image.
    private static final Pattern PROFILE_INTRO =
        Pattern.compile("id=\"introwrap\"");
    private static final Pattern PROFILE_HERO =
        Pattern.compile("src=\"(https://[^\"]*(?:imagehost|moddb)\\.com/[^\"]*/images/mods/[^\"]+\\.(?:jpg|png|jpeg))\"");
    private static final Pattern PROFILE_HERO_META =
        Pattern.compile("property=\"og:image\"\\s+content=\"([^\"]+)\"");
    // Profile links (/mods/<name>) but not ones into a mod's downloads tree
    // (those belong to file rows, not the mod itself).
    private static final Pattern MOD_PROFILE_HREF =
        Pattern.compile("href=\"(/mods/[a-z0-9_-]+)\"");
    private static final Pattern DOWNLOAD_PAGE_HREF =
        Pattern.compile("href=\"(/mods/[a-z0-9_-]+/downloads/[a-z0-9_-]+)\"");
    private static final Pattern DOWNLOAD_START_HREF =
        Pattern.compile("href=\"(/downloads/start/[0-9]+)\"");

    private static String firstMatch(String text, Pattern p) {
        if (text == null) {
            return null;
        }
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String attr(String text, String name) {
        Matcher m = Pattern.compile(name + "=\"([^\"]*)\"").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** Strips tags and entities from a page fragment for plain-text display. */
    private static String stripHtml(String raw) {
        String text = raw.replaceAll("<br\\s*/?>", "\n")
                         .replaceAll("</p>\\s*<p[^>]*>", "\n\n")
                         .replaceAll("<[^>]+>", " ");
        text = decodeEntities(text);
        // Collapse runs of whitespace, but keep the \n breaks above.
        text = text.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                   .replaceAll(" ?\\n ?", "\n")
                   .replaceAll("\n{3,}", "\n\n")
                   .trim();
        return text;
    }

    /** The handful of entities ModDB actually emits in these fields. */
    static String decodeEntities(String s) {
        if (s == null) {
            return null;
        }
        if (!s.contains("&")) {
            return s.trim();
        }
        return s.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#039;", "'")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .trim();
    }

    private ModDbClient() {
    }
}
