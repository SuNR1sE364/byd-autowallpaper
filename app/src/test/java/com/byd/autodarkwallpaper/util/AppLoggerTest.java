package com.byd.autodarkwallpaper.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import com.byd.autodarkwallpaper.config.AppPrefs;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AppLoggerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(AppPrefs.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit();
        AppLogger.clear();
    }

    @Test
    public void logWriteClearAndListenerNotification_workAsExpected() {
        AtomicReference<String> lastSnapshot = new AtomicReference<>();
        AppLogger.LogListener listener = lastSnapshot::set;
        AppLogger.addListener(listener);
        try {
            AppLogger.i("TestTag", "hello-log-entry");
            ShadowLooper.idleMainLooper();

            String all = AppLogger.getAllLogsText();
            assertTrue(all.contains("[I/TestTag] hello-log-entry"));
            assertNotNull(lastSnapshot.get());
            assertTrue(lastSnapshot.get().contains("hello-log-entry"));

            AppLogger.clear();
            ShadowLooper.idleMainLooper();
            assertFalse(AppLogger.getAllLogsText().contains("hello-log-entry"));
        } finally {
            AppLogger.removeListener(listener);
        }
    }

    @Test
    public void setCustomLogDirectoryAndExport_writesTimestampedFile() throws Exception {
        try {
            AppLogger.setCustomLogDirectory(context, "   ");
            fail("Expected IOException for blank path");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("不能为空"));
        }

        File customDir = tempFolder.newFolder("custom_logs");
        File applied = AppLogger.setCustomLogDirectory(context, customDir.getAbsolutePath());
        assertEquals(customDir.getAbsolutePath(), applied.getAbsolutePath());
        assertEquals(customDir.getAbsolutePath(), new AppPrefs(context).getLogCustomDirPath());

        AppLogger.w("ExportTest", "payload-line-123");
        File exported = AppLogger.exportLogsToTimestampedFile(context);
        assertTrue(exported.exists());
        assertTrue(exported.getName().startsWith("autodark_export_"));
        assertTrue(exported.getName().endsWith(".log"));

        String content = new String(Files.readAllBytes(exported.toPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("payload-line-123"));
    }
}
