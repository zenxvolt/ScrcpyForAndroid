package org.server.scrcpy.wrappers;

import android.os.IInterface;
import org.server.scrcpy.Ln;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
public final class PowerManager {
    private final IInterface manager;
    private final Method isInteractiveMethod;
    private final WarningLogger logger;
    private boolean warningLogged;

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

    private void warnOnce(Throwable cause) {
        if (!warningLogged) {
            warningLogged = true;
            logger.warn("Power state query unavailable; not treating it as screen off: " + cause);
        }
    }
}
