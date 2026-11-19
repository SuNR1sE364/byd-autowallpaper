package com.byd.autodarkwallpaper.config;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 持久化应用配置管理。
 */
public final class AppPrefs {

    public static final String PREFS_NAME = "byd_autodark_prefs";

    // 壁纸实现模式
    public static final int MODE_STATIC_MANAGER = 0;
    public static final int MODE_LIVE_SERVICE = 1;
    public static final int MODE_DUAL = 2; // 兼容历史配置值

    // 壁纸选取策略
    public static final int SELECT_FIXED = 0;
    public static final int SELECT_RANDOM = 1;

    // 触发源模式（保留底层字段，日常版本仅启用 TRIGGER_SYSTEM_UIMODE）
    public static final int TRIGGER_SYSTEM_UIMODE = 0;
    public static final int TRIGGER_BYD_LIGHT = 1;
    public static final int TRIGGER_BYD_SENSOR = 2;
    public static final int TRIGGER_ANY_DARK = 3;

    // 当前应用状态三态值
    public static final int TARGET_STATE_UNSET = -1;
    public static final int TARGET_STATE_LIGHT = 0;
    public static final int TARGET_STATE_DARK = 1;

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_WALLPAPER_MODE = "wallpaper_mode";
    private static final String KEY_DEBUG_MODE = "debug_mode_enabled";
    private static final String KEY_DAY_SELECTION_MODE = "day_selection_mode";
    private static final String KEY_NIGHT_SELECTION_MODE = "night_selection_mode";
    private static final String KEY_DAY_SELECTED_ID = "day_selected_wallpaper_id";
    private static final String KEY_NIGHT_SELECTED_ID = "night_selected_wallpaper_id";
    private static final String KEY_DAY_ACTIVE_ID = "day_active_wallpaper_id";
    private static final String KEY_NIGHT_ACTIVE_ID = "night_active_wallpaper_id";
    private static final String KEY_HAS_ORIGINAL_BACKUP = "has_original_wallpaper_backup";
    private static final String KEY_ORIGINAL_BACKUP_META = "original_wallpaper_backup_meta";

    // 底层硬件及高级策略字段（保留兼容）
    private static final String KEY_ACTIVE_TRIGGER_SOURCE = "active_trigger_source";
    private static final String KEY_LIGHT_USE_SIDE = "light_use_side";
    private static final String KEY_LIGHT_USE_LOW_BEAM = "light_use_low_beam";
    private static final String KEY_LIGHT_USE_HIGH_BEAM = "light_use_high_beam";
    private static final String KEY_SENSOR_THRESHOLD_LEVEL = "sensor_threshold_level";
    private static final String KEY_SENSOR_DARK_WHEN_LTE = "sensor_dark_when_lte";
    private static final String KEY_DEBOUNCE_MS = "debounce_ms";
    private static final String KEY_BOOT_AUTO_START = "boot_auto_start";
    private static final String KEY_STATIC_SYNC_LOCK_SCREEN = "static_sync_lock_screen";
    private static final String KEY_LIGHT_IS_CUSTOM = "light_is_custom";
    private static final String KEY_DARK_IS_CUSTOM = "dark_is_custom";
    private static final String KEY_CURRENT_TARGET_STATE = "current_target_state";
    private static final String KEY_LOG_CUSTOM_DIR_PATH = "log_custom_dir_path";
    private static final String KEY_LOG_SAF_TREE_URI = "log_saf_tree_uri";

    private final SharedPreferences prefs;

