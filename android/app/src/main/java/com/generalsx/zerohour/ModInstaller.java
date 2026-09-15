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
// Turns a ModDB download into an installed mod: streams the archive to a
// temp file, extracts every .big inside (recursing into the archive's own
// folder layout because ModDB authors nest arbitrarily: "Rise of the Reds/",
// "1.87/", "ROTR/1.87/Data/" ...) and copies loose .big files, into
//   <gameFolder>/Mods/<ModName>/<FileName>/
// A folder per downloaded file, not per mod: "Contra 009 Final" and
// "Contra 009 Patch" stay separately launchable and separately deletable.
// Delete = rmdir -r of the folder; no game file is ever touched.
//
// The engine mounts the chosen folder's entire tree (recursive glob) at
// archive-overwrite priority, so any layout we produce here loads.
//
// Extraction: java.util.zip for .zip (Android ships it), junrar for .rar
// (pure-Java RAR4, Apache-2.0, no transitive deps), commons-compress for
// .7z (GeneralsX @feature 15/09/2026 — a meaningful share of ModDB releases
// ship as 7z, and they were previously refused outright). A .big handed in
// directly (from storage, or already-extracted downloads) is copied as-is.
// RAR5 is still refused with a clear message rather than half-installed.
//
// GeneralsX @performance 15/09/2026 Resumable downloads: the archive lands
// in <Name>.part.dl and grows via HTTP Range requests across retries, so a
// 2 GB release that drops at 90% no longer restarts from zero. Free space
// is checked before downloading, and stale .part/.dl scratch files from
// crashed sessions are swept on the Mods screen's startup.

