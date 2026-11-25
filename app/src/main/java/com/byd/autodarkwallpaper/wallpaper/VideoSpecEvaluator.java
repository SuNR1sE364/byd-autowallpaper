package com.sunr1se.byd.autowallpaper.wallpaper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 视频壁纸规格评估器：用于检测导入视频是否达到高规格提醒阈值。
 * 规则：2K及以上、>60fps、>=60s、>100MB 四个条件满足任意两个及以上时触发提醒。
 */
public final class VideoSpecEvaluator {

    public static final int MIN_2K_LONG_EDGE = 2560;
    public static final int MIN_2K_SHORT_EDGE = 1440;
    public static final float HIGH_FPS_THRESHOLD = 60.1f;
    public static final long LONG_DURATION_MS = 60_000L;
    public static final long LARGE_FILE_BYTES = 100L * 1024L * 1024L;
    public static final int TRIGGER_MIN_CONDITIONS = 2;

    public static final class EvaluationResult {
        public final boolean shouldWarn;
        public final List<String> triggeredReasons;
        public final boolean hitResolution2K;
        public final boolean hitHighFps;
        public final boolean hitLongDuration;
        public final boolean hitLargeFile;

        public EvaluationResult(boolean shouldWarn,
                                List<String> triggeredReasons,
                                boolean hitResolution2K,
                                boolean hitHighFps,
                                boolean hitLongDuration,
                                boolean hitLargeFile) {
            this.shouldWarn = shouldWarn;
            this.triggeredReasons = triggeredReasons == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(triggeredReasons));
            this.hitResolution2K = hitResolution2K;
            this.hitHighFps = hitHighFps;
            this.hitLongDuration = hitLongDuration;
            this.hitLargeFile = hitLargeFile;
        }
    }

    private VideoSpecEvaluator() {
    }

    /**
     * 根据分辨率、帧率、时长和文件体积评估是否需要高规格提示。
     */
    public static EvaluationResult evaluate(int width, int height, float fps, long durationMs, long sizeBytes) {
        List<String> reasons = new ArrayList<>();

        boolean hit2K = width > 0 && height > 0
                && (Math.max(width, height) >= MIN_2K_LONG_EDGE
                || Math.min(width, height) >= MIN_2K_SHORT_EDGE);
        if (hit2K) {
            reasons.add("分辨率达到 2K 及以上 (" + width + "×" + height + ")");
        }

        boolean hitFps = fps > HIGH_FPS_THRESHOLD;
        if (hitFps) {
            reasons.add("帧率高于 60fps (" + formatFps(fps) + ")");
        }

        boolean hitDuration = durationMs >= LONG_DURATION_MS;
        if (hitDuration) {
            reasons.add("时长达到 60 秒及以上 (" + formatDuration(durationMs) + ")");
        }

        boolean hitSize = sizeBytes > LARGE_FILE_BYTES;
        if (hitSize) {
            reasons.add("文件体积超过 100MB (" + formatSize(sizeBytes) + ")");
        }

        boolean shouldWarn = reasons.size() >= TRIGGER_MIN_CONDITIONS;
        return new EvaluationResult(shouldWarn, reasons, hit2K, hitFps, hitDuration, hitSize);
    }

    public static String formatDuration(long durationMs) {
        if (durationMs <= 0L) {
            return "00:00";
        }
        long totalSeconds = durationMs / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.CHINA, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.CHINA, "%02d:%02d", minutes, seconds);
    }

    public static String formatSize(long sizeBytes) {
        if (sizeBytes <= 0L) {
            return "0 KB";
        }
        long kb = Math.max(1L, sizeBytes / 1024L);
        if (kb >= 1024L) {
            return String.format(Locale.CHINA, "%.1f MB", sizeBytes / (1024.0 * 1024.0));
        }
        return kb + " KB";
    }

    public static String formatFps(float fps) {
        if (fps <= 0f || Float.isNaN(fps)) {
            return "";
        }
        if (Math.abs(fps - Math.round(fps)) < 0.05f) {
            return Math.round(fps) + " fps";
        }
        return String.format(Locale.CHINA, "%.1f fps", fps);
    }
}
