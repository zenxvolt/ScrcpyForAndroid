package org.server.scrcpy.wrappers;

import android.hardware.display.VirtualDisplay;
import android.os.IInterface;
import android.view.Display;
import android.view.Surface;

import org.server.scrcpy.DisplayInfo;
import org.server.scrcpy.Ln;
import org.server.scrcpy.Size;
import org.server.scrcpy.util.Command;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
public final class DisplayManager {
    private final IInterface manager;

    private Method createVirtualDisplayMethod;
    private boolean displayStateWarningLogged;

    public DisplayManager(IInterface manager) {
        this.manager = manager;
    }

    /** Query the controlled physical display, not a mirror's global interactivity. */
    public int getDisplayState(int displayId) {
        try {
            Method method = manager.getClass().getMethod("getDisplayInfo", int.class);
            method.setAccessible(true);
            Object info = method.invoke(manager, displayId);
            if (info == null) {
                return Display.STATE_UNKNOWN;
            }
            Field state = info.getClass().getField("state");
            state.setAccessible(true);
            return state.getInt(info);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!displayStateWarningLogged) {
                displayStateWarningLogged = true;
                Ln.w("Display state query unavailable for displayId=" + displayId + ": " + e);
            }
            return Display.STATE_UNKNOWN;
        }
    }

    private static DisplayInfo getDisplayInfoFromDumpsysDisplay(int displayId) {
        try {
            String dumpsysDisplayOutput = Command.execReadOutput("dumpsys", "display");
            return parseDisplayInfo(dumpsysDisplayOutput, displayId);
        } catch (Exception e) {
            Ln.e("Could not get display info from \"dumpsys display\" output", e);
            return null;
        }
    }

    public static DisplayInfo parseDisplayInfo(String dumpsysDisplayOutput, int displayId) {
        Pattern regex = Pattern.compile(
                "^    mOverrideDisplayInfo=DisplayInfo\\{\".*?, displayId " + displayId + ".*?(, FLAG_.*)?, real ([0-9]+) x ([0-9]+).*?, "
                        + "rotation ([0-9]+).*?, layerStack ([0-9]+)",
                Pattern.MULTILINE);
        Matcher m = regex.matcher(dumpsysDisplayOutput);
        if (!m.find()) {
            return null;
        }
        int flags = parseDisplayFlags(m.group(1));
        int width = Integer.parseInt(m.group(2));
        int height = Integer.parseInt(m.group(3));
        int rotation = Integer.parseInt(m.group(4));
        int layerStack = Integer.parseInt(m.group(5));

        return new DisplayInfo(displayId, new Size(width, height), rotation, layerStack, flags);
    }

    private static int parseDisplayFlags(String text) {
        Pattern regex = Pattern.compile("FLAG_[A-Z_]+");
        if (text == null) {
            return 0;
        }

        int flags = 0;
        Matcher m = regex.matcher(text);
        while (m.find()) {
            String flagString = m.group();
            try {
                Field filed = Display.class.getDeclaredField(flagString);
                flags |= filed.getInt(null);
            } catch (ReflectiveOperationException e) {
                // Silently ignore, some flags reported by "dumpsys display" are @TestApi
            }
        }
        return flags;
    }


    public DisplayInfo getDisplayInfo() {
        try {
            Object displayInfo = manager.getClass().getMethod("getDisplayInfo", int.class).invoke(manager, 0);
            Class<?> cls = displayInfo.getClass();
            // width and height already take the rotation into account
            int width = cls.getDeclaredField("logicalWidth").getInt(displayInfo);
            int height = cls.getDeclaredField("logicalHeight").getInt(displayInfo);
            int rotation = cls.getDeclaredField("rotation").getInt(displayInfo);
            return new DisplayInfo(new Size(width, height), rotation);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public DisplayInfo getDisplayInfo(int displayId) {
        try {
            Object displayInfo = manager.getClass().getMethod("getDisplayInfo", int.class).invoke(manager, displayId);
            if (displayInfo == null) {
                // fallback when displayInfo is null
                return getDisplayInfoFromDumpsysDisplay(displayId);
            }
            Class<?> cls = displayInfo.getClass();
            // width and height already take the rotation into account
            int width = cls.getDeclaredField("logicalWidth").getInt(displayInfo);
            int height = cls.getDeclaredField("logicalHeight").getInt(displayInfo);
            int rotation = cls.getDeclaredField("rotation").getInt(displayInfo);
            int layerStack = cls.getDeclaredField("layerStack").getInt(displayInfo);
            int flags = cls.getDeclaredField("flags").getInt(displayInfo);
            int densityDpi = 160;
            try {
                densityDpi = cls.getDeclaredField("logicalDensityDpi").getInt(displayInfo);
            } catch (Exception ignored) {
            }
            return new DisplayInfo(displayId, new Size(width, height), rotation, layerStack, flags, densityDpi);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
    public int[] getDisplayIds() {
        try {
            return (int[]) manager.getClass().getMethod("getDisplayIds").invoke(manager);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private Method getCreateVirtualDisplayMethod() throws NoSuchMethodException {
        if (createVirtualDisplayMethod == null) {
            createVirtualDisplayMethod = android.hardware.display.DisplayManager.class
                    .getMethod("createVirtualDisplay", String.class, int.class, int.class, int.class, Surface.class);
        }
        return createVirtualDisplayMethod;
    }

    public VirtualDisplay createVirtualDisplay(String name, int width, int height, int displayIdToMirror, Surface surface) throws Exception {
        Method method = getCreateVirtualDisplayMethod();
        return (VirtualDisplay) method.invoke(null, name, width, height, displayIdToMirror, surface);
    }

    public VirtualDisplay createNewVirtualDisplay(String name, int width, int height, int dpi, Surface surface, int flags) throws Exception {
        java.lang.reflect.Constructor<android.hardware.display.DisplayManager> ctor =
                android.hardware.display.DisplayManager.class.getDeclaredConstructor(android.content.Context.class);
        ctor.setAccessible(true);
        android.hardware.display.DisplayManager dm = ctor.newInstance(org.server.scrcpy.util.FakeContext.get());
        return dm.createVirtualDisplay(name, width, height, dpi, surface, flags);
    }

    private static final int VIRTUAL_DISPLAY_FLAG_PUBLIC = 0x00000001;
    private static final int VIRTUAL_DISPLAY_FLAG_SECURE = 0x00000004;
    private static final int VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR = 0x00000010;

    /**
     * Mirror of the default display that also shows secure content (lock screen
     * PIN pad, FLAG_SECURE apps). Needs CAPTURE_SECURE_VIDEO_OUTPUT, which only
     * root/system have, so this is used only when the server runs as root.
     */
    public VirtualDisplay createSecureMirrorVirtualDisplay(String name, int width, int height, int dpi, Surface surface)
            throws Exception {
        int density = dpi > 0 ? dpi : 160;
        int[] flagSets = {
                VIRTUAL_DISPLAY_FLAG_SECURE | VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                VIRTUAL_DISPLAY_FLAG_SECURE | VIRTUAL_DISPLAY_FLAG_PUBLIC | VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        };
        // Root has no package: try the shell package first, then no package name at all.
        android.content.Context[] contexts = {
                org.server.scrcpy.util.FakeContext.get(),
                new android.content.ContextWrapper(org.server.scrcpy.util.FakeContext.get()) {
                    @Override
                    public String getPackageName() {
                        return null;
                    }

                    @Override
                    public String getOpPackageName() {
                        return null;
                    }
                },
        };
        Exception last = null;
        for (android.content.Context context : contexts) {
            for (int flags : flagSets) {
                try {
                    java.lang.reflect.Constructor<android.hardware.display.DisplayManager> ctor =
                            android.hardware.display.DisplayManager.class.getDeclaredConstructor(android.content.Context.class);
                    ctor.setAccessible(true);
                    android.hardware.display.DisplayManager dm = ctor.newInstance(context);
                    VirtualDisplay display = dm.createVirtualDisplay(name, width, height, density, surface, flags);
                    if (display != null) {
                        Ln.i("Secure VirtualDisplay created, flags=0x" + Integer.toHexString(flags));
                        return display;
                    }
                } catch (Exception e) {
                    last = e;
                    Ln.w("Secure VirtualDisplay flags=0x" + Integer.toHexString(flags) + " failed: " + e);
                }
            }
        }
        throw last != null ? last : new IllegalStateException("Secure VirtualDisplay not created");
    }

    public VirtualDisplay createMirrorVirtualDisplay(String name, int width, int height, int dpi, Surface surface) throws Exception {
        int density = dpi > 0 ? dpi : 160;
        Ln.i("Creating AUTO_MIRROR VirtualDisplay dpi=" + density + " " + width + "x" + height);
        try {
            return createNewVirtualDisplay(name, width, height, density, surface, 0x00000010);
        } catch (Exception e) {
            Ln.e("AUTO_MIRROR VirtualDisplay failed, trying PUBLIC|AUTO_MIRROR", e);
            return createNewVirtualDisplay(name, width, height, density, surface, 0x00000001 | 0x00000010);
        }
    }
}
