package org.server.scrcpy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ScreenOffControllerTest {
    @Test
    public void pollsFastRightAfterWakeUp() {
        // A wake-up makes Android re-apply the brightness: catch it before it is visible.
        assertEquals(ScreenOffController.FAST_POLL_MS, ScreenOffController.pollIntervalMs(0));
        assertEquals(ScreenOffController.FAST_POLL_MS,
                ScreenOffController.pollIntervalMs(ScreenOffController.FAST_POLL_WINDOW_MS - 1));
    }

    @Test
    public void pollsSlowerOnceSettled() {
        assertEquals(ScreenOffController.SLOW_POLL_MS,
                ScreenOffController.pollIntervalMs(ScreenOffController.FAST_POLL_WINDOW_MS));
        assertEquals(ScreenOffController.SLOW_POLL_MS, ScreenOffController.pollIntervalMs(60_000));
    }

    @Test
    public void clockAnomalyDoesNotPinFastPolling() {
        assertEquals(ScreenOffController.SLOW_POLL_MS, ScreenOffController.pollIntervalMs(-5));
    }
}
