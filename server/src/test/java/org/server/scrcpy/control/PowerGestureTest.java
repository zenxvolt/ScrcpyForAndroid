package org.server.scrcpy.control;

import static android.view.KeyEvent.KEYCODE_SLEEP;
import static android.view.KeyEvent.KEYCODE_UNKNOWN;
import static android.view.KeyEvent.KEYCODE_WAKEUP;
import static android.view.MotionEvent.*;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PowerGestureTest {
    private final PowerGesture gesture = new PowerGesture(160);

    private int touch(int action, int x, int y, long time, boolean on, boolean near) {
        return gesture.onTouch(action, x, y, time, on, near);
    }

    private int tap(long time, boolean on, boolean near) {
        assertEquals(KEYCODE_UNKNOWN, touch(ACTION_DOWN, 100, 100, time, on, near));
        return touch(ACTION_UP, 100, 100, time + 30, on, near);
    }

    @Test
    public void ordinaryLockscreenTapsNeverChangePower() {
        for (int i = 0; i < 10; ++i) {
            assertEquals(KEYCODE_UNKNOWN, tap(1000 + i * 100, true, false));
        }
    }

    @Test
    public void doubleTapWhileOffOnlyRequestsWakeup() {
        assertEquals(KEYCODE_UNKNOWN, tap(1000, false, false));
        assertEquals(KEYCODE_WAKEUP, tap(1100, false, false));
        // A stale screen-state query must not turn the screen back off.
        assertEquals(KEYCODE_UNKNOWN, tap(1200, false, false));
        assertEquals(KEYCODE_WAKEUP, tap(1300, false, false));
    }

    @Test
    public void coveredSensorOnlyRequestsSleepWhenScreenIsOn() {
        assertEquals(KEYCODE_UNKNOWN, tap(1000, true, true));
        assertEquals(KEYCODE_SLEEP, tap(1100, true, true));
        assertEquals(KEYCODE_UNKNOWN, tap(1200, false, true));
        assertEquals(KEYCODE_WAKEUP, tap(1300, false, true));
    }

    @Test
    public void swipesDoNotCountAsTapsEvenIfTheyReturnToTheirStart() {
        for (int i = 0; i < 2; ++i) {
            long time = 1000 + i * 100;
            touch(ACTION_DOWN, 100, 100, time, false, false);
            touch(ACTION_MOVE, 100, 300, time + 10, false, false);
            touch(ACTION_MOVE, 100, 100, time + 20, false, false);
            assertEquals(KEYCODE_UNKNOWN, touch(ACTION_UP, 100, 100, time + 30, false, false));
        }
        assertEquals(KEYCODE_UNKNOWN, tap(1200, false, false));
    }

    @Test
    public void movementAtReleaseAlsoRejectsSwipe() {
        tap(1000, false, false);
        touch(ACTION_DOWN, 100, 100, 1100, false, false);
        assertEquals(KEYCODE_UNKNOWN, touch(ACTION_UP, 100, 300, 1130, false, false));
    }

    @Test
    public void longPressDoesNotArmNextTap() {
        touch(ACTION_DOWN, 100, 100, 1000, false, false);
        assertEquals(KEYCODE_UNKNOWN, touch(ACTION_UP, 100, 100, 1400, false, false));
        assertEquals(KEYCODE_UNKNOWN, tap(1450, false, false));
    }

    @Test
    public void cancellationAndMultiplePointersClearPendingTap() {
        for (int action : new int[]{ACTION_CANCEL, ACTION_POINTER_DOWN | (1 << ACTION_POINTER_INDEX_SHIFT),
                ACTION_POINTER_UP | (1 << ACTION_POINTER_INDEX_SHIFT)}) {
            gesture.reset();
            tap(1000, false, false);
            touch(ACTION_DOWN, 100, 100, 1100, false, false);
            assertEquals(KEYCODE_UNKNOWN, touch(action, 100, 100, 1110, false, false));
            assertEquals(KEYCODE_UNKNOWN, touch(ACTION_UP, 100, 100, 1130, false, false));
            assertEquals(KEYCODE_UNKNOWN, tap(1200, false, false));
        }
    }

    @Test
    public void screenWakingBetweenTapsCancelsShortcut() {
        tap(1000, false, false);
        assertEquals(KEYCODE_UNKNOWN, tap(1100, true, false));
        assertEquals(KEYCODE_UNKNOWN, tap(1200, false, false));
    }

    @Test
    public void proximityChangeCannotCombineWakeAndSleepTaps() {
        tap(1000, false, false);
        assertEquals(KEYCODE_UNKNOWN, tap(1100, true, true));
        assertEquals(KEYCODE_SLEEP, tap(1200, true, true));
    }

    @Test
    public void distantOrSlowTapsDoNotFormDoubleTap() {
        tap(1000, false, false);
        touch(ACTION_DOWN, 400, 400, 1100, false, false);
        assertEquals(KEYCODE_UNKNOWN, touch(ACTION_UP, 400, 400, 1130, false, false));
        assertEquals(KEYCODE_UNKNOWN, tap(2000, false, false));
        assertEquals(KEYCODE_UNKNOWN, tap(2300, false, false));
    }

    @Test
    public void longUptimeDoesNotRoundAwayTapTimeout() {
        long time = 1L << 35;
        assertEquals(KEYCODE_UNKNOWN, tap(time, false, false));
        assertEquals(KEYCODE_UNKNOWN, tap(time + 300, false, false));
        assertEquals(KEYCODE_WAKEUP, tap(time + 400, false, false));
    }

    @Test
    public void unpairedReleasesNeverTriggerPower() {
        assertEquals(KEYCODE_UNKNOWN, touch(ACTION_UP, 100, 100, 1000, false, false));
        assertEquals(KEYCODE_UNKNOWN, touch(ACTION_UP, 100, 100, 1100, false, false));
    }

    @Test
    public void explicitResetClearsTapHistory() {
        tap(1000, false, false);
        gesture.reset();
        assertEquals(KEYCODE_UNKNOWN, tap(1100, false, false));
    }
}
