package com.byd.autodarkwallpaper.wallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;

import com.byd.autodarkwallpaper.config.AppPrefs;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WallpaperRepositoryTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Context context;
    private AppPrefs prefs;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(AppPrefs.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit();
        deleteRecursively(WallpaperRepository.getRootDir(context));
        prefs = new AppPrefs(context);
    }

    @Test
    public void emptyLibrary_returnsEmptyAndNullActive() {
        assertTrue(WallpaperRepository.listWallpapers(context, false).isEmpty());
        assertTrue(WallpaperRepository.listWallpapers(context, true).isEmpty());
        assertNull(WallpaperRepository.getActiveItem(context, false));
        assertNull(WallpaperRepository.resolveItemForApply(context, false, false));
    }

    @Test
    public void importListDelete_updatesPrefsAndFiles() throws Exception {
        Uri img1 = createTempImageUri("day_one.jpg", 320, 200, Color.BLUE);
        Uri img2 = createTempImageUri("day_two.jpg", 400, 240, Color.GREEN);

        WallpaperRepository.ImportResult res =
                WallpaperRepository.importWallpapersFromUris(context, Arrays.asList(img1, img2), false);

        assertEquals(2, res.importedItems.size());
        assertTrue(res.errorMessages.isEmpty());

        List<WallpaperRepository.WallpaperItem> items = WallpaperRepository.listWallpapers(context, false);
        assertEquals(2, items.size());

        String firstId = items.get(0).id;
        String secondId = items.get(1).id;
        assertEquals(firstId, prefs.getSelectedWallpaperId(false));
        assertEquals(firstId, prefs.getActiveWallpaperId(false));

        assertTrue(WallpaperRepository.deleteWallpaper(context, false, firstId));
        assertEquals(1, WallpaperRepository.listWallpapers(context, false).size());
        assertEquals(secondId, prefs.getSelectedWallpaperId(false));
        assertEquals(secondId, prefs.getActiveWallpaperId(false));
        assertFalse(WallpaperRepository.getWallpaperFile(context, false, firstId).exists());

        assertTrue(WallpaperRepository.deleteWallpaper(context, false, secondId));
        assertTrue(WallpaperRepository.listWallpapers(context, false).isEmpty());
        assertNull(prefs.getSelectedWallpaperId(false));
        assertNull(prefs.getActiveWallpaperId(false));
    }

    @Test
    public void resolveItemForApply_respectsFixedAndRandomModes() throws Exception {
        Uri img1 = createTempImageUri("night_1.jpg", 320, 200, Color.BLACK);
        Uri img2 = createTempImageUri("night_2.jpg", 320, 200, Color.DKGRAY);

        WallpaperRepository.ImportResult res =
                WallpaperRepository.importWallpapersFromUris(context, Arrays.asList(img1, img2), true);
        String id1 = res.importedItems.get(0).id;
        String id2 = res.importedItems.get(1).id;

        // 固定模式：应始终返回指定项
        prefs.setSelectionMode(true, AppPrefs.SELECT_FIXED);
        prefs.setSelectedWallpaperId(true, id2);
        WallpaperRepository.WallpaperItem fixed =
                WallpaperRepository.resolveItemForApply(context, true, true);
        assertNotNull(fixed);
        assertEquals(id2, fixed.id);
        assertEquals(id2, prefs.getActiveWallpaperId(true));

        // 随机模式：allowRandomRotate=false 保持当前项
        prefs.setSelectionMode(true, AppPrefs.SELECT_RANDOM);
        WallpaperRepository.WallpaperItem keep =
                WallpaperRepository.resolveItemForApply(context, true, false);
        assertNotNull(keep);
        assertEquals(id2, keep.id);

        // 随机模式：allowRandomRotate=true 在两张图时必须切换到另一张，避免连续重复
        WallpaperRepository.WallpaperItem rotated =
                WallpaperRepository.resolveItemForApply(context, true, true);
        assertNotNull(rotated);
        assertEquals(id1, rotated.id);
        assertNotEquals(id2, rotated.id);
        assertEquals(id1, prefs.getActiveWallpaperId(true));
    }

    @Test
    public void listWallpapers_cleansUpMissingDiskFiles() throws Exception {
        Uri img = createTempImageUri("orphan.jpg", 200, 120, Color.RED);
        WallpaperRepository.ImportResult res =
                WallpaperRepository.importWallpapersFromUris(context, Collections.singletonList(img), false);
        String id = res.importedItems.get(0).id;

        File diskFile = WallpaperRepository.getWallpaperFile(context, false, id);
        assertTrue(diskFile.exists());
        assertTrue(diskFile.delete());

        List<WallpaperRepository.WallpaperItem> listed = WallpaperRepository.listWallpapers(context, false);
        assertTrue(listed.isEmpty());
    }

    @Test
    public void migrateLegacyCustomWallpapers_importsLegacyFilesOnce() throws Exception {
        File root = WallpaperRepository.getRootDir(context);
        File oldLight = new File(root, "light.jpg");
        writeJpeg(oldLight, 300, 180, Color.CYAN);

        context.getSharedPreferences(AppPrefs.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("light_is_custom", true)
                .commit();

        WallpaperRepository.migrateLegacyCustomWallpapersIfNeeded(context);

        List<WallpaperRepository.WallpaperItem> dayItems = WallpaperRepository.listWallpapers(context, false);
        assertEquals(1, dayItems.size());
        assertEquals("日间壁纸", dayItems.get(0).displayName);
        assertEquals(dayItems.get(0).id, prefs.getSelectedWallpaperId(false));
    }

    private Uri createTempImageUri(String name, int width, int height, int color) throws IOException {
        File file = tempFolder.newFile(name);
        writeJpeg(file, width, height, color);
        return Uri.fromFile(file);
    }

    private void writeJpeg(File file, int width, int height, int color) throws IOException {
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bmp.eraseColor(color);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            assertTrue(bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos));
        } finally {
            bmp.recycle();
        }
    }

    private void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        f.delete();
    }
}
