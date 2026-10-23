package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Continue watching row under the dock (config.xml, continue_shelf): what the owner left half-watched in the TV's
 * apps, read from the system's Continue watching (Watch Next, docs/watch-next.md), most recent first. A tile resumes
 * the title in its app. The row is all or nothing: exactly one full row of titles, or no row, so the grid below never
 * has a gap. Only apps that publish to Watch Next show up (Netflix does not). Everything runs off the main thread;
 * composed tiles are kept in the cache directory.
 */
final class ContinueShelf {

    static final class Item {
        /** The Watch Next program id. Stable across reloads. */
        final String id;
        final String title;
        /** The app that published the row; its tile opens {@link #target} there. */
        final String pkg;
        /** The program's intent URI. */
        final String target;
        final String art;
        /** Share already watched, 0 to 1, or -1 when the app gave no position. */
        final float progress;
        /** The composed tile (art, progress bar and app icon), or null until it is loaded. */
        volatile Bitmap tile;

        Item(String id, String title, String pkg, String target, String art, float progress) {
            this.id = id;
            this.title = title;
            this.pkg = pkg;
            this.target = target;
            this.art = art;
            this.progress = progress;
        }
    }

    interface Listener {
        /** On the main thread: the row to show (empty for none), and again as each tile's artwork arrives. */
        void onShelf(List<Item> items, boolean artworkOnly);
    }

    private static final int CONNECT_MS = 4000, READ_MS = 8000;

    private final Context ctx;
    private final int columns;
    private final int tileW, tileH;
    private final Listener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File dir;
    private List<Item> shown = Collections.emptyList();

    ContinueShelf(Context ctx, int columns, int tileW, int tileH, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.columns = columns;
        this.tileW = tileW;
        this.tileH = tileH;
        this.listener = listener;
        this.dir = new File(ctx.getCacheDir(), "continue");
    }

    /** Reads Watch Next again (cheap, local) and loads the artwork of tiles not composed yet. */
    void refresh() {
        worker.execute(() -> {
            List<Item> row = pick(watchNext(), columns);
            if (!sameIds(row, shown)) {
                // Keep artwork already composed for titles that stay.
                for (Item i : row) for (Item s : shown) if (s.id.equals(i.id)) i.tile = s.tile;
                publish(row, false);
            }
            loadArtwork(row);
        });
    }

    void shutdown() {
        worker.shutdownNow();
    }

