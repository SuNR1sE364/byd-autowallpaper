package com.byd.autodarkwallpaper.wallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VideoSpecEvaluatorTest {

    private static final long MB = 1024L * 1024L;

    @Test
    public void normalSpec_doesNotWarn() {
        VideoSpecEvaluator.EvaluationResult res =
                VideoSpecEvaluator.evaluate(1920, 1080, 30f, 20_000L, 25 * MB);
        assertFalse(res.shouldWarn);
        assertTrue(res.triggeredReasons.isEmpty());
        assertFalse(res.hitResolution2K);
        assertFalse(res.hitHighFps);
        assertFalse(res.hitLongDuration);
        assertFalse(res.hitLargeFile);
    }

    @Test
    public void singleConditionOnly_neverWarns() {
        // 仅 2K 分辨率（同时覆盖长边>=2560、短边>=1440 以及非2K边界1920x1200）
        VideoSpecEvaluator.EvaluationResult r2k =
                VideoSpecEvaluator.evaluate(2560, 1080, 30f, 20_000L, 20 * MB);
        assertFalse(r2k.shouldWarn);
        assertTrue(r2k.hitResolution2K);
        assertEquals(1, r2k.triggeredReasons.size());

        VideoSpecEvaluator.EvaluationResult r2kPortrait =
                VideoSpecEvaluator.evaluate(1440, 2560, 30f, 20_000L, 20 * MB);
        assertFalse(r2kPortrait.shouldWarn);
        assertTrue(r2kPortrait.hitResolution2K);

        VideoSpecEvaluator.EvaluationResult not2k =
                VideoSpecEvaluator.evaluate(1920, 1200, 60.0f, 59_999L, 100 * MB);
        assertFalse(not2k.shouldWarn);
        assertTrue(not2k.triggeredReasons.isEmpty());

        // 仅 >60fps
        VideoSpecEvaluator.EvaluationResult rFps =
                VideoSpecEvaluator.evaluate(1920, 1080, 60.5f, 20_000L, 20 * MB);
        assertFalse(rFps.shouldWarn);
        assertTrue(rFps.hitHighFps);
        assertEquals(1, rFps.triggeredReasons.size());

        // 仅 >=60s
        VideoSpecEvaluator.EvaluationResult rDur =
                VideoSpecEvaluator.evaluate(1920, 1080, 60f, 60_000L, 20 * MB);
        assertFalse(rDur.shouldWarn);
        assertTrue(rDur.hitLongDuration);
        assertEquals(1, rDur.triggeredReasons.size());

        // 仅 >100MB
        VideoSpecEvaluator.EvaluationResult rSize =
                VideoSpecEvaluator.evaluate(1920, 1080, 60f, 59_999L, 100 * MB + 1L);
        assertFalse(rSize.shouldWarn);
        assertTrue(rSize.hitLargeFile);
        assertEquals(1, rSize.triggeredReasons.size());
    }

    @Test
    public void anyTwoConditions_alwaysWarn() {
        // 1. 2K + >60fps
        VideoSpecEvaluator.EvaluationResult c1 =
                VideoSpecEvaluator.evaluate(2560, 1440, 120f, 15_000L, 30 * MB);
        assertTrue(c1.shouldWarn);
        assertEquals(2, c1.triggeredReasons.size());

        // 2. 2K + >=60s
        VideoSpecEvaluator.EvaluationResult c2 =
                VideoSpecEvaluator.evaluate(2560, 1440, 30f, 60_000L, 30 * MB);
        assertTrue(c2.shouldWarn);
        assertEquals(2, c2.triggeredReasons.size());

        // 3. 2K + >100MB
        VideoSpecEvaluator.EvaluationResult c3 =
                VideoSpecEvaluator.evaluate(2560, 1440, 30f, 30_000L, 120 * MB);
        assertTrue(c3.shouldWarn);
        assertEquals(2, c3.triggeredReasons.size());

        // 4. >60fps + >=60s
        VideoSpecEvaluator.EvaluationResult c4 =
                VideoSpecEvaluator.evaluate(1920, 1080, 90f, 75_000L, 40 * MB);
        assertTrue(c4.shouldWarn);
        assertEquals(2, c4.triggeredReasons.size());

        // 5. >60fps + >100MB
        VideoSpecEvaluator.EvaluationResult c5 =
                VideoSpecEvaluator.evaluate(1920, 1080, 90f, 30_000L, 150 * MB);
        assertTrue(c5.shouldWarn);
        assertEquals(2, c5.triggeredReasons.size());

        // 6. >=60s + >100MB
        VideoSpecEvaluator.EvaluationResult c6 =
                VideoSpecEvaluator.evaluate(1920, 1080, 30f, 90_000L, 150 * MB);
        assertTrue(c6.shouldWarn);
        assertEquals(2, c6.triggeredReasons.size());
    }

    @Test
    public void threeAndFourConditions_warnWithAllReasons() {
        VideoSpecEvaluator.EvaluationResult allFour =
                VideoSpecEvaluator.evaluate(3840, 2160, 120f, 120_000L, 250 * MB);
        assertTrue(allFour.shouldWarn);
        assertEquals(4, allFour.triggeredReasons.size());
        assertTrue(allFour.hitResolution2K);
        assertTrue(allFour.hitHighFps);
        assertTrue(allFour.hitLongDuration);
        assertTrue(allFour.hitLargeFile);
    }

    @Test
    public void formattingHelpers_produceExpectedLabels() {
        assertEquals("00:00", VideoSpecEvaluator.formatDuration(0L));
        assertEquals("00:18", VideoSpecEvaluator.formatDuration(18_500L));
        assertEquals("01:05", VideoSpecEvaluator.formatDuration(65_000L));
        assertEquals("1:01:05", VideoSpecEvaluator.formatDuration(3_665_000L));
        assertEquals("60 fps", VideoSpecEvaluator.formatFps(60.0f));
        assertEquals("60.5 fps", VideoSpecEvaluator.formatFps(60.5f));
        assertEquals("512 KB", VideoSpecEvaluator.formatSize(512 * 1024L));
        assertEquals("120.0 MB", VideoSpecEvaluator.formatSize(120 * MB));
    }
}
