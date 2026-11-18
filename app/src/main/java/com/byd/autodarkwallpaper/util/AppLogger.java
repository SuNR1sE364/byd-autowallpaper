package com.byd.autodarkwallpaper.util;

import android.content.Context;
import android.hardware.bydauto.BYDAutoConstants;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Log;

import com.byd.autodarkwallpaper.config.AppPrefs;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用全局诊断日志中心：同时输出到 Logcat、内存环形缓冲（用于实时界面展示）以及本地持久化日志文件。
 * 支持自定义保存路径（默认优先尝试公共 Download/BYDAutoDarkLogs 目录）及 SAF 目录/文件导出。
 */
public final class AppLogger {

    public interface LogListener {
        void onLogUpdated(String fullLogText);
    }

    private static final String LOGCAT_PREFIX = "BYDAutoDark";
    private static final String DEFAULT_PUBLIC_SUBDIR = "BYDAutoDarkLogs";
    private static final String LOG_FILE_NAME = "autodark_debug.log";
    private static final int MAX_BUFFER_LINES = 500;

    private static final Object BUFFER_LOCK = new Object();
    private static final ArrayDeque<String> LOG_LINES = new ArrayDeque<>(MAX_BUFFER_LINES);
    private static final List<LogListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService FILE_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final SimpleDateFormat LINE_TIME_FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);
    private static final SimpleDateFormat EXPORT_TIME_FMT =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA);

    private static volatile boolean initialized = false;
    private static volatile File logFile = null;
    private static volatile String initError = null;

    private AppLogger() {
    }

    public static synchronized void init(Context context) {
        if (initialized) {
            return;
        }
        initialized = true;
        Context appCtx = context.getApplicationContext();
        AppPrefs prefs = new AppPrefs(appCtx);

        List<String> probeFailures = new ArrayList<>();
        String customDir = prefs.getLogCustomDirPath();
        if (customDir != null && !customDir.trim().isEmpty()) {
            try {
                logFile = tryPrepareWritableLogFile(new File(customDir.trim()));
                initError = null;
                i("AppLogger", "日志系统初始化完成(自定义目录): " + logFile.getAbsolutePath());
                return;
            } catch (IOException e) {
                String err = "已设置的自定义日志目录不可写 (" + customDir + "): " + e.getMessage();
                probeFailures.add(err);
                Log.e(LOGCAT_PREFIX, err, e);
            }
        }

        List<File> presets = getPresetLogDirectories(appCtx);
        for (File dir : presets) {
            try {
                logFile = tryPrepareWritableLogFile(dir);
                initError = null;
                for (String fail : probeFailures) {
                    appendLine("W", "AppLogger", fail);
                }
                i("AppLogger", "日志系统初始化完成，持久化路径: " + logFile.getAbsolutePath());
                return;
            } catch (IOException e) {
                String err = "目录不可写 (" + dir.getAbsolutePath() + "): " + e.getMessage();
                probeFailures.add(err);
                Log.w(LOGCAT_PREFIX, err);
            }
        }

        initError = String.join(" | ", probeFailures);
        Log.e(LOGCAT_PREFIX, "所有日志目录初始化失败: " + initError);
        appendLine("E", "AppLogger", "所有日志目录初始化失败: " + initError);
    }

    /**
     * 返回推荐预设日志目录列表（按优先顺序排列）：
     * 1. 公共 Download/BYDAutoDarkLogs（车机自带文件管理器可直接访问）
     * 2. 公共 Documents/BYDAutoDarkLogs
     * 3. 外部存储根目录/BYDAutoDarkLogs
     * 4. 应用私有外部目录 Android/data/<pkg>/files/logs
     */
    public static List<File> getPresetLogDirectories(Context context) {
        Context appCtx = context.getApplicationContext();
        List<File> result = new ArrayList<>();
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloads != null) {
            result.add(new File(downloads, DEFAULT_PUBLIC_SUBDIR));
        }
        File documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        if (documents != null) {
            result.add(new File(documents, DEFAULT_PUBLIC_SUBDIR));
        }
        File extRoot = Environment.getExternalStorageDirectory();
        if (extRoot != null) {
            result.add(new File(extRoot, DEFAULT_PUBLIC_SUBDIR));
        }
        File privateLogs = appCtx.getExternalFilesDir("logs");
        if (privateLogs != null) {
            result.add(privateLogs);
        }
        return result;
    }

    /**
     * 切换自定义日志保存目录，验证可写性后立即迁移内存已有日志并持久化设置。
     */
    public static synchronized File setCustomLogDirectory(Context context, String customDirPath) throws IOException {
        if (customDirPath == null || customDirPath.trim().isEmpty()) {
            throw new IOException("自定义日志目录路径不能为空");
        }
        Context appCtx = context.getApplicationContext();
        File targetDir = new File(customDirPath.trim());
        File candidate = tryPrepareWritableLogFile(targetDir);

        boolean sameFile = (logFile != null && logFile.getAbsolutePath().equals(candidate.getAbsolutePath()));
        new AppPrefs(appCtx).setLogCustomDirPath(targetDir.getAbsolutePath());
        logFile = candidate;
        initError = null;

        if (!sameFile && candidate.length() == 0L) {
            final String snapshot = getAllLogsText();
            if (!snapshot.isEmpty()) {
                FILE_EXECUTOR.execute(() -> {
                    try (BufferedWriter writer = new BufferedWriter(
                            new OutputStreamWriter(new FileOutputStream(candidate, true), StandardCharsets.UTF_8))) {
                        writer.write(snapshot);
                    } catch (IOException e) {
                        Log.e(LOGCAT_PREFIX, "迁移已有日志到新目录失败: " + candidate.getAbsolutePath(), e);
                    }
                });
            }
        }
        i("AppLogger", "日志保存目录已切换至: " + candidate.getAbsolutePath());
        return targetDir;
    }

    public static String getCurrentLogDirectoryPath(Context context) {
        File current = logFile;
        if (current != null && current.getParentFile() != null) {
            return current.getParentFile().getAbsolutePath();
        }
        String custom = new AppPrefs(context.getApplicationContext()).getLogCustomDirPath();
        if (custom != null && !custom.trim().isEmpty()) {
            return custom.trim();
        }
        List<File> presets = getPresetLogDirectories(context);
        return presets.isEmpty() ? "" : presets.get(0).getAbsolutePath();
    }

    public static String getLogFilePath() {
        if (logFile != null) {
            return logFile.getAbsolutePath();
        }
        return initError != null ? "不可用 (" + initError + ")" : "未初始化";
    }

    public static String getLogLocationSummary(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("实时日志：").append(getLogFilePath());
        String safTree = new AppPrefs(context.getApplicationContext()).getLogSafTreeUri();
        if (safTree != null && !safTree.trim().isEmpty()) {
            sb.append(" | SAF导出目录已绑定");
        }
        return sb.toString();
    }

    public static void i(String tag, String msg) {
        Log.i(LOGCAT_PREFIX + "/" + tag, msg);
        appendLine("I", tag, msg);
    }

    public static void w(String tag, String msg) {
        Log.w(LOGCAT_PREFIX + "/" + tag, msg);
        appendLine("W", tag, msg);
    }

    public static void e(String tag, String msg) {
        e(tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable t) {
        String fullMsg = (t == null) ? msg : (msg + "\n" + Log.getStackTraceString(t));
        Log.e(LOGCAT_PREFIX + "/" + tag, fullMsg);
        appendLine("E", tag, fullMsg);
    }

    public static void clear() {
        synchronized (BUFFER_LOCK) {
            LOG_LINES.clear();
        }
        notifyListeners();
        i("AppLogger", "内存调试日志已清空");
    }

    public static String getAllLogsText() {
        synchronized (BUFFER_LOCK) {
            if (LOG_LINES.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (String line : LOG_LINES) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    public static void addListener(LogListener listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
            listener.onLogUpdated(getAllLogsText());
        }
    }

    public static void removeListener(LogListener listener) {
        LISTENERS.remove(listener);
    }

    public static String formatExportTimestamp() {
        synchronized (EXPORT_TIME_FMT) {
            return EXPORT_TIME_FMT.format(new Date());
        }
    }

    public static File exportLogsToTimestampedFile(Context context) throws IOException {
        Context appCtx = context.getApplicationContext();
        File logsDir = (logFile != null) ? logFile.getParentFile() : null;
        if (logsDir == null) {
            String customDir = new AppPrefs(appCtx).getLogCustomDirPath();
            if (customDir != null && !customDir.trim().isEmpty()) {
                logsDir = new File(customDir.trim());
            } else {
                List<File> presets = getPresetLogDirectories(appCtx);
                for (File candidate : presets) {
                    try {
                        tryPrepareWritableLogFile(candidate);
                        logsDir = candidate;
                        break;
                    } catch (IOException ignored) {
                        // 继续尝试下一个候选预设目录
                    }
                }
            }
        }
        if (logsDir == null) {
            throw new IOException("当前没有可用的本地可写日志目录，请点击【设置保存位置】重新指定或使用 SAF 导出");
        }
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            throw new IOException("创建导出日志目录失败: " + logsDir.getAbsolutePath());
        }

        String fileName = "autodark_export_" + formatExportTimestamp() + ".log";
        File outFile = new File(logsDir, fileName);
        String snapshot = getAllLogsText();
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(outFile, false), StandardCharsets.UTF_8))) {
            writer.write(snapshot);
        }
        i("AppLogger", "日志已导出至: " + outFile.getAbsolutePath());

        String safTree = new AppPrefs(appCtx).getLogSafTreeUri();
        if (safTree != null && !safTree.trim().isEmpty()) {
            try {
                exportLogsToSafTree(appCtx, Uri.parse(safTree));
            } catch (Throwable t) {
                e("AppLogger", "同步导出到 SAF 目录失败: " + safTree, t);
                throw new IOException("本地已写入 " + outFile.getAbsolutePath()
                        + "，但同步导出到 SAF 目录失败: " + t.getMessage(), t);
            }
        }
        return outFile;
    }

    /**
     * 将当前完整日志导出到用户通过 ACTION_OPEN_DOCUMENT_TREE 选择的文件夹（支持 U 盘与任意存储目录）。
     */
    public static Uri exportLogsToSafTree(Context context, Uri treeUri) throws IOException {
        if (treeUri == null) {
            throw new IOException("SAF 目录 URI 为 null");
        }
        Context appCtx = context.getApplicationContext();
        String docId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        String fileName = "autodark_export_" + formatExportTimestamp() + ".log";
        Uri createdUri = DocumentsContract.createDocument(
                appCtx.getContentResolver(), parentDocUri, "text/plain", fileName);
        if (createdUri == null) {
            throw new IOException("DocumentsContract.createDocument 返回 null (treeUri=" + treeUri + ")");
        }
        writeLogsToUri(appCtx, createdUri);
        i("AppLogger", "日志已导出至 SAF 目录文件: " + createdUri);
        return createdUri;
    }

    /**
     * 向指定文档 URI 写入当前内存日志全量文本。
     */
    public static void writeLogsToUri(Context context, Uri fileUri) throws IOException {
        if (fileUri == null) {
            throw new IOException("目标文件 URI 为 null");
        }
        String snapshot = getAllLogsText();
        try (OutputStream os = context.getApplicationContext().getContentResolver().openOutputStream(fileUri)) {
            if (os == null) {
                throw new IOException("ContentResolver.openOutputStream 返回 null: " + fileUri);
            }
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
                writer.write(snapshot);
                writer.flush();
            }
        }
    }

    /**
     * 将比亚迪 SDK 返回码格式化为可读名称与原始十进制/十六进制值。
     */
    public static String formatBydReturnCode(int code) {
        switch (code) {
            case BYDAutoConstants.BYDAUTO_COMMAND_RESULT_SUCCESS:
                return "0 (SUCCESS)";
            case BYDAutoConstants.BYDAUTO_COMMAND_RESULT_FAILED:
                return code + " (0x" + Integer.toHexString(code) + " / FAILED)";
            case BYDAutoConstants.BYDAUTO_COMMAND_RESULT_BUSY:
                return code + " (0x" + Integer.toHexString(code) + " / BUSY)";
            case BYDAutoConstants.BYDAUTO_COMMAND_RESULT_TIMEOUT:
                return code + " (0x" + Integer.toHexString(code) + " / TIMEOUT)";
            case BYDAutoConstants.BYDAUTO_COMMAND_RESULT_INVALID_VALUE:
                return code + " (0x" + Integer.toHexString(code) + " / INVALID_VALUE)";
            default:
                return code + " (0x" + Integer.toHexString(code) + ")";
        }
    }

    private static File tryPrepareWritableLogFile(File dir) throws IOException {
        if (dir == null) {
            throw new IOException("日志目录为 null");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建日志目录: " + dir.getAbsolutePath());
        }
        if (!dir.isDirectory()) {
            throw new IOException("目标路径不是目录: " + dir.getAbsolutePath());
        }
        File candidate = new File(dir, LOG_FILE_NAME);
        try (FileOutputStream fos = new FileOutputStream(candidate, true)) {
            fos.getFD();
        }
        return candidate;
    }

    private static void appendLine(String level, String tag, String msg) {
        String timestamp;
        synchronized (LINE_TIME_FMT) {
            timestamp = LINE_TIME_FMT.format(new Date());
        }
        String formatted = timestamp + " [" + level + "/" + tag + "] " + msg;
        synchronized (BUFFER_LOCK) {
            if (LOG_LINES.size() >= MAX_BUFFER_LINES) {
                LOG_LINES.removeFirst();
            }
            LOG_LINES.addLast(formatted);
        }
        writeToDiskAsync(formatted);
        notifyListeners();
    }

    private static void writeToDiskAsync(final String line) {
        final File target = logFile;
        if (target == null) {
            return;
        }
        FILE_EXECUTOR.execute(() -> {
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(target, true), StandardCharsets.UTF_8))) {
                writer.write(line);
                writer.newLine();
            } catch (IOException e) {
                Log.e(LOGCAT_PREFIX, "写入持久化日志文件失败: " + target.getAbsolutePath(), e);
            }
        });
    }

    private static void notifyListeners() {
        if (LISTENERS.isEmpty()) {
            return;
        }
        final String snapshot = getAllLogsText();
        MAIN_HANDLER.post(() -> {
            for (LogListener listener : LISTENERS) {
                listener.onLogUpdated(snapshot);
            }
        });
    }
}
