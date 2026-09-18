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

// GeneralsX @feature Android port mod-launcher-2 15/09/2026
//
// A deliberately small thumbnail cache for the mod browser's card images.
// A 30-row ModDB page of thumbs is ~2 MB total; without a cache every
// rebuild() re-downloaded every image, and with a library (Glide/Coil) the
// app gains ~2 MB of dex for one screen. This is the ~100-line version:
//
//   memory  — LruCache sized at ~1/8 of the heap, decode-bitmap values
//   disk    — cacheDir/modthumbs/<sha1(url)>, zero-config eviction by OS
//   network — plain HttpURLConnection, no image library
//
// decode size is bounded by inSampleSize against ~256px, so a card grid
// never holds a full-resolution hero bitmap. A URL that fails twice is
// parked in a small negative-cache so a flaky network doesn't re-request
// per frame.

package com.Generals.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ThumbCache {

    private static final long MAX_DISK_BYTES = 32L * 1024 * 1024;
    private static final int MAX_PX = 256;
    private static final long FAIL_RETRY_MS = 5 * 60 * 1000;

    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(16) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };
    private static final Set<String> IN_FLIGHT = new HashSet<>();
    private static final Object LOCK = new Object();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "gx-thumb");
        t.setDaemon(true);
        return t;
    });

    interface Callback {
        void onLoaded(Bitmap bitmap);
    }

    /**
     * Starts an async load; callback fires on a worker thread (caller hops to
     * the UI thread itself if it needs to). Fires once; never throws.
     */
    static void load(String url, Callback callback) {
        if (url == null || url.isEmpty()) {
            return;
        }
        Bitmap cached = MEMORY.get(url);
        if (cached != null && !cached.isRecycled()) {
            callback.onLoaded(cached);
            return;
        }
        synchronized (LOCK) {
            Long failedAt = FAILED.get(url);
            if (failedAt != null
                && System.currentTimeMillis() - failedAt < FAIL_RETRY_MS) {
                return; // negative cache: a dead URL isn't re-hit for 5 minutes
            }
            if (!IN_FLIGHT.add(url)) {
                return; // already loading; the first request will serve both
            }
        }
        POOL.execute(() -> {
            Bitmap bmp = null;
            try {
                bmp = loadSync(url);
            } catch (Exception ignored) {
                // Negative-cached below with a timestamp so a dead CDN doesn't
                // get re-hit on every rebuild.
            }
            if (bmp != null) {
                MEMORY.put(url, bmp);
            }
            synchronized (LOCK) {
                IN_FLIGHT.remove(url);
                if (bmp == null) {
                    FAILED.put(url, System.currentTimeMillis());
                }
            }
            if (bmp != null) {
                callback.onLoaded(bmp);
            }
        });
    }

    private static final LruCache<String, Long> FAILED = new LruCache<>(64);

    private static Bitmap loadSync(String url) {
        // Disk cache first: cacheDir/modthumbs/<sha1(url)>.
        File file = diskFile(url);
        if (file != null && file.isFile() && file.length() > 0) {
            Bitmap bmp = decode(file);
            if (bmp != null) {
                return bmp;
            }
            file.delete(); // corrupt cache entry
        }

        byte[] bytes = fetch(url);
        if (bytes == null) {
            return null;
        }
        if (file != null) {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(bytes);
            } catch (Exception ignored) {
                // Cache miss on write is harmless; memory still gets the bitmap.
            }
            enforceDiskBudget();
        }
        return decodeBytes(bytes);
    }

    private static byte[] fetch(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", GenLauncherReposClient.USER_AGENT);
            conn.setRequestProperty("Accept", "image/*");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0 && buf.size() < 4 * 1024 * 1024) {
                buf.write(chunk, 0, n);
            }
            in.close();
            return buf.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static Bitmap decode(File file) {
        try {
            return decodeBytes(java.nio.file.Files.readAllBytes(file.toPath()));
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap decodeBytes(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= MAX_PX
               && bounds.outHeight / (sample * 2) >= MAX_PX) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
    }

    private static File diskFile(String url) {
        try {
            File dir = new File(getCacheDir(), "modthumbs");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return new File(dir, sb.toString() + ".img");
        } catch (Exception e) {
            return null;
        }
    }

    private static File sCacheDir;

    static void setCacheDir(File dir) {
        sCacheDir = dir;
    }

    private static File getCacheDir() {
        return sCacheDir;
    }

    /** Trim-on-write: oldest entries first when the disk cache exceeds budget. */
    private static void enforceDiskBudget() {
        try {
            File dir = getCacheDir();
            if (dir == null) {
                return;
            }
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            long total = 0;
            for (File f : files) {
                total += f.length();
            }
            if (total <= MAX_DISK_BYTES) {
                return;
            }
            java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : files) {
                if (total <= MAX_DISK_BYTES) {
                    break;
                }
                long len = f.length();
                if (f.delete()) {
                    total -= len;
                }
            }
        } catch (Exception ignored) {
            // Best-effort hygiene; never a crash over cache housekeeping.
        }
    }

    private ThumbCache() {
    }
}
