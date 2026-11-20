package org.server.scrcpy.wrappers;

import android.os.IInterface;
import android.os.SystemClock;
import org.server.scrcpy.Ln;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
public final class PowerManager {
    /** android.os.PowerManager.WAKE_REASON_APPLICATION */
    static final int WAKE_REASON_APPLICATION = 2;
    static final String WAKE_DETAILS = "scrcpy";
    static final String OP_PACKAGE = "com.android.shell";

    private final IInterface manager;
    private final Method isInteractiveMethod;
    private final Method wakeUpMethod;
    private final WarningLogger logger;
    private boolean warningLogged;
    private boolean wakeWarningLogged;

    interface WarningLogger {
        void warn(String message);
    }

    public PowerManager(IInterface manager) {
        this(manager, Ln::w);
    }

    PowerManager(IInterface manager, WarningLogger logger) {
        this.manager = manager;
        this.logger = logger;
        Method method = null;
        try {
            // minSdk is 21. The Binder proxy may be a non-public implementation.
            method = manager.getClass().getMethod("isInteractive");
            method.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce(e);
        }
        isInteractiveMethod = method;
        wakeUpMethod = findWakeUpMethod(manager);
    }

    /**
     * IPowerManager.wakeUp() signatures:
     * API 28+: wakeUp(long time, int reason, String details, String opPackageName)
     * API 21-27: wakeUp(long time, String reason, String opPackageName)
     */
    private static Method findWakeUpMethod(IInterface manager) {
        for (Method m : manager.getClass().getMethods()) {
            if (!"wakeUp".equals(m.getName())) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            boolean modern = p.length == 4 && p[0] == long.class && p[1] == int.class
                    && p[2] == String.class && p[3] == String.class;
            boolean legacy = p.length == 3 && p[0] == long.class
                    && p[1] == String.class && p[2] == String.class;
            if (modern || legacy) {
                try {
                    m.setAccessible(true);
                } catch (RuntimeException ignored) {
                    // public interface method: still invocable
                }
                return m;
            }
        }
        return null;
    }

    /** null means unavailable, never a confirmed sleeping device. */
    public Boolean isInteractive() {
        if (isInteractiveMethod == null) {
            return null;
        }
        try {
            return (Boolean) isInteractiveMethod.invoke(manager);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce(e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e);
            return null;
        }
    }

    /**
     * Wakes the device through the power service instead of a key event.
     * <p>
     * An injected KEYCODE_WAKEUP goes through the window manager's key policy, and
     * MIUI/HyperOS cancels it right away ("proximity sensor too close") whenever the
     * proximity sensor reads near (phone face down, in a pocket, covered). Every touch
     * on the mirror then woke the target and put it back to sleep: the screen blinked
     * and the lock screen could not be swiped. The power service call is not subject
     * to that key policy.
     *
     * @return false if the call is unavailable, so the caller can fall back to a key.
     */
    public boolean wakeUp() {
        return wakeUp(SystemClock.uptimeMillis());
    }

    boolean wakeUp(long now) {
        if (wakeUpMethod == null) {
            return false;
        }
        try {
            if (wakeUpMethod.getParameterTypes().length == 4) {
                wakeUpMethod.invoke(manager, now, WAKE_REASON_APPLICATION, WAKE_DETAILS, OP_PACKAGE);
            } else {
                wakeUpMethod.invoke(manager, now, WAKE_DETAILS, OP_PACKAGE);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Throwable cause = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            if (!wakeWarningLogged) {
                wakeWarningLogged = true;
                logger.warn("IPowerManager.wakeUp unavailable, falling back to the power key: " + cause);
            }
            return false;
        }
    }

    private void warnOnce(Throwable cause) {
        if (!warningLogged) {
            warningLogged = true;
            logger.warn("Power state query unavailable; not treating it as screen off: " + cause);
        }
    }
}
