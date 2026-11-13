package org.server.scrcpy.wrappers;

import android.os.Build;
import android.os.IBinder;

import org.server.scrcpy.Ln;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the physical panel off (or back on) while Android keeps running and
 * rendering, like scrcpy's --turn-screen-off. The mirror keeps working because
 * the capture is a separate virtual display.
 */
public final class DisplayPower {

    public static final int POWER_MODE_OFF = 0;
    public static final int POWER_MODE_NORMAL = 2;

    private static Class<?> displayControlClass;

    private DisplayPower() {
    }

    /** @return true when the mode was applied to at least one physical display */
    public static boolean setPowerMode(int mode) {
        boolean applied = false;
        try {
            Class<?> surfaceControl = Class.forName("android.view.SurfaceControl");
            Method setMode = surfaceControl.getMethod("setDisplayPowerMode", IBinder.class, int.class);
            for (IBinder token : getPhysicalDisplayTokens(surfaceControl)) {
                try {
                    setMode.invoke(null, token, mode);
                    applied = true;
                } catch (Exception e) {
                    Ln.w("setDisplayPowerMode failed: " + e);
                }
            }
        } catch (Exception e) {
            Ln.w("Display power control unavailable: " + e);
        }
        Ln.i("Physical display power mode " + (mode == POWER_MODE_OFF ? "OFF" : "NORMAL")
                + (applied ? "" : " (not applied)"));
        return applied;
    }

    private static List<IBinder> getPhysicalDisplayTokens(Class<?> surfaceControl) {
        List<IBinder> tokens = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+: the methods moved to com.android.server.display.DisplayControl.
            try {
                Class<?> displayControl = getDisplayControlClass();
                long[] ids = (long[]) displayControl.getMethod("getPhysicalDisplayIds").invoke(null);
                Method getToken = displayControl.getMethod("getPhysicalDisplayToken", long.class);
                for (long id : ids) {
                    IBinder token = (IBinder) getToken.invoke(null, id);
                    if (token != null) {
                        tokens.add(token);
                    }
                }
                if (!tokens.isEmpty()) {
                    return tokens;
                }
            } catch (Throwable e) {
                Ln.w("DisplayControl unavailable: " + e);
            }
        }
        try {
            long[] ids = (long[]) surfaceControl.getMethod("getPhysicalDisplayIds").invoke(null);
            Method getToken = surfaceControl.getMethod("getPhysicalDisplayToken", long.class);
            for (long id : ids) {
                IBinder token = (IBinder) getToken.invoke(null, id);
                if (token != null) {
                    tokens.add(token);
                }
            }
            if (!tokens.isEmpty()) {
                return tokens;
            }
        } catch (Throwable ignored) {
            // Not available on this version.
        }
        try {
            IBinder token = (IBinder) surfaceControl.getMethod("getInternalDisplayToken").invoke(null);
            if (token != null) {
                tokens.add(token);
                return tokens;
            }
        } catch (Throwable ignored) {
            // Not available on this version.
        }
        try {
            IBinder token = (IBinder) surfaceControl.getMethod("getBuiltInDisplay", int.class).invoke(null, 0);
            if (token != null) {
                tokens.add(token);
            }
        } catch (Throwable ignored) {
            // Not available on this version.
        }
        return tokens;
    }

    private static synchronized Class<?> getDisplayControlClass() throws Exception {
        if (displayControlClass == null) {
            Class<?> factory = Class.forName("com.android.internal.os.ClassLoaderFactory");
            Method create = factory.getDeclaredMethod("createClassLoader", String.class, String.class, String.class,
                    ClassLoader.class, int.class, boolean.class, String.class);
            ClassLoader loader = (ClassLoader) create.invoke(null, "/system/framework/services.jar", null, null,
                    ClassLoader.getSystemClassLoader(), 0, true, null);
            Class<?> cls = loader.loadClass("com.android.server.display.DisplayControl");
            Method loadLibrary = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
            loadLibrary.setAccessible(true);
            loadLibrary.invoke(Runtime.getRuntime(), cls, "android_servers");
            displayControlClass = cls;
        }
        return displayControlClass;
    }
}
