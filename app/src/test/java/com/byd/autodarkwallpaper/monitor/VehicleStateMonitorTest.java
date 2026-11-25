package com.sunr1se.byd.autowallpaper.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.provider.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VehicleStateMonitorTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Settings.Global.putString(context.getContentResolver(),
                VehicleStateMonitor.SETTINGS_GLOBAL_BYD_NIGHT_MODE, null);
    }

    @Test
    public void queryBydDarkStateOrNull_handlesValidAndInvalidValues() {
        assertNull(VehicleStateMonitor.queryBydDarkStateOrNull(null));
        assertNull(VehicleStateMonitor.queryBydDarkStateOrNull(context));

        Settings.Global.putString(context.getContentResolver(),
                VehicleStateMonitor.SETTINGS_GLOBAL_BYD_NIGHT_MODE, "1");
        assertEquals(Boolean.TRUE, VehicleStateMonitor.queryBydDarkStateOrNull(context));

        Settings.Global.putString(context.getContentResolver(),
                VehicleStateMonitor.SETTINGS_GLOBAL_BYD_NIGHT_MODE, " 2 ");
        assertEquals(Boolean.FALSE, VehicleStateMonitor.queryBydDarkStateOrNull(context));

        Settings.Global.putString(context.getContentResolver(),
                VehicleStateMonitor.SETTINGS_GLOBAL_BYD_NIGHT_MODE, "9");
        assertNull(VehicleStateMonitor.queryBydDarkStateOrNull(context));

        Settings.Global.putString(context.getContentResolver(),
                VehicleStateMonitor.SETTINGS_GLOBAL_BYD_NIGHT_MODE, "not_a_number");
        assertNull(VehicleStateMonitor.queryBydDarkStateOrNull(context));
    }

    @Test
    public void readBydNightModeNow_reportsMissingAndValidStates() {
        VehicleStateMonitor.BydDayNightReading missing = VehicleStateMonitor.readBydNightModeNow(context);
        assertFalse(missing.isValid());
        assertNotNull(missing.error);

        Settings.Global.putString(context.getContentResolver(),
                VehicleStateMonitor.SETTINGS_GLOBAL_BYD_NIGHT_MODE, "1");
        VehicleStateMonitor.BydDayNightReading dark = VehicleStateMonitor.readBydNightModeNow(context);
        assertTrue(dark.isValid());
        assertTrue(dark.isDark());
        assertNull(dark.error);

        Settings.Global.putString(context.getContentResolver(),
                VehicleStateMonitor.SETTINGS_GLOBAL_BYD_NIGHT_MODE, "2");
        VehicleStateMonitor.BydDayNightReading light = VehicleStateMonitor.readBydNightModeNow(context);
        assertTrue(light.isValid());
        assertFalse(light.isDark());
        assertNull(light.error);

        Settings.Global.putString(context.getContentResolver(),
                VehicleStateMonitor.SETTINGS_GLOBAL_BYD_NIGHT_MODE, "3");
        VehicleStateMonitor.BydDayNightReading invalid = VehicleStateMonitor.readBydNightModeNow(context);
        assertFalse(invalid.isValid());
        assertNotNull(invalid.error);
    }

    @Test
    public void evaluateSnapshot_coversAllBranches() {
        VehicleStateMonitor.EvaluationResult nullRes = VehicleStateMonitor.evaluateSnapshot(null);
        assertFalse(nullRes.valid);

        VehicleStateMonitor.TelemetrySnapshot errSnap = new VehicleStateMonitor.TelemetrySnapshot();
        errSnap.bydNightModeError = "missing key";
        VehicleStateMonitor.EvaluationResult errRes = VehicleStateMonitor.evaluateSnapshot(errSnap);
        assertFalse(errRes.valid);
        assertEquals("missing key", errRes.errorDetail);

        VehicleStateMonitor.TelemetrySnapshot darkSnap = new VehicleStateMonitor.TelemetrySnapshot();
        darkSnap.bydNightMode = VehicleStateMonitor.BYD_NIGHT_MODE_DARK;
        darkSnap.bydWallpaperMode = VehicleStateMonitor.BYD_WALLPAPER_MODE_AUTO;
        VehicleStateMonitor.EvaluationResult darkRes = VehicleStateMonitor.evaluateSnapshot(darkSnap);
        assertTrue(darkRes.valid);
        assertTrue(darkRes.targetDark);
        assertTrue(darkRes.reason.contains("自动"));

        VehicleStateMonitor.TelemetrySnapshot lightSnap = new VehicleStateMonitor.TelemetrySnapshot();
        lightSnap.bydNightMode = VehicleStateMonitor.BYD_NIGHT_MODE_LIGHT;
        lightSnap.bydWallpaperMode = VehicleStateMonitor.BYD_WALLPAPER_MODE_DAY;
        VehicleStateMonitor.EvaluationResult lightRes = VehicleStateMonitor.evaluateSnapshot(lightSnap);
        assertTrue(lightRes.valid);
        assertFalse(lightRes.targetDark);
        assertTrue(lightRes.reason.contains("手动日间"));

        VehicleStateMonitor.TelemetrySnapshot unsetSnap = new VehicleStateMonitor.TelemetrySnapshot();
        VehicleStateMonitor.EvaluationResult unsetRes = VehicleStateMonitor.evaluateSnapshot(unsetSnap);
        assertFalse(unsetRes.valid);
    }

    @Test
    public void formatters_returnReadableStrings() {
        assertEquals("1 (夜间/深色)", VehicleStateMonitor.formatBydNightMode(1, "1"));
        assertEquals("2 (日间/浅色)", VehicleStateMonitor.formatBydNightMode(2, "2"));
        assertEquals("未读取/不存在", VehicleStateMonitor.formatBydNightMode(VehicleStateMonitor.UNSET_VALUE, null));
        assertEquals("9 (未识别)", VehicleStateMonitor.formatBydNightMode(9, "9"));

        assertEquals("手动夜间", VehicleStateMonitor.formatBydWallpaperModeShort(1));
        assertEquals("手动日间", VehicleStateMonitor.formatBydWallpaperModeShort(2));
        assertEquals("自动", VehicleStateMonitor.formatBydWallpaperModeShort(3));
        assertNull(VehicleStateMonitor.formatBydWallpaperModeShort(99));

        assertTrue(VehicleStateMonitor.formatSystemNightMask(Configuration.UI_MODE_NIGHT_YES).contains("NIGHT_YES"));
        assertTrue(VehicleStateMonitor.formatSystemNightMask(Configuration.UI_MODE_NIGHT_NO).contains("NIGHT_NO"));
    }
}
