package com.sunr1se.byd.autowallpaper.wallpaper;

import android.Manifest;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.ExifInterface;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import com.sunr1se.byd.autowallpaper.config.AppPrefs;
import com.sunr1se.byd.autowallpaper.util.AppLogger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 日常壁纸库与原系统壁纸备份仓库。
 * 负责日/夜多壁纸管理（图片及视频）、指定或随机选取、旧版自定义壁纸迁移以及直接修改模式下的原壁纸备份/复原。
 */
public final class WallpaperRepository {

    private static final String TAG = "WallpaperRepo";
    private static final int MAX_IMPORT_EDGE = 3840;
    private static final int MAX_VIDEO_THUMB_EDGE = 960;
    private static final String LIBRARY_FILE_NAME = "library.json";
    private static final String BACKUP_FILE_NAME = "original_backup.img";
    private static final Random RANDOM = new Random();
    private static final Set<String> VIDEO_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".mp4", ".m4v", ".mov", ".3gp", ".3gpp", ".mkv", ".webm", ".ts", ".avi", ".flv", ".mpg", ".mpeg"
    ));

    public static final class WallpaperItem {
        public static final String MEDIA_TYPE_IMAGE = "image";
        public static final String MEDIA_TYPE_VIDEO = "video";

        public final String id;
        public final String displayName;
        public final int width;
        public final int height;
        public final long sizeBytes;
        public final long addedAt;
        public final String mediaType;
        public final String fileName;
        public final long durationMs;
        public final float fps;
        public final int rotation;
        public final CropRect cropRect;

        public WallpaperItem(String id, String displayName, int width, int height, long sizeBytes, long addedAt) {
            this(id, displayName, width, height, sizeBytes, addedAt,
                    MEDIA_TYPE_IMAGE, id + ".jpg", 0L, 0f, 0, null);
        }

        public WallpaperItem(String id,
                             String displayName,
                             int width,
                             int height,
                             long sizeBytes,
                             long addedAt,
                             String mediaType,
                             String fileName,
                             long durationMs,
                             float fps,
                             int rotation) {
            this(id, displayName, width, height, sizeBytes, addedAt,
                    mediaType, fileName, durationMs, fps, rotation, null);
        }

        public WallpaperItem(String id,
                             String displayName,
                             int width,
                             int height,
                             long sizeBytes,
                             long addedAt,
                             String mediaType,
                             String fileName,
                             long durationMs,
                             float fps,
                             int rotation,
                             CropRect cropRect) {
            this.id = id;
            this.displayName = displayName;
            this.width = width;
            this.height = height;
            this.sizeBytes = sizeBytes;
            this.addedAt = addedAt;
            this.mediaType = MEDIA_TYPE_VIDEO.equals(mediaType) ? MEDIA_TYPE_VIDEO : MEDIA_TYPE_IMAGE;
            this.fileName = (fileName != null && !fileName.trim().isEmpty()) ? fileName.trim() : (id + ".jpg");
            this.durationMs = Math.max(0L, durationMs);
            this.fps = (Float.isNaN(fps) || Float.isInfinite(fps) || fps < 0f) ? 0f : fps;
            this.rotation = rotation;
            this.cropRect = isVideo() ? null : cropRect;
        }

        public boolean isVideo() {
            return MEDIA_TYPE_VIDEO.equals(mediaType);
        }

        public boolean hasCustomCrop() {
            return !isVideo() && cropRect != null;
        }

        public WallpaperItem withCropRect(CropRect newCropRect) {
            return new WallpaperItem(
                    id, displayName, width, height, sizeBytes, addedAt,
                    mediaType, fileName, durationMs, fps, rotation, newCropRect);
        }
    }

    public static final class VideoInspection {
        public final Uri uri;
        public final String displayName;
        public final int width;
        public final int height;
        public final int rotation;
        public final long durationMs;
        public final float fps;
        public final long sizeBytes;
        public final VideoSpecEvaluator.EvaluationResult specResult;

        public VideoInspection(Uri uri,
                               String displayName,
                               int width,
                               int height,
                               int rotation,
                               long durationMs,
                               float fps,
                               long sizeBytes,
                               VideoSpecEvaluator.EvaluationResult specResult) {
            this.uri = uri;
            this.displayName = displayName;
            this.width = width;
            this.height = height;
            this.rotation = rotation;
            this.durationMs = durationMs;
            this.fps = fps;
            this.sizeBytes = sizeBytes;
            this.specResult = specResult;
        }
    }

    public static final class ImportResult {
        public final List<WallpaperItem> importedItems = new ArrayList<>();
        public final List<String> errorMessages = new ArrayList<>();
    }

    public static final class BackupResult {
        public enum Status {
            SUCCESS,
            PERMISSION_REQUIRED,
            LIVE_WALLPAPER_UNSUPPORTED,
            FAILED
        }

        public final Status status;
        public final String message;
        public final String meta;

        public BackupResult(Status status, String message, String meta) {
            this.status = status;
            this.message = message;
            this.meta = meta;
        }
    }

    private static final class LibraryData {
        final List<WallpaperItem> dayItems = new ArrayList<>();
        final List<WallpaperItem> nightItems = new ArrayList<>();
    }

    private WallpaperRepository() {
    }

    public static File getRootDir(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "wallpapers");
        if (!dir.exists() && !dir.mkdirs()) {
            AppLogger.e(TAG, "创建壁纸根目录失败: " + dir.getAbsolutePath());
        }
        return dir;
    }

    private static File getModeDir(Context context, boolean isDark) {
        File dir = new File(getRootDir(context), isDark ? "night" : "day");
        if (!dir.exists() && !dir.mkdirs()) {
            AppLogger.e(TAG, "创建壁纸分类目录失败: " + dir.getAbsolutePath());
        }
        return dir;
    }

    private static File getLibraryFile(Context context) {
        return new File(getRootDir(context), LIBRARY_FILE_NAME);
    }

    public static File getBackupFile(Context context) {
        return new File(getRootDir(context), BACKUP_FILE_NAME);
    }

    public static File getWallpaperFile(Context context, boolean isDark, WallpaperItem item) {
        if (item == null || item.fileName == null || item.fileName.trim().isEmpty()) {
            return null;
        }
        return new File(getModeDir(context, isDark), item.fileName.trim());
    }

    public static File getWallpaperFile(Context context, boolean isDark, String itemId) {
        if (itemId == null || itemId.trim().isEmpty()) {
            return null;
        }
        String trimmedId = itemId.trim();
        WallpaperItem item = findItemById(context, isDark, trimmedId);
        if (item != null) {
            return getWallpaperFile(context, isDark, item);
        }
        return new File(getModeDir(context, isDark), trimmedId + ".jpg");
    }

    public static File getThumbnailFile(Context context, boolean isDark, WallpaperItem item) {
        if (item == null || item.id == null || item.id.trim().isEmpty()) {
            return null;
        }
        if (item.isVideo()) {
            return new File(getModeDir(context, isDark), item.id.trim() + "_thumb.jpg");
        }
        return getWallpaperFile(context, isDark, item);
    }

    public static synchronized WallpaperItem findItemById(Context context, boolean isDark, String itemId) {
        if (itemId == null || itemId.trim().isEmpty()) {
            return null;
        }
        Context appCtx = context.getApplicationContext();
        LibraryData data = readLibraryLocked(appCtx);
        List<WallpaperItem> list = isDark ? data.nightItems : data.dayItems;
        return findById(list, itemId.trim());
    }

    /**
     * 仅迁移旧版用户自己导入过的自定义壁纸（不生成旧版内置测试壁纸）。
     */
    public static synchronized void migrateLegacyCustomWallpapersIfNeeded(Context context) {
        Context appCtx = context.getApplicationContext();
        File libFile = getLibraryFile(appCtx);
        if (libFile.exists()) {
            return;
        }

        AppPrefs prefs = new AppPrefs(appCtx);
        LibraryData data = new LibraryData();
        boolean migratedAny = false;

        if (prefs.isLightCustom()) {
            File oldLight = new File(getRootDir(appCtx), "light.jpg");
            WallpaperItem item = tryMigrateLegacyFile(appCtx, oldLight, false, "日间壁纸");
            if (item != null) {
                data.dayItems.add(item);
                prefs.setSelectedWallpaperId(false, item.id);
                prefs.setActiveWallpaperId(false, item.id);
                migratedAny = true;
            }
        }
        if (prefs.isDarkCustom()) {
            File oldDark = new File(getRootDir(appCtx), "dark.jpg");
            WallpaperItem item = tryMigrateLegacyFile(appCtx, oldDark, true, "夜间壁纸");
            if (item != null) {
                data.nightItems.add(item);
                prefs.setSelectedWallpaperId(true, item.id);
                prefs.setActiveWallpaperId(true, item.id);
                migratedAny = true;
            }
        }

        if (migratedAny) {
            try {
                writeLibraryLocked(appCtx, data);
                AppLogger.i(TAG, "已迁移旧版自定义壁纸至新版壁纸库");
            } catch (IOException e) {
                AppLogger.e(TAG, "保存迁移后的壁纸库失败", e);
            }
        }
    }

    private static WallpaperItem tryMigrateLegacyFile(Context context, File oldFile, boolean isDark, String name) {
        if (!oldFile.exists() || oldFile.length() == 0L) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(oldFile.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        String id = generateId();
        File dest = getWallpaperFile(context, isDark, id);
        try {
            copyFile(oldFile, dest);
            return new WallpaperItem(id, name, bounds.outWidth, bounds.outHeight,
                    dest.length(), System.currentTimeMillis());
        } catch (IOException e) {
            AppLogger.e(TAG, "迁移旧壁纸文件失败: " + oldFile.getAbsolutePath(), e);
            return null;
        }
    }

    public static synchronized List<WallpaperItem> listWallpapers(Context context, boolean isDark) {
        Context appCtx = context.getApplicationContext();
        LibraryData data = readLibraryLocked(appCtx);
        List<WallpaperItem> raw = isDark ? data.nightItems : data.dayItems;
        List<WallpaperItem> valid = new ArrayList<>();
        boolean dirty = false;
        for (WallpaperItem item : raw) {
            File f = getWallpaperFile(appCtx, isDark, item);
            if (f != null && f.exists() && f.length() > 0L) {
                valid.add(item);
            } else {
                dirty = true;
                AppLogger.w(TAG, "发现缺失壁纸文件，已从列表清理: id=" + item.id);
            }
        }
        if (dirty) {
            raw.clear();
            raw.addAll(valid);
            try {
                writeLibraryLocked(appCtx, data);
            } catch (IOException e) {
                AppLogger.e(TAG, "清理缺失文件记录后写回 library.json 失败", e);
            }
        }
        return new ArrayList<>(valid);
    }

    public static synchronized List<WallpaperItem> listWallpapers(
            Context context, boolean isDark, boolean includeVideos) {
        List<WallpaperItem> all = listWallpapers(context, isDark);
        if (includeVideos) {
            return all;
        }
        return filterOutVideos(all);
    }

    private static List<WallpaperItem> filterOutVideos(List<WallpaperItem> items) {
        List<WallpaperItem> images = new ArrayList<>();
        for (WallpaperItem item : items) {
            if (!item.isVideo()) {
                images.add(item);
            }
        }
        return images;
    }

    public static synchronized ImportResult importWallpapersFromUris(
            Context context, List<Uri> uris, boolean isDark) throws IOException {
        if (uris == null || uris.isEmpty()) {
            throw new IOException("未选择任何壁纸文件");
        }
        Context appCtx = context.getApplicationContext();
        LibraryData data = readLibraryLocked(appCtx);
        List<WallpaperItem> targetList = isDark ? data.nightItems : data.dayItems;
        ImportResult result = new ImportResult();

        for (Uri uri : uris) {
            if (uri == null) {
                continue;
            }
            try {
                WallpaperItem item;
                if (isVideoUri(appCtx, uri)) {
                    item = importVideoFromUri(appCtx, uri, isDark);
                } else {
                    item = decodeAndSaveUri(appCtx, uri, isDark);
                }
                targetList.add(item);
                result.importedItems.add(item);
            } catch (Throwable t) {
                String err = (t.getMessage() != null && !t.getMessage().isEmpty())
                        ? t.getMessage() : t.getClass().getSimpleName();
                AppLogger.e(TAG, "导入壁纸失败: uri=" + uri, t);
                result.errorMessages.add(err);
            }
        }

        if (result.importedItems.isEmpty()) {
            String summary = result.errorMessages.isEmpty()
                    ? "未知错误"
                    : String.join("; ", result.errorMessages);
            throw new IOException("导入壁纸失败: " + summary);
        }

        AppPrefs prefs = new AppPrefs(appCtx);
        String existingSelected = prefs.getSelectedWallpaperId(isDark);
        if (existingSelected == null || findById(targetList, existingSelected) == null) {
            WallpaperItem defaultSelection = findDefaultImportedSelection(
                    result.importedItems, prefs.getWallpaperMode() == AppPrefs.MODE_STATIC_MANAGER);
            prefs.setSelectedWallpaperId(isDark, defaultSelection.id);
            prefs.setActiveWallpaperId(isDark, defaultSelection.id);
        }

        writeLibraryLocked(appCtx, data);
        AppLogger.i(TAG, "导入" + (isDark ? "夜间" : "日间") + "壁纸完成: 成功 "
                + result.importedItems.size() + " 项, 失败 " + result.errorMessages.size() + " 项");
        return result;
    }

    private static WallpaperItem findDefaultImportedSelection(
            List<WallpaperItem> importedItems, boolean preferImage) {
        if (preferImage) {
            for (WallpaperItem item : importedItems) {
                if (!item.isVideo()) {
                    return item;
                }
            }
        }
        return importedItems.get(0);
    }

    public static synchronized boolean deleteWallpaper(Context context, boolean isDark, String itemId)
            throws IOException {
        if (itemId == null || itemId.trim().isEmpty()) {
            return false;
        }
        Context appCtx = context.getApplicationContext();
        LibraryData data = readLibraryLocked(appCtx);
        List<WallpaperItem> targetList = isDark ? data.nightItems : data.dayItems;

        List<WallpaperItem> removedItems = new ArrayList<>();
        for (int i = targetList.size() - 1; i >= 0; i--) {
            WallpaperItem item = targetList.get(i);
            if (itemId.equals(item.id)) {
                removedItems.add(item);
                targetList.remove(i);
            }
        }
        if (removedItems.isEmpty()) {
            return false;
        }

        for (WallpaperItem removedItem : removedItems) {
            File file = getWallpaperFile(appCtx, isDark, removedItem);
            if (file != null && file.exists() && !file.delete()) {
                AppLogger.w(TAG, "删除壁纸磁盘文件失败: " + file.getAbsolutePath());
            }
            if (removedItem.isVideo()) {
                File thumb = getThumbnailFile(appCtx, isDark, removedItem);
                if (thumb != null && thumb.exists() && !thumb.delete()) {
                    AppLogger.w(TAG, "删除视频缩略图失败: " + thumb.getAbsolutePath());
                }
            }
        }

        AppPrefs prefs = new AppPrefs(appCtx);
        String fallbackId = targetList.isEmpty() ? null : targetList.get(0).id;
        if (itemId.equals(prefs.getSelectedWallpaperId(isDark))) {
            prefs.setSelectedWallpaperId(isDark, fallbackId);
        }
        if (itemId.equals(prefs.getActiveWallpaperId(isDark))) {
            prefs.setActiveWallpaperId(isDark, fallbackId);
        }

        writeLibraryLocked(appCtx, data);
        AppLogger.i(TAG, "已删除" + (isDark ? "夜间" : "日间") + "壁纸: id=" + itemId);
        return true;
    }

    /**
     * 更新指定静态图片壁纸的归一化裁剪区域；传入 null 表示恢复默认居中裁剪。
     */
    public static synchronized WallpaperItem updateWallpaperCrop(
            Context context, boolean isDark, String itemId, CropRect cropRect) throws IOException {
        if (itemId == null || itemId.trim().isEmpty()) {
            throw new IOException("更新壁纸取景失败：壁纸 ID 为空");
        }
        Context appCtx = context.getApplicationContext();
        LibraryData data = readLibraryLocked(appCtx);
        List<WallpaperItem> targetList = isDark ? data.nightItems : data.dayItems;

        String trimmedId = itemId.trim();
        for (int i = 0; i < targetList.size(); i++) {
            WallpaperItem current = targetList.get(i);
            if (trimmedId.equals(current.id)) {
                if (current.isVideo()) {
                    throw new IOException("视频壁纸不支持调整静态取景区域: " + current.displayName);
                }
                WallpaperItem updated = current.withCropRect(cropRect);
                targetList.set(i, updated);
                writeLibraryLocked(appCtx, data);
                AppLogger.i(TAG, "已更新" + (isDark ? "夜间" : "日间") + "壁纸取景: id=" + trimmedId
                        + ", cropRect=" + (cropRect != null ? cropRect.toShortString() : "default"));
                return updated;
            }
        }
        throw new IOException("未找到对应壁纸条目: id=" + trimmedId);
    }

    public static synchronized WallpaperItem getActiveItem(Context context, boolean isDark) {
        return getActiveItem(context, isDark, true);
    }

    public static synchronized WallpaperItem getActiveItem(Context context, boolean isDark, boolean allowVideo) {
        Context appCtx = context.getApplicationContext();
        List<WallpaperItem> items = listWallpapers(appCtx, isDark, allowVideo);
        if (items.isEmpty()) {
            return null;
        }
        AppPrefs prefs = new AppPrefs(appCtx);
        WallpaperItem found = findById(items, prefs.getActiveWallpaperId(isDark));
        if (found != null) {
            return found;
        }
        WallpaperItem selected = findById(items, prefs.getSelectedWallpaperId(isDark));
        return selected != null ? selected : items.get(0);
    }

    /**
     * 解析本次应用应当使用的壁纸项：
     * - 指定模式：取选中项（失效则回落到第一项并同步更新配置；静态模式下若原选中为视频，保留原选中ID不被覆盖）。
     * - 随机模式：当 allowRandomRotate 为 true 或尚无有效当前项时随机选取（尽量避免与当前项连续重复）。
     */
    public static synchronized WallpaperItem resolveItemForApply(
            Context context, boolean isDark, boolean allowRandomRotate) {
        return resolveItemForApply(context, isDark, allowRandomRotate, true);
    }

    public static synchronized WallpaperItem resolveItemForApply(
            Context context, boolean isDark, boolean allowRandomRotate, boolean allowVideo) {
        Context appCtx = context.getApplicationContext();
        List<WallpaperItem> allItems = listWallpapers(appCtx, isDark, true);
        List<WallpaperItem> items = allowVideo ? allItems : filterOutVideos(allItems);
        if (items.isEmpty()) {
            return null;
        }
        AppPrefs prefs = new AppPrefs(appCtx);
        int selectionMode = prefs.getSelectionMode(isDark);

        if (selectionMode == AppPrefs.SELECT_FIXED) {
            String selectedId = prefs.getSelectedWallpaperId(isDark);
            WallpaperItem selected = findById(items, selectedId);
            if (selected == null) {
                WallpaperItem existingInAll = findById(allItems, selectedId);
                boolean preservedVideoSelection = !allowVideo && existingInAll != null && existingInAll.isVideo();
                selected = items.get(0);
                if (!preservedVideoSelection) {
                    prefs.setSelectedWallpaperId(isDark, selected.id);
                }
            }
            prefs.setActiveWallpaperId(isDark, selected.id);
            return selected;
        }

        // 随机轮换模式
        String currentActiveId = prefs.getActiveWallpaperId(isDark);
        WallpaperItem currentActive = findById(items, currentActiveId);
        if (!allowRandomRotate && currentActive != null) {
            return currentActive;
        }

        WallpaperItem chosen;
        if (items.size() == 1) {
            chosen = items.get(0);
        } else {
            List<WallpaperItem> candidates = new ArrayList<>();
            for (WallpaperItem item : items) {
                if (!item.id.equals(currentActiveId)) {
                    candidates.add(item);
                }
            }
            if (candidates.isEmpty()) {
                chosen = items.get(RANDOM.nextInt(items.size()));
            } else {
                chosen = candidates.get(RANDOM.nextInt(candidates.size()));
            }
        }
        prefs.setActiveWallpaperId(isDark, chosen.id);
        return chosen;
    }

    public static boolean hasReadWallpaperPermission(Context context) {
        Context appCtx = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return Environment.isExternalStorageManager();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
                    || appCtx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return appCtx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 在开启或切换至“直接修改壁纸”模式前备份系统当前静态壁纸。
     * 若已有有效备份则直接复用，防止将本应用写入的壁纸重复覆盖备份。
     */
    public static synchronized BackupResult backupCurrentSystemWallpaper(Context context) {
        Context appCtx = context.getApplicationContext();
        AppPrefs prefs = new AppPrefs(appCtx);
        File backupFile = getBackupFile(appCtx);

        if (prefs.hasOriginalBackup() && backupFile.exists() && backupFile.length() > 0L) {
            String meta = prefs.getOriginalBackupMeta();
            AppLogger.i(TAG, "检测到已有原壁纸备份，跳过重复备份: " + backupFile.getAbsolutePath());
            return new BackupResult(BackupResult.Status.SUCCESS, "已保留原壁纸备份", meta);
        }

        WallpaperManager wm = WallpaperManager.getInstance(appCtx);
        try {
            WallpaperInfo liveInfo = wm.getWallpaperInfo();
            if (liveInfo != null) {
                String liveName = liveInfo.getPackageName() + "/" + liveInfo.getServiceName();
                AppLogger.w(TAG, "当前系统壁纸为动态壁纸，无法按静态图片备份: " + liveName);
                return new BackupResult(
                        BackupResult.Status.LIVE_WALLPAPER_UNSUPPORTED,
                        "当前系统正在使用动态壁纸（" + liveInfo.getServiceName() + "），无法作为静态图片备份。",
                        null);
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "检查当前系统 WallpaperInfo 失败", t);
        }

        if (!hasReadWallpaperPermission(appCtx)) {
            AppLogger.w(TAG, "缺少读取系统原壁纸所需的存储权限");
            return new BackupResult(
                    BackupResult.Status.PERMISSION_REQUIRED,
                    "需要授予文件访问权限才能读取并备份系统原有壁纸。",
                    null);
        }

        File tempFile = new File(getRootDir(appCtx), BACKUP_FILE_NAME + ".tmp");
        try {
            boolean saved = false;
            int width = 0;
            int height = 0;

            try (ParcelFileDescriptor pfd = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)) {
                if (pfd != null) {
                    try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
                         FileOutputStream fos = new FileOutputStream(tempFile, false)) {
                        byte[] buf = new byte[16384];
                        int len;
                        while ((len = fis.read(buf)) != -1) {
                            fos.write(buf, 0, len);
                        }
                        fos.flush();
                    }
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(tempFile.getAbsolutePath(), bounds);
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                        width = bounds.outWidth;
                        height = bounds.outHeight;
                        saved = true;
                    } else {
                        AppLogger.w(TAG, "WallpaperManager.getWallpaperFile 读出内容无法解析为图片，尝试回退读取 Drawable");
                    }
                }
            } catch (SecurityException se) {
                AppLogger.e(TAG, "WallpaperManager.getWallpaperFile 权限被拒", se);
                cleanupTempFile(tempFile);
                return new BackupResult(
                        BackupResult.Status.PERMISSION_REQUIRED,
                        "读取系统原壁纸权限不足: " + se.getMessage(),
                        null);
            }

            if (!saved) {
                Drawable drawable;
                try {
                    drawable = wm.getDrawable();
                } catch (SecurityException se) {
                    AppLogger.e(TAG, "WallpaperManager.getDrawable 权限被拒", se);
                    cleanupTempFile(tempFile);
                    return new BackupResult(
                            BackupResult.Status.PERMISSION_REQUIRED,
                            "读取系统壁纸 Drawable 权限不足: " + se.getMessage(),
                            null);
                }

                if (drawable == null) {
                    cleanupTempFile(tempFile);
                    String msg = "系统未返回原壁纸数据（可能当前使用车机百变主题或受系统保护）";
                    AppLogger.w(TAG, msg);
                    return new BackupResult(BackupResult.Status.FAILED, msg, null);
                }

                Bitmap bmp = drawableToBitmap(drawable);
                if (bmp == null || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) {
                    cleanupTempFile(tempFile);
                    String msg = "无法从系统壁纸提取有效图像";
                    AppLogger.w(TAG, msg);
                    return new BackupResult(BackupResult.Status.FAILED, msg, null);
                }
                try {
                    width = bmp.getWidth();
                    height = bmp.getHeight();
                    try (FileOutputStream fos = new FileOutputStream(tempFile, false)) {
                        if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                            throw new IOException("Bitmap.compress(PNG) 返回 false");
                        }
                        fos.flush();
                    }
                    saved = true;
                } finally {
                    if (!(drawable instanceof BitmapDrawable)) {
                        bmp.recycle();
                    }
                }
            }

            if (!saved || !tempFile.exists() || tempFile.length() == 0L) {
                cleanupTempFile(tempFile);
                return new BackupResult(BackupResult.Status.FAILED, "备份文件写入为空", null);
            }

            if (backupFile.exists() && !backupFile.delete()) {
                throw new IOException("无法删除旧备份文件: " + backupFile.getAbsolutePath());
            }
            if (!tempFile.renameTo(backupFile)) {
                throw new IOException("无法移动临时备份文件至: " + backupFile.getAbsolutePath());
            }

            String meta = width + "x" + height;
            prefs.setHasOriginalBackup(true);
            prefs.setOriginalBackupMeta(meta);
            AppLogger.i(TAG, "已成功备份用户原壁纸: " + meta + " (" + backupFile.length() + " bytes)");
            return new BackupResult(BackupResult.Status.SUCCESS, "已备份原壁纸 (" + meta + ")", meta);
        } catch (Throwable t) {
            cleanupTempFile(tempFile);
            AppLogger.e(TAG, "备份用户原有壁纸失败", t);
            return new BackupResult(
                    BackupResult.Status.FAILED,
                    t.getClass().getSimpleName() + ": " + t.getMessage(),
                    null);
        }
    }

    public static synchronized void restoreOriginalWallpaper(Context context) throws IOException {
        Context appCtx = context.getApplicationContext();
        AppPrefs prefs = new AppPrefs(appCtx);
        File backupFile = getBackupFile(appCtx);

        if (!prefs.hasOriginalBackup() || !backupFile.exists() || backupFile.length() == 0L) {
            throw new IOException("未找到可用的原壁纸备份文件");
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeFile(backupFile.getAbsolutePath(), opts);
        if (bitmap == null) {
            throw new IOException("原壁纸备份文件损坏，无法解码: " + backupFile.getAbsolutePath());
        }

        try {
            WallpaperManager wm = WallpaperManager.getInstance(appCtx);
            int resultId = wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM);
            AppLogger.i(TAG, "复原原壁纸 WallpaperManager.setBitmap resultId=" + resultId
                    + ", 分辨率=" + bitmap.getWidth() + "x" + bitmap.getHeight());
            if (resultId <= 0) {
                throw new IOException("系统返回无效壁纸 ID=" + resultId + "，复原原壁纸失败");
            }
            if (!backupFile.delete()) {
                AppLogger.w(TAG, "复原后删除原壁纸备份文件失败: " + backupFile.getAbsolutePath());
            }
            prefs.setHasOriginalBackup(false);
            prefs.setOriginalBackupMeta(null);
        } finally {
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    public static boolean isVideoUri(Context context, Uri uri) {
        if (uri == null || context == null) {
            return false;
        }
        try {
            String mime = context.getContentResolver().getType(uri);
            if (mime != null) {
                String lowerMime = mime.toLowerCase(Locale.US);
                if (lowerMime.startsWith("video/")) {
                    return true;
                }
                if (lowerMime.startsWith("image/")) {
                    return false;
                }
            }
        } catch (Throwable t) {
            AppLogger.w(TAG, "读取 URI MIME 类型失败: " + uri + " | " + t.getMessage());
        }
        String rawName = queryRawDisplayName(context, uri);
        if (rawName == null) {
            rawName = uri.getLastPathSegment();
        }
        String ext = extractExtension(rawName);
        return ext != null && VIDEO_EXTENSIONS.contains(ext);
    }

    public static List<VideoInspection> inspectHighSpecVideos(Context context, List<Uri> uris) {
        List<VideoInspection> warnings = new ArrayList<>();
        if (context == null || uris == null || uris.isEmpty()) {
            return warnings;
        }
        Context appCtx = context.getApplicationContext();
        for (Uri uri : uris) {
            if (uri == null || !isVideoUri(appCtx, uri)) {
                continue;
            }
            try {
                long sizeBytes = queryUriSizeBytes(appCtx, uri);
                String displayName = queryDisplayName(appCtx, uri, false);
                VideoMeta meta = readVideoMeta(appCtx, uri, null);
                int dispW = meta.displayWidth();
                int dispH = meta.displayHeight();
                VideoSpecEvaluator.EvaluationResult eval = VideoSpecEvaluator.evaluate(
                        dispW, dispH, meta.fps, meta.durationMs, sizeBytes);
                if (eval.shouldWarn) {
                    warnings.add(new VideoInspection(
                            uri, displayName, dispW, dispH, meta.rotation,
                            meta.durationMs, meta.fps, sizeBytes, eval));
                }
            } catch (Throwable t) {
                AppLogger.e(TAG, "预检视频规格失败: uri=" + uri, t);
            }
        }
        return warnings;
    }

    private static final class VideoMeta {
        final int width;
        final int height;
        final int rotation;
        final long durationMs;
        final float fps;

        VideoMeta(int width, int height, int rotation, long durationMs, float fps) {
            this.width = width;
            this.height = height;
            this.rotation = rotation;
            this.durationMs = durationMs;
            this.fps = fps;
        }

        int displayWidth() {
            return (rotation == 90 || rotation == 270) ? height : width;
        }

        int displayHeight() {
            return (rotation == 90 || rotation == 270) ? width : height;
        }
    }

    private static VideoMeta readVideoMeta(Context context, Uri uri, File localFile) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if (localFile != null) {
                retriever.setDataSource(localFile.getAbsolutePath());
            } else {
                retriever.setDataSource(context, uri);
            }
            int width = parseIntSafe(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 0);
            int height = parseIntSafe(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 0);
            int rotation = parseIntSafe(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
            long durationMs = parseLongSafe(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 0L);

            float fps = extractVideoFpsFromExtractor(context, uri, localFile);
            if (fps <= 0f) {
                fps = parseFloatSafe(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE), 0f);
            }
            if (fps <= 0f && durationMs > 0L) {
                long frameCount = parseLongSafe(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT), 0L);
                if (frameCount > 0L) {
                    fps = frameCount * 1000f / (float) durationMs;
                }
            }
            return new VideoMeta(width, height, rotation, durationMs, fps);
        } catch (Throwable t) {
            throw new IOException("解析视频元数据失败: " + t.getMessage(), t);
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static float extractVideoFpsFromExtractor(Context context, Uri uri, File localFile) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            if (localFile != null) {
                extractor.setDataSource(localFile.getAbsolutePath());
            } else {
                extractor.setDataSource(context, uri, null);
            }
            int trackCount = extractor.getTrackCount();
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    try {
                        float val = format.getFloat(MediaFormat.KEY_FRAME_RATE);
                        if (val > 0f) {
                            return val;
                        }
                    } catch (ClassCastException e) {
                        int intVal = format.getInteger(MediaFormat.KEY_FRAME_RATE);
                        if (intVal > 0) {
                            return (float) intVal;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            AppLogger.w(TAG, "MediaExtractor 读取帧率失败: " + t.getMessage());
        } finally {
            try {
                extractor.release();
            } catch (Throwable ignored) {
            }
        }
        return 0f;
    }

    private static WallpaperItem importVideoFromUri(Context context, Uri uri, boolean isDark) throws IOException {
        String id = generateId();
        String ext = resolveVideoExtension(context, uri);
        String fileName = id + ext;
        File modeDir = getModeDir(context, isDark);
        File targetFile = new File(modeDir, fileName);
        File tempFile = new File(modeDir, fileName + ".tmp");
        File thumbFile = new File(modeDir, id + "_thumb.jpg");

        try {
            long written = 0L;
            try (InputStream is = context.getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(tempFile, false)) {
                if (is == null) {
                    throw new IOException("无法打开视频输入流: " + uri);
                }
                byte[] buf = new byte[65536];
                int len;
                while ((len = is.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                    written += len;
                }
                fos.flush();
            }
            if (written <= 0L) {
                throw new IOException("视频文件内容为空: " + uri);
            }

            VideoMeta meta = readVideoMeta(context, uri, tempFile);
            if (meta.width <= 0 || meta.height <= 0) {
                throw new IOException("无法解析视频分辨率，可能不是有效的视频格式");
            }

            extractAndSaveVideoThumbnail(tempFile, thumbFile);

            if (targetFile.exists() && !targetFile.delete()) {
                throw new IOException("无法覆盖旧视频文件: " + targetFile.getAbsolutePath());
            }
            if (!tempFile.renameTo(targetFile)) {
                throw new IOException("无法重命名临时视频文件: " + targetFile.getAbsolutePath());
            }

            String displayName = queryDisplayName(context, uri, isDark);
            return new WallpaperItem(
                    id,
                    displayName,
                    meta.displayWidth(),
                    meta.displayHeight(),
                    targetFile.length(),
                    System.currentTimeMillis(),
                    WallpaperItem.MEDIA_TYPE_VIDEO,
                    fileName,
                    meta.durationMs,
                    meta.fps,
                    meta.rotation);
        } catch (Throwable t) {
            cleanupTempFile(tempFile);
            cleanupTempFile(thumbFile);
            if (t instanceof IOException) {
                throw (IOException) t;
            }
            throw new IOException("导入视频失败: " + t.getMessage(), t);
        }
    }

    private static void extractAndSaveVideoThumbnail(File videoFile, File thumbFile) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap frame = null;
        Bitmap scaled = null;
        try {
            retriever.setDataSource(videoFile.getAbsolutePath());
            frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                frame = retriever.getFrameAtTime(-1L);
            }
            if (frame == null) {
                throw new IOException("无法提取视频首帧画面，该视频编码可能不受车机支持");
            }
            int fw = frame.getWidth();
            int fh = frame.getHeight();
            int maxEdge = Math.max(fw, fh);
            if (maxEdge > MAX_VIDEO_THUMB_EDGE && fw > 0 && fh > 0) {
                float scale = (float) MAX_VIDEO_THUMB_EDGE / (float) maxEdge;
                int tw = Math.max(1, Math.round(fw * scale));
                int th = Math.max(1, Math.round(fh * scale));
                scaled = Bitmap.createScaledBitmap(frame, tw, th, true);
                saveBitmapAsJpeg(scaled, thumbFile, 90);
            } else {
                saveBitmapAsJpeg(frame, thumbFile, 90);
            }
        } finally {
            if (scaled != null && scaled != frame && !scaled.isRecycled()) {
                scaled.recycle();
            }
            if (frame != null && !frame.isRecycled()) {
                frame.recycle();
            }
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static WallpaperItem decodeAndSaveUri(Context context, Uri uri, boolean isDark) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) {
                throw new IOException("无法打开图片输入流: " + uri);
            }
            BitmapFactory.decodeStream(is, null, bounds);
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("无法解析图片尺寸，可能不是有效的图片格式");
        }

        int orientation = readExifOrientation(context, uri);
        int sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_IMPORT_EDGE, MAX_IMPORT_EDGE);
        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
        decodeOpts.inSampleSize = sampleSize;
        decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap rawBitmap;
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) {
                throw new IOException("二次读取图片输入流失败: " + uri);
            }
            rawBitmap = BitmapFactory.decodeStream(is, null, decodeOpts);
        }

        if (rawBitmap == null) {
            throw new IOException("图片解码返回 null");
        }

        Bitmap finalBitmap = applyExifOrientation(rawBitmap, orientation);
        String id = generateId();
        File targetFile = getWallpaperFile(context, isDark, id);
        try {
            saveBitmapAsJpeg(finalBitmap, targetFile, 94);
            String displayName = queryDisplayName(context, uri, isDark);
            return new WallpaperItem(
                    id,
                    displayName,
                    finalBitmap.getWidth(),
                    finalBitmap.getHeight(),
                    targetFile.length(),
                    System.currentTimeMillis());
        } finally {
            if (finalBitmap != rawBitmap && !rawBitmap.isRecycled()) {
                rawBitmap.recycle();
            }
            if (!finalBitmap.isRecycled()) {
                finalBitmap.recycle();
            }
        }
    }

    private static int readExifOrientation(Context context, Uri uri) {
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) {
                return ExifInterface.ORIENTATION_NORMAL;
            }
            ExifInterface exif = new ExifInterface(is);
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Throwable t) {
            AppLogger.w(TAG, "读取图片 EXIF 方向信息失败，将按默认方向导入: " + t.getMessage());
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Bitmap applyExifOrientation(Bitmap src, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270f);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.postScale(1f, -1f);
                break;
            default:
                return src;
        }
        Bitmap rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
        return rotated != null ? rotated : src;
    }

    private static String queryRawDisplayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return cursor.getString(idx);
                }
            }
        } catch (Throwable t) {
            AppLogger.w(TAG, "读取文件显示名称失败: " + t.getMessage());
        }
        return null;
    }

    private static String queryDisplayName(Context context, Uri uri, boolean isDark) {
        String name = queryRawDisplayName(context, uri);
        if (name == null || name.trim().isEmpty()) {
            return isDark ? "夜间壁纸" : "日间壁纸";
        }
        String trimmed = name.trim();
        int dot = trimmed.lastIndexOf('.');
        return (dot > 0) ? trimmed.substring(0, dot) : trimmed;
    }

    private static long queryUriSizeBytes(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !cursor.isNull(idx)) {
                    long size = cursor.getLong(idx);
                    if (size > 0L) {
                        return size;
                    }
                }
            }
        } catch (Throwable t) {
            AppLogger.w(TAG, "读取文件大小失败: " + t.getMessage());
        }
        try (AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (afd != null && afd.getLength() > 0L) {
                return afd.getLength();
            }
        } catch (Throwable t) {
            AppLogger.w(TAG, "通过 AssetFileDescriptor 读取文件大小失败: " + t.getMessage());
        }
        return 0L;
    }

    private static String resolveVideoExtension(Context context, Uri uri) {
        String rawName = queryRawDisplayName(context, uri);
        String ext = extractExtension(rawName);
        if (ext != null && VIDEO_EXTENSIONS.contains(ext)) {
            return ext;
        }
        try {
            String mime = context.getContentResolver().getType(uri);
            if (mime != null) {
                String fromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime.toLowerCase(Locale.US));
                if (fromMime != null && !fromMime.trim().isEmpty()) {
                    String candidate = "." + fromMime.trim().toLowerCase(Locale.US);
                    if (VIDEO_EXTENSIONS.contains(candidate)) {
                        return candidate;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return ".mp4";
    }

    private static String extractExtension(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        int dot = trimmed.lastIndexOf('.');
        if (dot <= 0 || dot >= trimmed.length() - 1) {
            return null;
        }
        String ext = trimmed.substring(dot).toLowerCase(Locale.US);
        if (ext.length() > 8) {
            return null;
        }
        return ext;
    }

    private static int parseIntSafe(String raw, int fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLongSafe(String raw, long fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float parseFloatSafe(String raw, float fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            Bitmap bmp = ((BitmapDrawable) drawable).getBitmap();
            if (bmp != null) {
                return bmp;
            }
        }
        int width = Math.max(1, drawable.getIntrinsicWidth());
        int height = Math.max(1, drawable.getIntrinsicHeight());
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    private static LibraryData readLibraryLocked(Context context) {
        LibraryData data = new LibraryData();
        File file = getLibraryFile(context);
        if (!file.exists() || file.length() == 0L) {
            return data;
        }
        try {
            String jsonStr = readTextFile(file);
            JSONObject root = new JSONObject(jsonStr);
            parseItemsArray(root.optJSONArray("day"), data.dayItems);
            parseItemsArray(root.optJSONArray("night"), data.nightItems);
        } catch (Throwable t) {
            AppLogger.e(TAG, "读取壁纸库 library.json 失败: " + file.getAbsolutePath(), t);
        }
        return data;
    }

    private static void parseItemsArray(JSONArray arr, List<WallpaperItem> out) {
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) {
                continue;
            }
            String id = obj.optString("id", "");
            if (id.isEmpty()) {
                continue;
            }
            out.add(new WallpaperItem(
                    id,
                    obj.optString("displayName", "壁纸"),
                    obj.optInt("width", 0),
                    obj.optInt("height", 0),
                    obj.optLong("sizeBytes", 0L),
                    obj.optLong("addedAt", 0L),
                    obj.optString("mediaType", WallpaperItem.MEDIA_TYPE_IMAGE),
                    obj.optString("fileName", id + ".jpg"),
                    obj.optLong("durationMs", 0L),
                    (float) obj.optDouble("fps", 0.0),
                    obj.optInt("rotation", 0),
                    CropRect.fromJsonObject(obj.optJSONObject("cropRect"))));
        }
    }

    private static void writeLibraryLocked(Context context, LibraryData data) throws IOException {
        JSONObject root = new JSONObject();
        try {
            root.put("day", toJsonArray(data.dayItems));
            root.put("night", toJsonArray(data.nightItems));
        } catch (JSONException e) {
            throw new IOException("序列化壁纸库 JSON 失败", e);
        }

        File target = getLibraryFile(context);
        File tmp = new File(target.getParentFile(), LIBRARY_FILE_NAME + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp, false)) {
            fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (JSONException e) {
            throw new IOException("格式化壁纸库 JSON 失败", e);
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("无法替换 library.json: " + target.getAbsolutePath());
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("无法重命名 library.json.tmp: " + target.getAbsolutePath());
        }
    }

    private static JSONArray toJsonArray(List<WallpaperItem> items) throws JSONException {
        JSONArray arr = new JSONArray();
        for (WallpaperItem item : items) {
            JSONObject obj = new JSONObject();
            obj.put("id", item.id);
            obj.put("displayName", item.displayName);
            obj.put("width", item.width);
            obj.put("height", item.height);
            obj.put("sizeBytes", item.sizeBytes);
            obj.put("addedAt", item.addedAt);
            obj.put("mediaType", item.mediaType);
            obj.put("fileName", item.fileName);
            obj.put("durationMs", item.durationMs);
            double safeFps = (Float.isNaN(item.fps) || Float.isInfinite(item.fps)) ? 0.0 : item.fps;
            obj.put("fps", safeFps);
            obj.put("rotation", item.rotation);
            if (item.hasCustomCrop()) {
                obj.put("cropRect", item.cropRect.toJsonObject());
            }
            arr.put(obj);
        }
        return arr;
    }

    static synchronized WallpaperItem addVideoItemForTest(Context context,
                                                           boolean isDark,
                                                           String displayName,
                                                           String ext,
                                                           int width,
                                                           int height,
                                                           long durationMs,
                                                           float fps,
                                                           int rotation) throws IOException {
        Context appCtx = context.getApplicationContext();
        String id = generateId();
        String safeExt = (ext != null && ext.startsWith(".")) ? ext : ".mp4";
        String fileName = id + safeExt;
        File modeDir = getModeDir(appCtx, isDark);
        File videoFile = new File(modeDir, fileName);
        try (FileOutputStream fos = new FileOutputStream(videoFile, false)) {
            fos.write(new byte[]{0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p'});
            fos.flush();
        }
        Bitmap thumb = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        try {
            saveBitmapAsJpeg(thumb, new File(modeDir, id + "_thumb.jpg"), 90);
        } finally {
            thumb.recycle();
        }
        WallpaperItem item = new WallpaperItem(
                id,
                displayName,
                width,
                height,
                videoFile.length(),
                System.currentTimeMillis(),
                WallpaperItem.MEDIA_TYPE_VIDEO,
                fileName,
                durationMs,
                fps,
                rotation);
        LibraryData data = readLibraryLocked(appCtx);
        List<WallpaperItem> targetList = isDark ? data.nightItems : data.dayItems;
        targetList.add(item);
        AppPrefs prefs = new AppPrefs(appCtx);
        if (prefs.getSelectedWallpaperId(isDark) == null) {
            prefs.setSelectedWallpaperId(isDark, item.id);
            prefs.setActiveWallpaperId(isDark, item.id);
        }
        writeLibraryLocked(appCtx, data);
        return item;
    }

    private static String readTextFile(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = fis.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static WallpaperItem findById(List<WallpaperItem> items, String id) {
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        for (WallpaperItem item : items) {
            if (id.equals(item.id)) {
                return item;
            }
        }
        return null;
    }

    private static String generateId() {
        return "wp_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 6);
    }

    private static void saveBitmapAsJpeg(Bitmap bitmap, File targetFile, int quality) throws IOException {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建壁纸目录: " + parent.getAbsolutePath());
        }
        File tempFile = new File(parent, targetFile.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tempFile, false)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos)) {
                throw new IOException("Bitmap.compress 返回 false: " + targetFile.getAbsolutePath());
            }
            fos.flush();
        }
        if (targetFile.exists() && !targetFile.delete()) {
            throw new IOException("无法覆盖旧壁纸文件: " + targetFile.getAbsolutePath());
        }
        if (!tempFile.renameTo(targetFile)) {
            throw new IOException("无法重命名临时壁纸文件: " + targetFile.getAbsolutePath());
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent.getAbsolutePath());
        }
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst, false)) {
            byte[] buf = new byte[16384];
            int len;
            while ((len = fis.read(buf)) != -1) {
                fos.write(buf, 0, len);
            }
            fos.flush();
        }
    }

    private static void cleanupTempFile(File file) {
        if (file != null && file.exists() && !file.delete()) {
            AppLogger.w(TAG, "清理临时文件失败: " + file.getAbsolutePath());
        }
    }

    private static int calculateInSampleSize(int width, int height, int reqWidth, int reqHeight) {
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }
}
