package com.byd.autodarkwallpaper.util;

import android.content.Context;
import android.hardware.bydauto.BYDAutoConstants;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用全局诊断日志中心：同时输出到 Logcat、内存环形缓冲（用于实时界面展示）以及本地持久化日志文件。
 */
public final class AppLogger {

    public interface LogListener {
        void onLogUpdated(String fullLogText);
    }

    private static final String LOGCAT_PREFIX = "BYDAutoDark";
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
        Context appCtx = context.getApplicationContext();
        File logsDir = appCtx.getExternalFilesDir("logs");
        if (logsDir == null) {
            initError = "Context.getExternalFilesDir(\"logs\") 返回 null，无法开启外部私有目录日志持久化";
            Log.e(LOGCAT_PREFIX, initError);
            initialized = true;
            appendLine("E", "AppLogger", initError);
            return;
        }
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            initError = "无法创建日志目录: " + logsDir.getAbsolutePath();
            Log.e(LOGCAT_PREFIX, initError);
            initialized = true;
            appendLine("E", "AppLogger", initError);
            return;
        }
        logFile = new File(logsDir, "autodark_debug.log");
        initialized = true;
        i("AppLogger", "日志系统初始化完成，持久化路径: " + logFile.getAbsolutePath());
    }

    public static String getLogFilePath() {
        if (logFile != null) {
            return logFile.getAbsolutePath();
        }
        return initError != null ? "不可用 (" + initError + ")" : "未初始化";
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

    public static File exportLogsToTimestampedFile(Context context) throws IOException {
        File logsDir = context.getApplicationContext().getExternalFilesDir("logs");
        if (logsDir == null) {
            throw new IOException("Context.getExternalFilesDir(\"logs\") returned null");
        }
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            throw new IOException("Failed to create logs directory: " + logsDir.getAbsolutePath());
        }
        String fileName = "autodark_export_" + EXPORT_TIME_FMT.format(new Date()) + ".log";
        File outFile = new File(logsDir, fileName);
        String snapshot = getAllLogsText();
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(outFile, false), StandardCharsets.UTF_8))) {
            writer.write(snapshot);
        }
        i("AppLogger", "日志已导出至: " + outFile.getAbsolutePath());
        return outFile;
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
