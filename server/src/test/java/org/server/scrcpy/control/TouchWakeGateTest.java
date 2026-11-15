package org.server.scrcpy.control;

import static android.view.MotionEvent.*;
import static org.junit.Assert.assertEquals;
import static org.server.scrcpy.control.TouchWakeGate.Result.*;

import org.junit.Test;

public class TouchWakeGateTest {
    private final TouchWakeGate gate = new TouchWakeGate();

    @Test
    public void awakeLockscreenGestureIsDeliveredCompletely() {
        assertEquals(DELIVER, gate.onTouch(ACTION_DOWN, true, false));
        assertEquals(DELIVER, gate.onTouch(ACTION_MOVE, true, false));
        assertEquals(DELIVER, gate.onTouch(ACTION_UP, true, false));
    }

    @Test
    public void screenStateChangeDoesNotDropAnActiveSwipe() {
        assertEquals(DELIVER, gate.onTouch(ACTION_DOWN, true, false));
        assertEquals(DELIVER, gate.onTouch(ACTION_MOVE, false, false));
        assertEquals(DELIVER, gate.onTouch(ACTION_UP, false, false));
    }

    @Test
    public void singleTapWakesAndConsumesTheEntireWakingGesture() {
        assertEquals(WAKE_UP, gate.onTouch(ACTION_DOWN, false, false));
        assertEquals(CONSUME, gate.onTouch(ACTION_MOVE, true, false));
        assertEquals(CONSUME, gate.onTouch(ACTION_UP, true, false));
        assertEquals(DELIVER, gate.onTouch(ACTION_DOWN, true, false));
        assertEquals(DELIVER, gate.onTouch(ACTION_UP, true, false));
    }

    @Test
    public void releaseBeforeWakeCompletesDoesNotSwallowNextSwipe() {
        assertEquals(WAKE_UP, gate.onTouch(ACTION_DOWN, false, false));
        assertEquals(CONSUME, gate.onTouch(ACTION_UP, false, false));
        assertEquals(DELIVER, gate.onTouch(ACTION_DOWN, true, false));
        assertEquals(DELIVER, gate.onTouch(ACTION_MOVE, true, false));
        assertEquals(DELIVER, gate.onTouch(ACTION_UP, true, false));
    }

    @Test
    public void newDownRecoversFromMissingRelease() {
        assertEquals(WAKE_UP, gate.onTouch(ACTION_DOWN, false, false));
        assertEquals(DELIVER, gate.onTouch(ACTION_DOWN, true, false));
        assertEquals(DELIVER, gate.onTouch(ACTION_UP, true, false));
    }

    @Test
    public void cancelEndsTheConsumedGesture() {
        assertEquals(WAKE_UP, gate.onTouch(ACTION_DOWN, false, false));
        assertEquals(CONSUME, gate.onTouch(ACTION_CANCEL, false, false));
        assertEquals(DELIVER, gate.onTouch(ACTION_DOWN, true, false));
    }

    @Test
    public void secondaryFingersCannotWakeAgainDuringConsumedGesture() {
        assertEquals(WAKE_UP, gate.onTouch(ACTION_DOWN, false, false));
        assertEquals(CONSUME, gate.onTouch(ACTION_POINTER_DOWN | (1 << ACTION_POINTER_INDEX_SHIFT), false, false));
        assertEquals(CONSUME, gate.onTouch(ACTION_POINTER_UP | (1 << ACTION_POINTER_INDEX_SHIFT), true, false));
        assertEquals(CONSUME, gate.onTouch(ACTION_UP, true, false));
    }

    @Test
    public void proximityGesturesRemainAvailableWithoutDroppingRelease() {
        assertEquals(DELIVER, gate.onTouch(ACTION_DOWN, false, true));
        assertEquals(DELIVER, gate.onTouch(ACTION_UP, false, true));
    }

    @Test
    public void forcedOffPanelDoesNotWakeOnRepeatedLockscreenSwipes() {
        for (int i = 0; i < 10; ++i) {
            assertEquals(DELIVER, gate.onTouch(ACTION_DOWN, false, false, true, true));
            assertEquals(DELIVER, gate.onTouch(ACTION_MOVE, false, false, true, true));
            assertEquals(DELIVER, gate.onTouch(ACTION_UP, false, false, true, true));
        }
    }

    @Test
    public void realSleepInScreenOffModeStillWakesAndDeliversSwipe() {
        assertEquals(WAKE_AND_DELIVER, gate.onTouch(ACTION_DOWN, false, false, true, false));
        assertEquals(DELIVER, gate.onTouch(ACTION_MOVE, true, false, true, false));
        assertEquals(DELIVER, gate.onTouch(ACTION_UP, true, false, true, false));
    }

    @Test
    public void unsupportedPanelFallbackAllowsNextTouchToWakeNormally() {
        assertEquals(DELIVER, gate.onTouch(ACTION_DOWN, false, false, true, true));
        assertEquals(DELIVER, gate.onTouch(ACTION_UP, false, false, true, true));
        // The keeper restores panel power and disables its override on ROM-induced sleep.
        assertEquals(WAKE_AND_DELIVER, gate.onTouch(ACTION_DOWN, false, false, true, false));
        assertEquals(DELIVER, gate.onTouch(ACTION_UP, true, false, true, false));
        assertEquals(DELIVER, gate.onTouch(ACTION_DOWN, true, false, true, false));
    }
}
