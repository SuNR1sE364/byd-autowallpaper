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
import java.nio.charset.StandardCharsets;
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

    @Test
    public void legacyLibraryJson_defaultsToImageTypeAndJpgFile() throws Exception {
        File dayDir = new File(WallpaperRepository.getRootDir(context), "day");
        assertTrue(dayDir.mkdirs() || dayDir.exists());
        writeJpeg(new File(dayDir, "legacy_1.jpg"), 320, 200, Color.GREEN);

        String legacyJson = "{\n"
                + "  \"day\": [{\n"
                + "    \"id\": \"legacy_1\",\n"
                + "    \"displayName\": \"旧版壁纸\",\n"
                + "    \"width\": 320,\n"
                + "    \"height\": 200,\n"
                + "    \"sizeBytes\": 1024,\n"
                + "    \"addedAt\": 123456\n"
                + "  }],\n"
                + "  \"night\": []\n"
                + "}";
        File libFile = new File(WallpaperRepository.getRootDir(context), "library.json");
        try (FileOutputStream fos = new FileOutputStream(libFile)) {
            fos.write(legacyJson.getBytes(StandardCharsets.UTF_8));
        }

        List<WallpaperRepository.WallpaperItem> items = WallpaperRepository.listWallpapers(context, false);
        assertEquals(1, items.size());
        WallpaperRepository.WallpaperItem item = items.get(0);
        assertFalse(item.isVideo());
        assertEquals(WallpaperRepository.WallpaperItem.MEDIA_TYPE_IMAGE, item.mediaType);
        assertEquals("legacy_1.jpg", item.fileName);
    }

    @Test
    public void videoItem_filtersInStaticMode_preservesSelection_andDeletesThumb() throws Exception {
        WallpaperRepository.WallpaperItem video = WallpaperRepository.addVideoItemForTest(
                context, false, "测试视频", ".mp4", 1920, 1080, 18_500L, 60f, 0);

        File videoFile = WallpaperRepository.getWallpaperFile(context, false, video);
        File thumbFile = WallpaperRepository.getThumbnailFile(context, false, video);
        assertNotNull(videoFile);
        assertNotNull(thumbFile);
        assertTrue(videoFile.exists());
        assertTrue(thumbFile.exists());
        assertTrue(video.isVideo());

        // 动态模式列表包含视频，静态模式列表过滤视频
        assertEquals(1, WallpaperRepository.listWallpapers(context, false, true).size());
        assertTrue(WallpaperRepository.listWallpapers(context, false, false).isEmpty());
        assertNull(WallpaperRepository.getActiveItem(context, false, false));

        // 额外导入一张静态图片，并将动态模式固定选中保持在视频上
        Uri imgUri = createTempImageUri("day_img.jpg", 320, 200, Color.BLUE);
        WallpaperRepository.ImportResult imgRes =
                WallpaperRepository.importWallpapersFromUris(context, Collections.singletonList(imgUri), false, true);
        String imgId = imgRes.importedItems.get(0).id;
        prefs.setSelectionMode(false, AppPrefs.SELECT_FIXED);
        prefs.setSelectedWallpaperId(false, video.id);

        // 静态模式解析时应回落到图片，且不覆盖用户动态模式下选中的视频 ID
        WallpaperRepository.WallpaperItem staticApplied =
                WallpaperRepository.resolveItemForApply(context, false, false, false);
        assertNotNull(staticApplied);
        assertEquals(imgId, staticApplied.id);
        assertEquals(video.id, prefs.getSelectedWallpaperId(false));

        // 切回动态模式时无损恢复视频壁纸
        WallpaperRepository.WallpaperItem liveApplied =
                WallpaperRepository.resolveItemForApply(context, false, false, true);
        assertNotNull(liveApplied);
        assertEquals(video.id, liveApplied.id);

        // 删除视频应同时清理主文件与缩略图
        assertTrue(WallpaperRepository.deleteWallpaper(context, false, video.id));
        assertFalse(videoFile.exists());
        assertFalse(thumbFile.exists());
    }

    @Test
    public void importWallpapers_staticModeRejectsVideoUri() throws Exception {
        File mp4 = tempFolder.newFile("sample_clip.mp4");
        try (FileOutputStream fos = new FileOutputStream(mp4)) {
            fos.write(new byte[]{0, 0, 0, 8});
        }
        try {
            WallpaperRepository.importWallpapersFromUris(
                    context, Collections.singletonList(Uri.fromFile(mp4)), false, false);
            org.junit.Assert.fail("静态模式下应拒绝导入视频");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("动态壁纸模式"));
        }
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
