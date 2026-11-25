package com.sunr1se.byd.autowallpaper.config;

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

    // 壁纸选取策略
    public static final int SELECT_FIXED = 0;
    public static final int SELECT_RANDOM = 1;

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

    // 旧版自定义壁纸迁移兼容标志
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

    public boolean isLightCustom() {
        return prefs.getBoolean(KEY_LIGHT_IS_CUSTOM, false);
    }

    public boolean isDarkCustom() {
        return prefs.getBoolean(KEY_DARK_IS_CUSTOM, false);
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
        if (mode == MODE_LIVE_SERVICE) {
            return "动态壁纸模式";
        }
        return "直接修改壁纸";
    }
}
