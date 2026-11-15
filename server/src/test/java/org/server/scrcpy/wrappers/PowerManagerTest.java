package org.server.scrcpy.wrappers;

import android.os.IBinder;
import android.os.IInterface;
import android.view.Display;
import android.view.MotionEvent;
import org.junit.Test;
import org.server.scrcpy.control.TouchWakeGate;
import org.server.scrcpy.device.ScreenState;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class PowerManagerTest {
    // Mimic the non-public Binder proxy used by Android's power service.
    private static final class PrivatePowerService implements IInterface {
        boolean interactive = true;
        boolean fail;

        public boolean isInteractive() {
            if (fail) {
                throw new SecurityException("power state query denied");
            }
            return interactive;
        }

        @Override
        public IBinder asBinder() {
            return null;
        }
    }

    @Test
    public void readsNonPublicBinderProxy() {
        PrivatePowerService service = new PrivatePowerService();
        List<String> warnings = new ArrayList<>();
        PowerManager manager = new PowerManager(service, warnings::add);
        assertEquals(Boolean.TRUE, manager.isInteractive());
        assertTrue(warnings.isEmpty());
    }

    @Test
    public void actualSleepingStateIsPreserved() {
        PrivatePowerService service = new PrivatePowerService();
        service.interactive = false;
        PowerManager manager = new PowerManager(service, message -> fail(message));
        assertEquals(Boolean.FALSE, manager.isInteractive());
    }

    @Test
    public void invocationFailureDoesNotBecomeRepeatedWakeRequests() {
        PrivatePowerService service = new PrivatePowerService();
        service.fail = true;
        List<String> warnings = new ArrayList<>();
        PowerManager manager = new PowerManager(service, warnings::add);
        TouchWakeGate gate = new TouchWakeGate();
        for (int i = 0; i < 10; ++i) {
            Boolean interactive = manager.isInteractive();
            assertNull(interactive);
            ScreenState state = ScreenState.resolve(Display.STATE_UNKNOWN, interactive);
            assertEquals(TouchWakeGate.Result.DELIVER,
                    gate.onTouch(MotionEvent.ACTION_DOWN, state != ScreenState.OFF, false));
            assertEquals(TouchWakeGate.Result.DELIVER,
                    gate.onTouch(MotionEvent.ACTION_UP, state != ScreenState.OFF, false));
        }
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("SecurityException"));
    }

    @Test
    public void queryRecoversAfterTemporaryFailure() {
        PrivatePowerService service = new PrivatePowerService();
        service.fail = true;
        PowerManager manager = new PowerManager(service, message -> { });
        assertNull(manager.isInteractive());
        service.fail = false;
        assertEquals(Boolean.TRUE, manager.isInteractive());
    }

    @Test
    public void missingMethodIsUnknownInsteadOfCrashingOrReportingSleep() {
        IInterface service = new IInterface() {
            @Override
            public IBinder asBinder() { return null; }
        };
        List<String> warnings = new ArrayList<>();
        PowerManager manager = new PowerManager(service, warnings::add);
        assertNull(manager.isInteractive());
        assertEquals(1, warnings.size());
    }
}