    public AppPrefs(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public int getWallpaperMode() {
        int mode = prefs.getInt(KEY_WALLPAPER_MODE, MODE_STATIC_MANAGER);
        if (mode != MODE_STATIC_MANAGER && mode != MODE_LIVE_SERVICE) {
            return MODE_STATIC_MANAGER;
        }
        return mode;
    }

    public void setWallpaperMode(int mode) {
        if (mode != MODE_STATIC_MANAGER && mode != MODE_LIVE_SERVICE) {
            mode = MODE_STATIC_MANAGER;
        }
        prefs.edit().putInt(KEY_WALLPAPER_MODE, mode).apply();
    }

    public boolean isDebugModeEnabled() {
        return prefs.getBoolean(KEY_DEBUG_MODE, false);
    }

    public void setDebugModeEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_DEBUG_MODE, enabled).apply();
    }

    public int getSelectionMode(boolean isDark) {
        String key = isDark ? KEY_NIGHT_SELECTION_MODE : KEY_DAY_SELECTION_MODE;
        int mode = prefs.getInt(key, SELECT_FIXED);
        return (mode == SELECT_RANDOM) ? SELECT_RANDOM : SELECT_FIXED;
    }

    public void setSelectionMode(boolean isDark, int mode) {
        String key = isDark ? KEY_NIGHT_SELECTION_MODE : KEY_DAY_SELECTION_MODE;
        int normalized = (mode == SELECT_RANDOM) ? SELECT_RANDOM : SELECT_FIXED;
        prefs.edit().putInt(key, normalized).apply();
    }

    public String getSelectedWallpaperId(boolean isDark) {
        String key = isDark ? KEY_NIGHT_SELECTED_ID : KEY_DAY_SELECTED_ID;
        return prefs.getString(key, null);
    }

    public void setSelectedWallpaperId(boolean isDark, String id) {
        String key = isDark ? KEY_NIGHT_SELECTED_ID : KEY_DAY_SELECTED_ID;
        if (id == null || id.trim().isEmpty()) {
            prefs.edit().remove(key).apply();
        } else {
            prefs.edit().putString(key, id.trim()).apply();
        }
    }

    public String getActiveWallpaperId(boolean isDark) {
        String key = isDark ? KEY_NIGHT_ACTIVE_ID : KEY_DAY_ACTIVE_ID;
        return prefs.getString(key, null);
    }

    public void setActiveWallpaperId(boolean isDark, String id) {
        String key = isDark ? KEY_NIGHT_ACTIVE_ID : KEY_DAY_ACTIVE_ID;
        if (id == null || id.trim().isEmpty()) {
            prefs.edit().remove(key).apply();
        } else {
            prefs.edit().putString(key, id.trim()).apply();
        }
    }

    public boolean hasOriginalBackup() {
        return prefs.getBoolean(KEY_HAS_ORIGINAL_BACKUP, false);
    }

    public void setHasOriginalBackup(boolean hasBackup) {
        prefs.edit().putBoolean(KEY_HAS_ORIGINAL_BACKUP, hasBackup).apply();
    }

    public String getOriginalBackupMeta() {
        return prefs.getString(KEY_ORIGINAL_BACKUP_META, null);
    }

    public void setOriginalBackupMeta(String meta) {
        if (meta == null || meta.trim().isEmpty()) {
            prefs.edit().remove(KEY_ORIGINAL_BACKUP_META).apply();
        } else {
            prefs.edit().putString(KEY_ORIGINAL_BACKUP_META, meta.trim()).apply();
        }
    }

    public int getActiveTriggerSource() {
        return prefs.getInt(KEY_ACTIVE_TRIGGER_SOURCE, TRIGGER_SYSTEM_UIMODE);
    }

    public void setActiveTriggerSource(int source) {
        prefs.edit().putInt(KEY_ACTIVE_TRIGGER_SOURCE, source).apply();
    }

    public boolean isLightUseSide() {
        return prefs.getBoolean(KEY_LIGHT_USE_SIDE, true);
    }

    public void setLightUseSide(boolean value) {
        prefs.edit().putBoolean(KEY_LIGHT_USE_SIDE, value).apply();
    }

    public boolean isLightUseLowBeam() {
        return prefs.getBoolean(KEY_LIGHT_USE_LOW_BEAM, true);
    }

    public void setLightUseLowBeam(boolean value) {
        prefs.edit().putBoolean(KEY_LIGHT_USE_LOW_BEAM, value).apply();
    }

    public boolean isLightUseHighBeam() {
        return prefs.getBoolean(KEY_LIGHT_USE_HIGH_BEAM, false);
    }

    public void setLightUseHighBeam(boolean value) {
        prefs.edit().putBoolean(KEY_LIGHT_USE_HIGH_BEAM, value).apply();
    }

    public int getSensorThresholdLevel() {
        return prefs.getInt(KEY_SENSOR_THRESHOLD_LEVEL, 2);
    }

    public void setSensorThresholdLevel(int level) {
        prefs.edit().putInt(KEY_SENSOR_THRESHOLD_LEVEL, level).apply();
    }

    public boolean isSensorDarkWhenLte() {
        return prefs.getBoolean(KEY_SENSOR_DARK_WHEN_LTE, true);
    }

    public void setSensorDarkWhenLte(boolean lte) {
        prefs.edit().putBoolean(KEY_SENSOR_DARK_WHEN_LTE, lte).apply();
    }

    public long getDebounceMs() {
        return prefs.getLong(KEY_DEBOUNCE_MS, 0L);
    }

    public void setDebounceMs(long debounceMs) {
        prefs.edit().putLong(KEY_DEBOUNCE_MS, debounceMs).apply();
    }

    public boolean isBootAutoStart() {
        return prefs.getBoolean(KEY_BOOT_AUTO_START, true);
    }

    public void setBootAutoStart(boolean bootAutoStart) {
        prefs.edit().putBoolean(KEY_BOOT_AUTO_START, bootAutoStart).apply();
    }

    public boolean isStaticSyncLockScreen() {
        return prefs.getBoolean(KEY_STATIC_SYNC_LOCK_SCREEN, false);
    }

    public void setStaticSyncLockScreen(boolean syncLockScreen) {
        prefs.edit().putBoolean(KEY_STATIC_SYNC_LOCK_SCREEN, syncLockScreen).apply();
    }

    public boolean isLightCustom() {
        return prefs.getBoolean(KEY_LIGHT_IS_CUSTOM, false);
    }

    public void setLightCustom(boolean custom) {
        prefs.edit().putBoolean(KEY_LIGHT_IS_CUSTOM, custom).apply();
    }

    public boolean isDarkCustom() {
        return prefs.getBoolean(KEY_DARK_IS_CUSTOM, false);
    }

    public void setDarkCustom(boolean custom) {
        prefs.edit().putBoolean(KEY_DARK_IS_CUSTOM, custom).apply();
    }

    public int getCurrentTargetState() {
        return prefs.getInt(KEY_CURRENT_TARGET_STATE, TARGET_STATE_UNSET);
    }

    public void setCurrentTargetState(boolean isDark) {
        prefs.edit().putInt(KEY_CURRENT_TARGET_STATE,
                isDark ? TARGET_STATE_DARK : TARGET_STATE_LIGHT).apply();
    }

    public String getLogCustomDirPath() {
        return prefs.getString(KEY_LOG_CUSTOM_DIR_PATH, null);
    }

    public void setLogCustomDirPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            prefs.edit().remove(KEY_LOG_CUSTOM_DIR_PATH).apply();
        } else {
            prefs.edit().putString(KEY_LOG_CUSTOM_DIR_PATH, path.trim()).apply();
        }
    }

    public String getLogSafTreeUri() {
        return prefs.getString(KEY_LOG_SAF_TREE_URI, null);
    }

    public void setLogSafTreeUri(String uriString) {
        if (uriString == null || uriString.trim().isEmpty()) {
            prefs.edit().remove(KEY_LOG_SAF_TREE_URI).apply();
        } else {
            prefs.edit().putString(KEY_LOG_SAF_TREE_URI, uriString.trim()).apply();
        }
    }

    public static String formatWallpaperMode(int mode) {
        switch (mode) {
            case MODE_STATIC_MANAGER:
                return "直接修改壁纸";
            case MODE_LIVE_SERVICE:
                return "动态壁纸模式";
            default:
                return "直接修改壁纸";
        }
    }

    public static String formatTriggerSource(int source) {
        switch (source) {
            case TRIGGER_SYSTEM_UIMODE:
                return "系统深浅色模式";
            case TRIGGER_BYD_LIGHT:
                return "比亚迪车灯(BYDAutoLightDevice)";
            case TRIGGER_BYD_SENSOR:
                return "比亚迪光感(BYDAutoSensorDevice)";
            case TRIGGER_ANY_DARK:
                return "多源联合触发";
            default:
                return "未知触发源(" + source + ")";
        }
    }
}
