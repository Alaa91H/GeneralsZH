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
// Extraction: java.util.zip for .zip (Android ships it). .rar has no JDK
// support, so junrar (pure-Java RAR4 extractor, Apache-2.0, no transitive
// deps) covers the substantial share of ModDB releases shipped as .rar.
// Everything else (.exe installers, .7z) is refused with a clear message
// rather than half-installed.

package com.generalsx.zerohour;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ModInstaller {

    /** A mod present on disk under <gameFolder>/Mods/. */
    static final class InstalledMod {
        final String displayName; // folder name (shown verbatim in the UI)
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

    /** Progress for the download+extract pipeline; delivered via runOnUiThread. */
    interface Listener {
        void onPhase(String text);
        void onProgress(long bytesRead, long totalBytes);
    }

    private static final String MODS_DIR_NAME = "Mods";

    // ------------------------------------------------------------ discovery

    static File modsRoot(String gameFolder) {
        return new File(gameFolder, MODS_DIR_NAME);
    }

    /** Lists installed mods; the selected one (if any) sorts to the top. */
    static List<InstalledMod> listInstalled(String gameFolder, String launchPath) {
        List<InstalledMod> out = new ArrayList<>();
        File root = modsRoot(gameFolder);
        File[] entries = root.listFiles();
        if (entries != null) {
            for (File e : entries) {
                if (!e.isDirectory() || !hasBigFile(e)) {
                    continue; // empty/broken folders are invisible, not launchable
                }
                out.add(new InstalledMod(e.getName(), e.getAbsolutePath(),
                                         dirSize(e), e.getAbsolutePath().equals(launchPath)));
            }
        }
        out.sort((a, b) -> {
            if (a.selected != b.selected) {
                return a.selected ? -1 : 1;
            }
            return a.displayName.compareToIgnoreCase(b.displayName);
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
            if (e.isFile() && e.getName().toLowerCase().endsWith(".big")) {
                return true;
            }
            if (e.isDirectory() && hasBigFile(e)) {
                return true;
            }
        }
        return false;
    }

    private static long dirSize(File dir) {
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
     */
    static File downloadAndInstall(String url, String gameFolder, String modName,
                                   String fileBaseName, Listener listener) throws Exception {
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

        File tmp = new File(modDir, fileBaseName + ".part");
        try {
            download(url, tmp, listener);
            listener.onPhase("extracting");
            int count = extract(tmp, fileDir, listener);
            if (count == 0) {
                throw new IOException("no .big archives found in this download");
            }
            return fileDir;
        } finally {
            tmp.delete();
        }
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

    private static void download(String url, File dest, Listener listener) throws IOException {
        HttpURLConnection conn = null;
        File partial = new File(dest.getParentFile(), dest.getName() + ".dl");
        try {
            // openDownloadStream fills the array slot with the live connection
            // (content length + disconnect need it afterwards).
            HttpURLConnection[] holder = new HttpURLConnection[1];
            InputStream in = ModDbClient.openDownloadStream(url, holder);
            conn = holder[0];
            long total = conn.getContentLength();
            if (total > ModDbClient.MAX_DOWNLOAD_BYTES) {
                throw new IOException("file larger than the 8 GB safety cap");
            }
            long done = 0;
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(partial))) {
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
            if (!partial.renameTo(dest)) {
                throw new IOException("could not finalize download");
            }
        } finally {
            ModDbClient.disconnectQuietly(conn);
            partial.delete();
        }
    }

    /**
     * Extracts every .big from archive into dest. Supports zip and rar
     * ( junrar ), recursing into inner zips/rars — ModDB mod authors ship
     * zip-in-zip often enough that flat extraction is not enough. Returns
     * the number of .big files placed.
     */
    static int extract(File archive, File dest, Listener listener) throws Exception {
        int count = 0;
        String name = archive.getName().toLowerCase();
        if (name.endsWith(".zip")) {
            count = extractZip(archive, dest, listener);
        } else if (name.endsWith(".rar")) {
            count = extractRar(archive, dest, listener);
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
            String n = e.getName().toLowerCase();
            if (e.isFile() && (n.endsWith(".zip") || n.endsWith(".rar"))) {
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
                String base = entry.getName();
                int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
                base = slash >= 0 ? base.substring(slash + 1) : base;
                if (!base.toLowerCase().endsWith(".big")) {
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
                String base = name;
                int slash = Math.max(base.lastIndexOf('\\'), base.lastIndexOf('/'));
                base = slash >= 0 ? base.substring(slash + 1) : base;
                if (!base.toLowerCase().endsWith(".big")) {
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

    private ModInstaller() {
    }
}