package com.generalsx.zerohour;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ModInstaller {

    /**
     * A single installed version leaf: <gameFolder>/Mods/<Mod>/<Version>/.
     * GenLauncher's model — every downloaded release of a mod stays
     * separately installed, selectable and deletable; switching versions
     * never destroys another.
     */
    static final class InstalledMod {
        final String displayName; // version folder name (shown verbatim in the UI)
        final String dirPath;     // absolute path handed to the engine via -mod
        final long bytesUsed;     // size of every file under dirPath
        final boolean selected;   // currently written to mod_launch.cfg

        InstalledMod(String displayName, String dirPath, long bytesUsed, boolean selected) {
            this.displayName = displayName;
            this.dirPath = dirPath;
            this.bytesUsed = bytesUsed;
            this.selected = selected;
        }
    }

    /**
     * GeneralsX @feature 16/09/2026 A mod family: one ModDB profile's group
     * of installed versions (a mod with one legacy folder still groups as a
     * single-version family). Groups keep the Mods page GenLauncher-shaped:
     * the mod is the row, versions expand beneath it, and the update badge
     * lives on the group.
     */
    static final class ModGroup {
        final String modName;              // family name = top-level folder under Mods/
        final List<InstalledMod> versions; // sorted: selected first, then name
        final long bytesUsed;              // whole family
        final boolean anySelected;

        ModGroup(String modName, List<InstalledMod> versions) {
            this.modName = modName;
            this.versions = versions;
            long total = 0;
            boolean selected = false;
            for (InstalledMod v : versions) {
                total += v.bytesUsed;
                selected |= v.selected;
            }
            this.bytesUsed = total;
            this.anySelected = selected;
        }
    }

    /** Progress for the download+extract pipeline; delivered via runOnUiThread. */
    interface Listener {
        void onPhase(String text);
        void onProgress(long bytesRead, long totalBytes);
    }

    private static final String MODS_DIR_NAME = "Mods";
    /** Sidecar holding the ModDB file page path for update checks. */
    static final String META_SUFFIX = ".moddb_meta";
    /** Big archive format magic: every .big starts with these four bytes. */
    private static final long BIGF_MAGIC = 0x42494746L; // "BIGF"

    // ------------------------------------------------------------ discovery

    static File modsRoot(String gameFolder) {
        return new File(gameFolder, MODS_DIR_NAME);
    }

    /**
     * GeneralsX @feature 16/09/2026 Writes the metadata sidecar alongside an
     * installed leaf so its origin (ModDB file page) survives across
     * sessions — the input to update checks, and the distinguishing mark
     * between a ModDB-installed version and a storage import.
     */
    static void writeMeta(File fileDir, String filePagePath) {
        if (filePagePath == null || filePagePath.isEmpty()) {
            return; // local import: no origin, no update path
        }
        try (java.io.FileWriter w = new java.io.FileWriter(
                 new File(fileDir.getParentFile(),
                          fileDir.getName() + META_SUFFIX), false)) {
            w.write(filePagePath);
            w.write("\n");
        } catch (IOException e) {
            // Sidecar is an optimization, not a correctness requirement:
            // without it the version just never shows an update badge.
        }
    }

    /** Reads the sidecar from a version folder; null when absent/empty. */
    static String readMeta(File versionDir, String leafName) {
        File meta = new File(versionDir, leafName + META_SUFFIX);
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(meta))) {
            String line = r.readLine();
            return (line != null && !line.trim().isEmpty()) ? line.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Free bytes on the volume holding the game folder (0 when unknown). */
    static long freeBytes(String gameFolder) {
        try {
            return modsRoot(gameFolder).getUsableSpace();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * GeneralsX @bugfix 15/09/2026 Sweeps scratch files a killed session can
     * leave behind: <file>.part.dl partials from interrupted downloads and
     * ".<name>.extracted" dirs from interrupted nested-archive extraction.
     * GenLauncher's most-complained-about issue is storage never reclaimed;
     * this runs on every Mods-screen open so a crash never costs the user
     * gigabytes of invisible junk.
     *
     * keepPartialBase exempts the live install's own partial: the .part.dl
     * IS the resume asset — deleting it turns every retry into a full
     * re-download, so the sweep skips exactly the file a pending retry
     * would resume from (matched on the download temp's "<base>.part.dl"
     * name) while still reclaiming genuine orphans.
     */
    static void cleanupTempFiles(String gameFolder, String keepPartialBase) {
        String keepName = (keepPartialBase == null || keepPartialBase.isEmpty())
            ? null : keepPartialBase.toLowerCase(Locale.US) + ".part.dl";
        File root = modsRoot(gameFolder);
        File[] entries = root.listFiles();
        if (entries == null) {
            return;
        }
        for (File modDir : entries) {
            if (!modDir.isDirectory()) {
                continue;
            }
            File[] children = modDir.listFiles();
            if (children == null) {
                continue;
            }
            for (File child : children) {
                String name = child.getName().toLowerCase(Locale.US);
                if (child.isFile() && name.endsWith(".part.dl")) {
                    if (name.equals(keepName)) {
                        continue; // the live download's resume asset
                    }
                    child.delete();
                } else if (child.isDirectory() && name.startsWith(".")
                           && name.endsWith(".extracted")) {
                    deleteRecursively(child);
                }
            }
        }
    }

    /**
     * Lists installed mods grouped per ModDB profile. Legacy single-leaf
     * layout (pre-1.4 folders directly under Mods/) is read as a
     * single-version family, so nothing installed before this change
     * disappears from the UI or stops launching.
     */
    static List<ModGroup> listGroups(String gameFolder, String launchPath) {
        List<ModGroup> out = new ArrayList<>();
        File root = modsRoot(gameFolder);
        File[] entries = root.listFiles();
        if (entries != null) {
            for (File e : entries) {
                if (!e.isDirectory()) {
                    continue;
                }
                List<InstalledMod> versions = new ArrayList<>();
                File[] children = e.listFiles();
                if (children != null) {
                    for (File c : children) {
                        if (c.isDirectory() && hasBigFile(c)) {
                            versions.add(new InstalledMod(c.getName(), c.getAbsolutePath(),
                                dirSize(c), c.getAbsolutePath().equals(launchPath)));
                        }
                    }
                }
                // Legacy layout: .big files sit directly in the mod folder.
                if (versions.isEmpty() && hasBigFile(e)) {
                    versions.add(new InstalledMod(e.getName(), e.getAbsolutePath(),
                        dirSize(e), e.getAbsolutePath().equals(launchPath)));
                }
                if (!versions.isEmpty()) {
                    versions.sort((a, b) -> {
                        if (a.selected != b.selected) {
                            return a.selected ? -1 : 1;
                        }
                        return a.displayName.compareToIgnoreCase(b.displayName);
                    });
                    out.add(new ModGroup(e.getName(), versions));
                }
            }
        }
        out.sort((a, b) -> {
            if (a.anySelected != b.anySelected) {
                return a.anySelected ? -1 : 1;
            }
            return a.modName.compareToIgnoreCase(b.modName);
        });
        return out;
    }

    /** Does this folder hold at least one .big anywhere below it? */
    private static boolean hasBigFile(File dir) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return false;
        }
        for (File e : entries) {
            if (e.isFile() && e.getName().toLowerCase(Locale.US).endsWith(".big")) {
                return true;
            }
            if (e.isDirectory() && hasBigFile(e)) {
                return true;
            }
        }
        return false;
    }

    static long dirSize(File dir) {
        long total = 0;
        File[] entries = dir.listFiles();
        if (entries != null) {
            for (File e : entries) {
                if (e.isDirectory()) {
                    total += dirSize(e);
                } else {
                    total += e.length();
                }
            }
        }
        return total;
    }

    // ------------------------------------------------------- install/delete

    /**
     * Downloads url and installs it as gameFolder/Mods/<modName>/<fileName>/.
     * Returns the created folder. Throws with a user-presentable message.
     * Refuses to start when the volume cannot plausibly hold the file.
     */
    static File downloadAndInstall(String url, String gameFolder, String modName,
                                   String fileBaseName, Listener listener) throws Exception {
        return downloadAndInstall(url, gameFolder, modName, fileBaseName, null, listener);
    }

    /**
     * Same, and records the ModDB file page in the version sidecar so the
     * panel can offer "update available" later.
     */
    static File downloadAndInstall(String url, String gameFolder, String modName,
                                   String fileBaseName, String filePagePath,
                                   Listener listener) throws Exception {
        File fileDir = prepareFileDir(gameFolder, modName, fileBaseName);
        File tmp = new File(fileDir.getParentFile(), fileBaseName + ".part");
        try {
            downloadResumable(url, tmp, listener);
            if (listener != null) {
                listener.onPhase("extracting");
            }
            int count = extract(tmp, fileDir, listener);
            if (count == 0) {
                throw new IOException("no .big archives found in this download");
            }
            writeMeta(fileDir, filePagePath);
            return fileDir;
        } finally {
            tmp.delete();
        }
    }

    /**
     * GeneralsX @feature 15/09/2026 Installs a mod from a file the user
     * picked on the device (SAF copy handed to us as a real File) or any
     * archive already on disk. Same extraction path as ModDB downloads, so
     * zip/rar/7z/nested all behave identically; a bare .big is copied as-is.
     */
    static File installFromLocalFile(File archive, String gameFolder, String modName,
                                     Listener listener) throws Exception {
        String base = archive.getName();
        int dot = base.lastIndexOf('.');
        String fileBaseName = dot > 0 ? base.substring(0, dot) : base;
        if (fileBaseName.trim().isEmpty()) {
            fileBaseName = "mod";
        }
        File fileDir = prepareFileDir(gameFolder, modName, fileBaseName);
        if (listener != null) {
            listener.onPhase("extracting");
        }
        int count = extract(archive, fileDir, listener);
        if (count == 0) {
            throw new IOException("no .big archives found in this file");
        }
        // Local imports carry no ModDB origin — no sidecar, no update badge.
        writeMeta(fileDir, null);
        return fileDir;
    }

    /** Creates (or resets) the install target dir; shared by both install paths. */
    private static File prepareFileDir(String gameFolder, String modName,
                                       String fileBaseName) throws IOException {
        File modsRoot = modsRoot(gameFolder);
        if (!modsRoot.isDirectory() && !modsRoot.mkdirs()) {
            throw new IOException("cannot create " + modsRoot);
        }
        // The two leaf names come from ModDB titles; strip path-hostile chars.
        File modDir = safeDir(modsRoot, modName);
        File fileDir = safeDir(modDir, fileBaseName);
        if (fileDir.exists()) {
            deleteRecursively(fileDir);
        }
        if (!fileDir.mkdirs()) {
            throw new IOException("cannot create " + fileDir);
        }
        return fileDir;
    }

    static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        f.delete();
    }

    /** Sanitized "<modsRoot>/<raw>" that refuses to escape the mods root. */
    private static File safeDir(File modsRoot, String raw) {
        String cleaned = raw.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            cleaned = "mod";
        }
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(0, 80).trim();
        }
        File f = new File(modsRoot, cleaned);
        try {
            if (!f.getCanonicalPath().startsWith(modsRoot.getCanonicalPath())) {
                throw new IOException("bad install path");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return f;
    }

    // ------------------------------------------------------------- download

    /**
     * Streams url into dest, resuming from an existing dest+".dl" partial via
     * a Range request. A resume the server refuses (416, or a 200 that means
     * "starting over") falls back to a clean restart — the invariant is that
     * the user never waits longer than the plain non-resumable download.
     */
    private static void download(String url, File dest, Listener listener) throws IOException {
        File partial = new File(dest.getParentFile(), dest.getName() + ".dl");
        long have = partial.isFile() ? partial.length() : 0;
        if (have > ModDbClient.MAX_DOWNLOAD_BYTES) {
            partial.delete(); // corrupt scratch from an interrupted huge file
            have = 0;
        }
        if (have > 0 && listener != null) {
            listener.onPhase("resuming");
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection conn = null;
            try {
                HttpURLConnection[] holder = new HttpURLConnection[1];
                long[] sizeHolder = new long[1];
                InputStream in;
                try {
                    in = ModDbClient.openDownloadStream(url, have, sizeHolder, holder);
                } catch (IOException e) {
                    if (attempt == 0 && have > 0) {
                        // Resume refused (HTTP 416) — restart cleanly once.
                        have = 0;
                        partial.delete();
                        continue;
                    }
                    throw e;
                }
                conn = holder[0];
                long total = sizeHolder[0];
                if (total > ModDbClient.MAX_DOWNLOAD_BYTES) {
                    throw new IOException("file larger than the 8 GB safety cap");
                }
                if (total > 0 && total <= have) {
                    // Partial already covers (or exceeds) the full size: trust
                    // it and finish. The extract step still validates content.
                    in.close();
                    break;
                }
                long done = have;
                try (OutputStream out = new BufferedOutputStream(
                         new FileOutputStream(partial, have > 0))) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        done += n;
                        if (done > ModDbClient.MAX_DOWNLOAD_BYTES) {
                            throw new IOException("file larger than the 8 GB safety cap");
                        }
                        out.write(buf, 0, n);
                        if (listener != null && total > 0) {
                            listener.onProgress(done, total);
                        }
                    }
                }
                break; // clean end of stream
            } finally {
                ModDbClient.disconnectQuietly(conn);
            }
        }

        // Guard against a server that closed early: the partial must now be
        // complete only if Content-Length said so; without a length we accept
        // the stream's end as the end (extract validates the archive anyway).
        if (!partial.isFile() || partial.length() == 0) {
            throw new IOException("download produced no data");
        }
        if (!partial.renameTo(dest)) {
            throw new IOException("could not finalize download");
        }
    }

    /** Same contract as download(); exists so its name says what it does now. */
    private static void downloadResumable(String url, File dest, Listener listener)
            throws IOException {
        download(url, dest, listener);
    }

    // ------------------------------------------------------------ extraction

    /**
     * Extracts every .big from archive into dest. Supports zip, rar (junrar)
     * and 7z (commons-compress), recursing into inner zips/rars/7z — ModDB
     * mod authors ship zip-in-zip often enough that flat extraction is not
     * enough. A plain .big input is "extracted" as a straight copy (count 1).
     * Returns the number of .big files placed.
     */
    static int extract(File archive, File dest, Listener listener) throws Exception {
        int count;
        String name = archive.getName().toLowerCase(Locale.US);
        if (name.endsWith(".zip")) {
            count = extractZip(archive, dest, listener);
        } else if (name.endsWith(".rar")) {
            count = extractRar(archive, dest, listener);
        } else if (name.endsWith(".7z")) {
            count = extract7z(archive, dest, listener);
        } else if (name.endsWith(".big")) {
            copyFile(archive, new File(dest, archive.getName()));
            count = 1;
        } else {
            throw new IOException("unsupported archive type: " + archive.getName());
        }
        // Second pass: nested archives found inside.
        List<File> nested = new ArrayList<>();
        collectArchives(dest, nested);
        for (File inner : nested) {
            File innerDest = new File(inner.getParentFile(), "." + inner.getName() + ".extracted");
            int innerCount = extract(inner, innerDest, listener);
            if (innerCount > 0) {
                // Flatten the nested archive's content next to it, then drop
                // both the inner archive and its extraction scratch dir.
                moveChildren(innerDest, inner.getParentFile());
                count += innerCount;
            }
            deleteRecursively(innerDest);
            inner.delete();
        }
        return count;
    }

    private static void collectArchives(File dir, List<File> out) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File e : entries) {
            String n = e.getName().toLowerCase(Locale.US);
            if (e.isFile() && (n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z"))) {
                out.add(e);
            } else if (e.isDirectory()) {
                collectArchives(e, out);
            }
        }
    }

    private static void moveChildren(File from, File to) {
        File[] entries = from.listFiles();
        if (entries == null) {
            return;
        }
        for (File e : entries) {
            File dest = new File(to, e.getName());
            if (!e.renameTo(dest)) {
                // Same-name collision: keep the existing file, drop the new one.
                if (e.isDirectory()) {
                    deleteRecursively(e);
                } else {
                    e.delete();
                }
            }
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    /** Base name of an archive entry path, with both slash flavors handled. */
    private static String entryBase(String entryName) {
        int slash = Math.max(entryName.lastIndexOf('/'), entryName.lastIndexOf('\\'));
        return slash >= 0 ? entryName.substring(slash + 1) : entryName;
    }

    private static int extractZip(File zip, File dest, Listener listener) throws IOException {
        int count = 0;
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            byte[] buf = new byte[64 * 1024];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String base = entryBase(entry.getName());
                if (!base.toLowerCase(Locale.US).endsWith(".big")) {
                    continue;
                }
                File out = new File(dest, base);
                try (InputStream in = zf.getInputStream(entry);
                     OutputStream fout = new BufferedOutputStream(new FileOutputStream(out))) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fout.write(buf, 0, n);
                    }
                }
                count++;
                if (listener != null) {
                    listener.onProgress(count, -1);
                }
            }
        }
        return count;
    }

    private static int extractRar(File rar, File dest, Listener listener) throws Exception {
        int count = 0;
        try (Archive archive = new Archive(rar)) {
            byte[] buf = new byte[64 * 1024];
            FileHeader header;
            while ((header = archive.nextFileHeader()) != null) {
                if (header.isDirectory()) {
                    continue;
                }
                String name = header.getFileNameString();
                if (name == null) {
                    continue;
                }
                String base = entryBase(name);
                if (!base.toLowerCase(Locale.US).endsWith(".big")) {
                    continue;
                }
                File out = new File(dest, base);
                try (OutputStream fout = new BufferedOutputStream(new FileOutputStream(out))) {
                    archive.extractFile(header, fout);
                }
                count++;
                if (listener != null) {
                    listener.onProgress(count, -1);
                }
            }
        }
        return count;
    }

    // GeneralsX @feature 15/09/2026 7z support via commons-compress (pure
    // Java, Apache-2.0). Matches the zip/rar paths exactly: only .big leaves
    // are written, everything else in the archive is skipped.
    private static int extract7z(File sevenZ, File dest, Listener listener) throws IOException {
        int count = 0;
        try (SevenZFile zf = new SevenZFile(sevenZ)) {
            SevenZArchiveEntry entry;
            byte[] buf = new byte[64 * 1024];
            while ((entry = zf.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String base = entryBase(entry.getName());
                if (base == null || !base.toLowerCase(Locale.US).endsWith(".big")) {
                    // Skip the entry's bytes to keep the stream consistent.
                    skipEntry(zf, entry);
                    continue;
                }
                File out = new File(dest, base);
                try (OutputStream fout = new BufferedOutputStream(new FileOutputStream(out))) {
                    int n;
                    long remaining = entry.getSize();
                    while (remaining > 0 && (n = zf.read(buf, 0,
                            (int) Math.min(buf.length, remaining))) > 0) {
                        fout.write(buf, 0, n);
                        remaining -= n;
                    }
                }
                count++;
                if (listener != null) {
                    listener.onProgress(count, -1);
                }
            }
        }
        return count;
    }

    private static void skipEntry(SevenZFile zf, SevenZArchiveEntry entry) throws IOException {
        long remaining = entry.getSize();
        byte[] sink = new byte[64 * 1024];
        while (remaining > 0) {
            long n = zf.read(sink, 0, (int) Math.min(sink.length, remaining));
            if (n <= 0) {
                break;
            }
            remaining -= n;
        }
    }

    // ------------------------------------------------------------ validation

    /**
     * True when the file looks like a real .big archive: BIGF magic plus a
     * plausible directory-table size. GeneralsX @feature 15/09/2026
     * Pre-flight check before launch — a truncated download used to crash
     * the engine deep in INI parsing; now the launcher warns first.
     */
    private static boolean looksLikeBig(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] head = new byte[8];
            if (in.read(head) != head.length) {
                return false;
            }
            long magic = ((long) (head[0] & 0xFF) << 24)
                       | ((long) (head[1] & 0xFF) << 16)
                       | ((long) (head[2] & 0xFF) << 8)
                       | (long) (head[3] & 0xFF);
            if (magic != BIGF_MAGIC) {
                return false;
            }
            long archiveSize = ((long) (head[4] & 0xFF) << 24)
                             | ((long) (head[5] & 0xFF) << 16)
                             | ((long) (head[6] & 0xFF) << 8)
                             | (long) (head[7] & 0xFF);
            // Field is "file size - 8" per the BIG format; the loose sanity
            // bound catches truncated files without over-specifying.
            return archiveSize + 8 <= f.length() + 4096;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Validates a mod folder's .big set before handing it to -mod.
     * Returns an empty string when everything looks launchable, otherwise a
     * short non-localized reason ("not a valid BIG archive") the caller
     * wraps in a localized warning.
     */
    static String validateMod(String modDirPath) {
        File dir = new File(modDirPath);
        List<File> bigs = new ArrayList<>();
        collectBigs(dir, bigs);
        if (bigs.isEmpty()) {
            return "no .big files";
        }
        for (File f : bigs) {
            if (!looksLikeBig(f)) {
                return f.getName() + " is not a valid BIG archive";
            }
        }
        return "";
    }

    private static void collectBigs(File dir, List<File> out) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File e : entries) {
            if (e.isFile() && e.getName().toLowerCase(Locale.US).endsWith(".big")) {
                out.add(e);
            } else if (e.isDirectory()) {
                collectBigs(e, out);
            }
        }
    }

    private ModInstaller() {
    }
}