    /**
     * The first {@code columns} titles of {@code recentFirst}, one per title (an app can list a show twice, and two
     * apps the same film). Fewer than {@code columns} titles gives no row.
     */
    static List<Item> pick(List<Item> recentFirst, int columns) {
        List<Item> row = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Item i : recentFirst) {
            if (row.size() == columns) break;
            if (seen.add(i.id) && seen.add("title:" + i.title.trim().toLowerCase(java.util.Locale.ROOT))) row.add(i);
        }
        return row.size() == columns ? row : Collections.emptyList();
    }

    static boolean sameIds(List<Item> a, List<Item> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).id.equals(b.get(i).id)) return false;
        return true;
    }

    /** Position over duration, clamped to 0..1; -1 when either is missing. */
    static float progress(long position, long duration) {
        if (position <= 0 || duration <= 0) return -1f;
        return Math.min(1f, (float) position / duration);
    }

    private void publish(List<Item> row, boolean artworkOnly) {
        shown = row;
        main.post(() -> listener.onShelf(row, artworkOnly));
    }

    // ---- Watch Next ------------------------------------------------------------------------------------------

    /**
     * Every installed app's rows in the system's Continue watching, most recently watched first, without the
     * watchlist (titles saved for later, not started). Needs READ_TV_LISTINGS; without it, or on a TV without the
     * provider, there are none. No selection: a caller without ACCESS_ALL_EPG_DATA gets a SecurityException for
     * one, so the whole table is read and filtered here.
     */
    private List<Item> watchNext() {
        List<Item> items = new ArrayList<>();
        if (ctx.checkSelfPermission("android.permission.READ_TV_LISTINGS") != PackageManager.PERMISSION_GRANTED) {
            return items;
        }
        String[] projection = {
                TvContract.WatchNextPrograms._ID,
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
        };
        PackageManager pm = ctx.getPackageManager();
        Set<String> installed = new HashSet<>(), missing = new HashSet<>();
        List<long[]> order = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, projection,
                null, null, null)) {
            if (c == null) return items;
            while (c.moveToNext()) {
                String pkg = c.getString(1), title = c.getString(2), intent = c.getString(5);
                if (pkg == null || title == null || title.isEmpty() || intent == null) continue;
                if (!c.isNull(7) && c.getInt(7) == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST) continue;
                // Rows outlive an uninstalled app until the provider cleans up.
                if (!installed.contains(pkg)) {
                    if (missing.contains(pkg) || pm.getLaunchIntentForPackage(pkg) == null
                            && pm.getLeanbackLaunchIntentForPackage(pkg) == null) {
                        missing.add(pkg);
                        continue;
                    }
                    installed.add(pkg);
                }
                String art = c.getString(3) != null ? c.getString(3) : c.getString(4);
                if (art == null) continue;
                items.add(new Item("wn:" + c.getLong(0), title, pkg, intent, art, progress(c.getLong(8), c.getLong(9))));
                order.add(new long[]{c.getLong(6), items.size() - 1});
            }
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
        Collections.sort(order, (a, b) -> Long.compare(b[0], a[0]));
        List<Item> sorted = new ArrayList<>();
        for (long[] o : order) sorted.add(items.get((int) o[1]));
        return sorted;
    }

    // ---- Artwork ---------------------------------------------------------------------------------------------

    private static String tileName(Item i) {
        // The progress is in the name so a title watched further gets a fresh bar.
        return "tile-" + Integer.toHexString((i.art + "|" + i.pkg + "|" + Math.round(i.progress * 50)).hashCode()) + ".jpg";
    }

    private void loadArtwork(List<Item> row) {
        boolean changed = false;
        for (Item i : row) {
            if (Thread.currentThread().isInterrupted()) return;
            if (i.tile != null) continue;
            File cached = new File(dir, tileName(i));
            Bitmap tile = cached.isFile() ? BitmapFactory.decodeFile(cached.getPath()) : null;
            if (tile == null) {
                tile = compose(decode(i.art, tileW), appIcon(i.pkg), i.progress);
                if (tile != null) {
                    dir.mkdirs();
                    try (OutputStream out = new FileOutputStream(cached)) {
                        tile.compress(Bitmap.CompressFormat.JPEG, 88, out);
                    } catch (Exception ignored) {
                    }
                }
            }
            if (tile != null) {
                i.tile = tile;
                changed = true;
                publish(row, true);
            }
        }
        if (changed) pruneTiles(row);
    }

    /** Keeps the cache to the tiles in the row and a few spares. */
    private void pruneTiles(List<Item> row) {
        File[] files = dir.listFiles((d, name) -> name.startsWith("tile-"));
        if (files == null || files.length <= columns * 3) return;
        Set<String> keep = new HashSet<>();
        for (Item i : row) keep.add(tileName(i));
        for (File f : files) if (!keep.contains(f.getName())) f.delete();
    }

    private Drawable appIcon(String pkg) {
        try {
            return ctx.getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** The art cropped to the tile, a dark wash at the bottom, the app's icon in a corner and the progress bar. */
    private Bitmap compose(Bitmap art, Drawable icon, float progress) {
        if (art == null) return null;
        Bitmap out = Bitmap.createBitmap(tileW, tileH, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        float scale = Math.max((float) tileW / art.getWidth(), (float) tileH / art.getHeight());
        float w = art.getWidth() * scale, h = art.getHeight() * scale;
        c.drawBitmap(art, null, new RectF((tileW - w) / 2, (tileH - h) / 2, (tileW + w) / 2, (tileH + h) / 2), p);
        art.recycle();
        Paint wash = new Paint();
        wash.setShader(new LinearGradient(0, tileH * 0.55f, 0, tileH, 0x00000000, 0xA0000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, tileW, tileH, wash);
        float pad = tileW * 0.05f;
        if (icon != null) {
            int size = Math.round(tileH * 0.2f);
            int left = Math.round(pad), top = Math.round(pad);
            icon.setBounds(left, top, left + size, top + size);
            icon.draw(c);
        }
        if (progress >= 0f) {
            float barH = Math.max(4f, tileH * 0.025f), y = tileH - pad - barH, r = barH / 2;
            Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
            track.setColor(0x66FFFFFF);
            c.drawRoundRect(new RectF(pad, y, tileW - pad, y + barH), r, r, track);
            Paint done = new Paint(Paint.ANTI_ALIAS_FLAG);
            done.setColor(0xFFFFFFFF);
            float end = pad + (tileW - 2 * pad) * Math.max(progress, 0.03f);
            c.drawRoundRect(new RectF(pad, y, end, y + barH), r, r, done);
        }
        return out;
    }

    /** Decodes an http(s), content or file image, subsampled to about {@code width} pixels wide. */
    private Bitmap decode(String uri, int width) {
        try {
            byte[] bytes;
            Uri u = Uri.parse(uri);
            if ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) {
                bytes = download(uri);
            } else {
                ContentResolver cr = ctx.getContentResolver();
                try (InputStream in = cr.openInputStream(u)) {
                    bytes = in == null ? null : readAll(in);
                }
            }
            if (bytes == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= width) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    // ---- I/O -------------------------------------------------------------------------------------------------

    /** GET with redirects followed (image CDNs redirect); null unless 200. */
    private static byte[] download(String url) throws Exception {
        for (int hop = 0; hop < 4; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_MS);
            conn.setReadTimeout(READ_MS);
            conn.setInstanceFollowRedirects(false);
            try {
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400 && conn.getHeaderField("Location") != null) {
                    url = new URL(new URL(url), conn.getHeaderField("Location")).toString();
                    continue;
                }
                if (code != 200) return null;
                try (InputStream in = conn.getInputStream()) {
                    return readAll(in);
                }
            } finally {
                conn.disconnect();
            }
        }
        return null;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /**
     * The intent a tile sends: an intent: URI as written, anything else (the apps write https links to their own
     * player) as a VIEW of it, held to the app that published the row so a browser never takes it.
     */
    static Intent intentFor(String target, String pkg) {
        Intent i;
        try {
            i = target.startsWith("intent:") ? Intent.parseUri(target, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(target));
        } catch (java.net.URISyntaxException e) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
        }
        if (i.getPackage() == null && i.getComponent() == null) i.setPackage(pkg);
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
