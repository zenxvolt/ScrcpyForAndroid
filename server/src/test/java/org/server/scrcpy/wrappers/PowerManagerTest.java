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

    // IPowerManager on API 28+.
    private static final class WakeablePowerService implements IInterface {
        final List<Object[]> wakeCalls = new ArrayList<>();
        boolean deny;

        public boolean isInteractive() {
            return false;
        }

        public void wakeUp(long time, int reason, String details, String opPackageName) {
            if (deny) {
                throw new SecurityException("Neither user nor current process has DEVICE_POWER");
            }
            wakeCalls.add(new Object[]{time, reason, details, opPackageName});
        }

        @Override
        public IBinder asBinder() {
            return null;
        }
    }

    // IPowerManager on API 21-27.
    private static final class LegacyWakeablePowerService implements IInterface {
        final List<Object[]> wakeCalls = new ArrayList<>();

        public void wakeUp(long time, String reason, String opPackageName) {
            wakeCalls.add(new Object[]{time, reason, opPackageName});
        }

        @Override
        public IBinder asBinder() {
            return null;
        }
    }

    @Test
    public void wakesThroughPowerServiceInsteadOfKeyPolicy() {
        WakeablePowerService service = new WakeablePowerService();
        PowerManager manager = new PowerManager(service, message -> fail(message));
        assertTrue(manager.wakeUp(1234L));
        assertEquals(1, service.wakeCalls.size());
        Object[] call = service.wakeCalls.get(0);
        assertEquals(1234L, call[0]);
        assertEquals(PowerManager.WAKE_REASON_APPLICATION, call[1]);
        assertEquals(PowerManager.WAKE_DETAILS, call[2]);
        assertEquals(PowerManager.OP_PACKAGE, call[3]);
    }

    @Test
    public void wakesThroughLegacyPowerService() {
        LegacyWakeablePowerService service = new LegacyWakeablePowerService();
        PowerManager manager = new PowerManager(service, message -> { });
        assertTrue(manager.wakeUp(99L));
        assertEquals(1, service.wakeCalls.size());
        assertEquals(99L, service.wakeCalls.get(0)[0]);
    }

    @Test
    public void deniedWakeReportsFailureSoCallerCanUsePowerKey() {
        WakeablePowerService service = new WakeablePowerService();
        service.deny = true;
        List<String> warnings = new ArrayList<>();
        PowerManager manager = new PowerManager(service, warnings::add);
        assertFalse(manager.wakeUp(1L));
        assertFalse(manager.wakeUp(2L));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("DEVICE_POWER"));
    }

    @Test
    public void missingWakeMethodReportsFailure() {
        PowerManager manager = new PowerManager(new PrivatePowerService(), message -> { });
        assertFalse(manager.wakeUp(1L));
    }
}
