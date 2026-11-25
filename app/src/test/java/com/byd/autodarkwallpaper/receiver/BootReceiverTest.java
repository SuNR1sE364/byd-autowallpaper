package com.sunr1se.byd.autowallpaper.receiver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.app.Application;
import android.content.Intent;

import com.sunr1se.byd.autowallpaper.config.AppPrefs;
import com.sunr1se.byd.autowallpaper.service.AutoDarkMonitorService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowApplication;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BootReceiverTest {

    private Application app;
    private AppPrefs prefs;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(AppPrefs.PREFS_NAME, Application.MODE_PRIVATE).edit().clear().commit();
        prefs = new AppPrefs(app);
    }

    @Test
    public void onReceive_whenDisabled_doesNotStartMonitorService() {
        prefs.setEnabled(false);

        BootReceiver receiver = new BootReceiver();
        receiver.onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));

        ShadowApplication shadowApp = Shadow.extract(app);
        assertNull(shadowApp.getNextStartedService());
    }

    @Test
    public void onReceive_whenEnabled_startsMonitorServiceWithBootAction() {
        prefs.setEnabled(true);

        BootReceiver receiver = new BootReceiver();
        receiver.onReceive(app, new Intent("com.byd.action.BOOT_COMPLETED"));

        ShadowApplication shadowApp = Shadow.extract(app);
        Intent started = shadowApp.getNextStartedService();
        assertNotNull(started);
        assertEquals(AutoDarkMonitorService.class.getName(), started.getComponent().getClassName());
        assertEquals(AutoDarkMonitorService.ACTION_BOOT_START, started.getAction());
    }
}
