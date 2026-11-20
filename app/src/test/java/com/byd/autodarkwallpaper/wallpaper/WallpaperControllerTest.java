package com.byd.autodarkwallpaper.wallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
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
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WallpaperControllerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Application app;
    private AppPrefs prefs;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(AppPrefs.PREFS_NAME, Application.MODE_PRIVATE).edit().clear().commit();
        deleteRecursively(WallpaperRepository.getRootDir(app));
        WallpaperController.resetDedupState();
        prefs = new AppPrefs(app);
    }

    @Test
    public void decodeSampledBitmap_returnsNullForMissingAndDecodesValidFile() throws IOException {
        assertNull(WallpaperController.decodeSampledBitmap(null, 480, 300));
        assertNull(WallpaperController.decodeSampledBitmap(new File(tempFolder.getRoot(), "missing.jpg"), 480, 300));

        File img = tempFolder.newFile("sample.jpg");
        writeJpeg(img, 1600, 1000, Color.MAGENTA);

        Bitmap decoded = WallpaperController.decodeSampledBitmap(img, 400, 250);
        assertNotNull(decoded);
        assertTrue(decoded.getWidth() <= 800);
        assertTrue(decoded.getHeight() <= 500);
        decoded.recycle();
    }

    @Test
    public void applyWallpaper_whenLibraryEmpty_notifiesErrorStatus() {
        AtomicReference<String> statusText = new AtomicReference<>();
        AtomicBoolean statusError = new AtomicBoolean(false);
        WallpaperController.ApplyStatusListener listener = (text, isError) -> {
            statusText.set(text);
            statusError.set(isError);
        };

        WallpaperController.addApplyStatusListener(listener);
        try {
            WallpaperController.applyWallpaper(app, true, "测试切换", false, false);
            ShadowLooper.idleMainLooper();

            assertTrue(statusError.get());
            assertNotNull(statusText.get());
            assertTrue(statusText.get().contains("夜间"));
        } finally {
            WallpaperController.removeApplyStatusListener(listener);
        }
    }

    @Test
    public void applyWallpaper_liveMode_broadcastsAndDeduplicatesUntilReset() throws Exception {
        File img = tempFolder.newFile("live_day.jpg");
        writeJpeg(img, 320, 200, Color.YELLOW);
        WallpaperRepository.ImportResult imported =
                WallpaperRepository.importWallpapersFromUris(app, Collections.singletonList(Uri.fromFile(img)), false);
        String itemId = imported.importedItems.get(0).id;

        prefs.setWallpaperMode(AppPrefs.MODE_LIVE_SERVICE);
        ShadowApplication shadowApp = Shadow.extract(app);
        shadowApp.clearBroadcastIntents();

        // 首次应用：应发送广播
        WallpaperController.applyWallpaper(app, false, "测试", false, false);
        List<Intent> broadcasts = shadowApp.getBroadcastIntents();
        assertEquals(1, broadcasts.size());
        assertEquals(WallpaperController.ACTION_WALLPAPER_STATE_CHANGED, broadcasts.get(0).getAction());
        assertEquals(itemId, broadcasts.get(0).getStringExtra(WallpaperController.EXTRA_ITEM_ID));
        assertEquals(AppPrefs.TARGET_STATE_LIGHT, prefs.getCurrentTargetState());

        // 同状态再次应用（非强制）：应被去重跳过
        WallpaperController.applyWallpaper(app, false, "测试重复", false, false);
        assertEquals(1, shadowApp.getBroadcastIntents().size());

        // 重置去重状态后再次应用：应再次发送广播
        WallpaperController.resetDedupState();
        WallpaperController.applyWallpaper(app, false, "重置后", false, false);
        assertEquals(2, shadowApp.getBroadcastIntents().size());
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
