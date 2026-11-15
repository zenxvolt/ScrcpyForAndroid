package org.server.scrcpy.wrappers;

import android.os.IBinder;
import android.os.IInterface;
import android.view.Display;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DisplayManagerStateTest {
    private static final class FakeDisplayInfo {
        public int state = Display.STATE_ON;
    }

    private static final class PrivateDisplayService implements IInterface {
        FakeDisplayInfo info = new FakeDisplayInfo();
        int requestedDisplay = -1;

        public FakeDisplayInfo getDisplayInfo(int displayId) {
            requestedDisplay = displayId;
            return info;
        }

        @Override
        public IBinder asBinder() { return null; }
    }

    @Test
    public void queriesTheControlledDisplayThroughNonPublicProxy() {
        PrivateDisplayService service = new PrivateDisplayService();
        DisplayManager manager = new DisplayManager(service);
        assertEquals(Display.STATE_ON, manager.getDisplayState(7));
        assertEquals(7, service.requestedDisplay);
    }

    @Test
    public void readsPowerStateChangesWithoutCachingOldState() {
        PrivateDisplayService service = new PrivateDisplayService();
        DisplayManager manager = new DisplayManager(service);
        assertEquals(Display.STATE_ON, manager.getDisplayState(0));
        service.info.state = Display.STATE_OFF;
        assertEquals(Display.STATE_OFF, manager.getDisplayState(0));
        service.info.state = Display.STATE_ON;
        assertEquals(Display.STATE_ON, manager.getDisplayState(0));
    }

    @Test
    public void missingDisplayIsUnknownRatherThanOff() {
        PrivateDisplayService service = new PrivateDisplayService();
        service.info = null;
        assertEquals(Display.STATE_UNKNOWN, new DisplayManager(service).getDisplayState(0));
    }
}
