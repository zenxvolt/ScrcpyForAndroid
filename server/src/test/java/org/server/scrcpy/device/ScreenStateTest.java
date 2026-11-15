package org.server.scrcpy.device;

import android.view.Display;
import android.view.MotionEvent;
import org.junit.Test;
import org.server.scrcpy.control.TouchWakeGate;

import static org.junit.Assert.assertEquals;
import static org.server.scrcpy.device.ScreenState.*;

public class ScreenStateTest {
    @Test
    public void visibleLockscreenOverridesNonInteractivePowerReport() {
        assertEquals(ON, resolve(Display.STATE_ON, false));
        TouchWakeGate gate = new TouchWakeGate();
        for (int i = 0; i < 10; ++i) {
            boolean screenOn = resolve(Display.STATE_ON, false) != OFF;
            assertEquals(TouchWakeGate.Result.DELIVER, gate.onTouch(MotionEvent.ACTION_DOWN, screenOn, false));
            assertEquals(TouchWakeGate.Result.DELIVER, gate.onTouch(MotionEvent.ACTION_UP, screenOn, false));
        }
    }

    @Test
    public void unknownIsNotAnOffSignal() {
        assertEquals(UNKNOWN, resolve(Display.STATE_UNKNOWN, null));
        TouchWakeGate gate = new TouchWakeGate();
        boolean screenOn = resolve(Display.STATE_UNKNOWN, null) != OFF;
        assertEquals(TouchWakeGate.Result.DELIVER, gate.onTouch(MotionEvent.ACTION_DOWN, screenOn, false));
        assertEquals(TouchWakeGate.Result.DELIVER, gate.onTouch(MotionEvent.ACTION_UP, screenOn, false));
    }

    @Test
    public void knownOffAndDozingDisplayCanStillWake() {
        for (int state : new int[]{Display.STATE_OFF, Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND}) {
            assertEquals(OFF, resolve(state, true));
            TouchWakeGate gate = new TouchWakeGate();
            assertEquals(TouchWakeGate.Result.WAKE_UP, gate.onTouch(MotionEvent.ACTION_DOWN,
                    resolve(state, null) != OFF, false));
        }
    }

    @Test
    public void onVariantsDoNotAutomaticallyWake() {
        assertEquals(ON, resolve(Display.STATE_VR, false));
        assertEquals(ON, resolve(Display.STATE_ON_SUSPEND, false));
    }

    @Test
    public void powerServiceIsFallbackOnlyWhenDisplayStateIsUnavailable() {
        assertEquals(ON, resolve(Display.STATE_UNKNOWN, true));
        assertEquals(OFF, resolve(Display.STATE_UNKNOWN, false));
        assertEquals(UNKNOWN, resolve(999, null));
    }

    @Test
    public void intentionallyOffPanelDoesNotCreateWakeLoop() {
        TouchWakeGate gate = new TouchWakeGate();
        for (int i = 0; i < 10; ++i) {
            boolean screenOn = resolve(Display.STATE_OFF, false) != OFF;
            assertEquals(TouchWakeGate.Result.DELIVER,
                    gate.onTouch(MotionEvent.ACTION_DOWN, screenOn, false, true, true));
            assertEquals(TouchWakeGate.Result.DELIVER,
                    gate.onTouch(MotionEvent.ACTION_UP, screenOn, false, true, true));
        }
    }
}
