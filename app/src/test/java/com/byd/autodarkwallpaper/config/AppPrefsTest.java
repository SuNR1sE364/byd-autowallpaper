package com.byd.autodarkwallpaper.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AppPrefsTest {

    private AppPrefs prefs;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(AppPrefs.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new AppPrefs(context);
    }

    @Test
    public void defaultValues_areExpected() {
        assertFalse(prefs.isEnabled());
        assertEquals(AppPrefs.MODE_STATIC_MANAGER, prefs.getWallpaperMode());
        assertFalse(prefs.isDebugModeEnabled());
        assertEquals(AppPrefs.SELECT_FIXED, prefs.getSelectionMode(false));
        assertEquals(AppPrefs.SELECT_FIXED, prefs.getSelectionMode(true));
        assertNull(prefs.getSelectedWallpaperId(false));
        assertNull(prefs.getActiveWallpaperId(true));
        assertFalse(prefs.hasOriginalBackup());
        assertNull(prefs.getOriginalBackupMeta());
        assertEquals(AppPrefs.TARGET_STATE_UNSET, prefs.getCurrentTargetState());
        assertNull(prefs.getLogCustomDirPath());
        assertNull(prefs.getLogSafTreeUri());
    }

    @Test
    public void wallpaperMode_normalizesInvalidValuesToStatic() {
        prefs.setWallpaperMode(AppPrefs.MODE_LIVE_SERVICE);
        assertEquals(AppPrefs.MODE_LIVE_SERVICE, prefs.getWallpaperMode());
        assertEquals("动态壁纸模式", AppPrefs.formatWallpaperMode(AppPrefs.MODE_LIVE_SERVICE));

        prefs.setWallpaperMode(99);
        assertEquals(AppPrefs.MODE_STATIC_MANAGER, prefs.getWallpaperMode());
        assertEquals("直接修改壁纸", AppPrefs.formatWallpaperMode(99));
    }

    @Test
    public void selectionMode_isIndependentForDayAndNight() {
        prefs.setSelectionMode(false, AppPrefs.SELECT_RANDOM);
        prefs.setSelectionMode(true, AppPrefs.SELECT_FIXED);

        assertEquals(AppPrefs.SELECT_RANDOM, prefs.getSelectionMode(false));
        assertEquals(AppPrefs.SELECT_FIXED, prefs.getSelectionMode(true));

        prefs.setSelectionMode(false, 42);
        assertEquals(AppPrefs.SELECT_FIXED, prefs.getSelectionMode(false));
    }

    @Test
    public void stringFields_trimAndClearOnBlank() {
        prefs.setSelectedWallpaperId(false, "  wp_1  ");
        assertEquals("wp_1", prefs.getSelectedWallpaperId(false));
        prefs.setSelectedWallpaperId(false, "   ");
        assertNull(prefs.getSelectedWallpaperId(false));

        prefs.setActiveWallpaperId(true, " wp_2 ");
        assertEquals("wp_2", prefs.getActiveWallpaperId(true));
        prefs.setActiveWallpaperId(true, null);
        assertNull(prefs.getActiveWallpaperId(true));

        prefs.setOriginalBackupMeta(" 1920x1200 ");
        assertEquals("1920x1200", prefs.getOriginalBackupMeta());
        prefs.setOriginalBackupMeta("");
        assertNull(prefs.getOriginalBackupMeta());

        prefs.setLogCustomDirPath(" /sdcard/Download/Logs ");
        assertEquals("/sdcard/Download/Logs", prefs.getLogCustomDirPath());
        prefs.setLogCustomDirPath(" ");
        assertNull(prefs.getLogCustomDirPath());

        prefs.setLogSafTreeUri(" content://tree/123 ");
        assertEquals("content://tree/123", prefs.getLogSafTreeUri());
        prefs.setLogSafTreeUri(null);
        assertNull(prefs.getLogSafTreeUri());
    }

    @Test
    public void targetStateAndFlags_roundTrip() {
        prefs.setEnabled(true);
        prefs.setDebugModeEnabled(true);
        prefs.setHasOriginalBackup(true);
        prefs.setCurrentTargetState(true);

        assertTrue(prefs.isEnabled());
        assertTrue(prefs.isDebugModeEnabled());
        assertTrue(prefs.hasOriginalBackup());
        assertEquals(AppPrefs.TARGET_STATE_DARK, prefs.getCurrentTargetState());

        prefs.setCurrentTargetState(false);
        assertEquals(AppPrefs.TARGET_STATE_LIGHT, prefs.getCurrentTargetState());
    }
}
