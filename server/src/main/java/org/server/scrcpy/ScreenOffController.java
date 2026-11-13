package org.server.scrcpy;

import android.os.SystemClock;

import org.server.scrcpy.wrappers.DisplayPower;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the target's physical screen dark while it is mirrored ("turn screen
 * off" option), with two strategies:
 * <ul>
 *     <li><b>Backlight (root)</b>: write 0 to the panel backlight in sysfs.
 *     Android still considers the screen on, so it never goes to sleep and
 *     touches keep working. Needed on MIUI, which puts the device to sleep as
 *     soon as the panel power mode is switched off.</li>
 *     <li><b>Panel power mode (shell)</b>: SurfaceControl.setDisplayPowerMode(OFF),
 *     like scrcpy's --turn-screen-off. If the ROM reacts by putting the device
 *     to sleep, the mode is given up instead of blinking forever.</li>
 * </ul>
 * Everything is restored when the server exits.
 */
public final class ScreenOffController {

    private static final String[] BACKLIGHT_CLASS_DIRS = {"/sys/class/backlight"};
    private static final String[] BACKLIGHT_EXTRA_NODES = {"/sys/class/leds/lcd-backlight/brightness"};

    private static final Map<File, String> savedBacklight = new LinkedHashMap<>();
    private static boolean panelModeUsed = false;
    private static volatile boolean panelModeUnsupported = false;
    private static Thread keeper;

    private ScreenOffController() {
    }

    private static boolean isRoot() {
        return android.os.Process.myUid() == 0;
    }

    /** Start keeping the screen dark (idempotent). */
    public static synchronized void start(final Device device, final WakeTracker wakeTracker) {
        if (keeper != null) {
            return;
        }
        final boolean useBacklight = isRoot() && !findBacklightNodes().isEmpty();
        Ln.i("Screen off mode: " + (useBacklight ? "backlight (root)" : "panel power mode"));
        keeper = new Thread(() -> {
            boolean first = true;
            while (true) {
                SystemClock.sleep(1000);
                // Leave time for a wake-up to finish before darkening the screen.
                if (SystemClock.uptimeMillis() - wakeTracker.lastWakeAt() < 1500 || !device.isScreenOn()) {
                    continue;
                }
                if (useBacklight) {
                    backlightOff(first);
                } else if (!panelModeUnsupported) {
                    panelOff(device, first);
                }
                first = false;
            }
        }, "screen-off-keeper");
        keeper.setDaemon(true);
        keeper.start();
    }

    /** Restore the screen; safe to call several times. */
    public static synchronized void restore() {
        for (Map.Entry<File, String> entry : savedBacklight.entrySet()) {
            write(entry.getKey(), entry.getValue());
            Ln.i("Backlight restored: " + entry.getKey() + " = " + entry.getValue());
        }
        savedBacklight.clear();
        if (panelModeUsed) {
            DisplayPower.setPowerMode(DisplayPower.POWER_MODE_NORMAL);
            panelModeUsed = false;
        }
    }

    // ------------------------------------------------------------------ backlight

    private static List<File> findBacklightNodes() {
        List<File> nodes = new ArrayList<>();
        for (String dir : BACKLIGHT_CLASS_DIRS) {
            File[] entries = new File(dir).listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    File brightness = new File(entry, "brightness");
                    if (brightness.exists()) {
                        nodes.add(brightness);
                    }
                }
            }
        }
        for (String path : BACKLIGHT_EXTRA_NODES) {
            File node = new File(path);
            if (node.exists() && !nodes.contains(node)) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    private static synchronized void backlightOff(boolean log) {
        for (File node : findBacklightNodes()) {
            String current = read(node);
            if (current == null) {
                continue;
            }
            if (!"0".equals(current)) {
                // Android may have set a new brightness meanwhile: remember the latest one.
                savedBacklight.put(node, current);
                if (write(node, "0") && log) {
                    Ln.i("Backlight off: " + node + " (was " + current + ")");
                }
            }
        }
    }

    private static String read(File node) {
        try (FileInputStream in = new FileInputStream(node)) {
            byte[] buf = new byte[32];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n).trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean write(File node, String value) {
        try (FileOutputStream out = new FileOutputStream(node)) {
            out.write(value.getBytes());
            return true;
        } catch (Exception e) {
            Ln.w("Cannot write " + node + ": " + e);
            return false;
        }
    }

    // ----------------------------------------------------------------- panel mode

    private static void panelOff(Device device, boolean log) {
        if (!DisplayPower.setPowerMode(DisplayPower.POWER_MODE_OFF, log)) {
            return;
        }
        synchronized (ScreenOffController.class) {
            panelModeUsed = true;
        }
        if (log) {
            // Some ROMs (MIUI) put the device to sleep when the panel is switched off:
            // touching would then wake it and blink forever. Detect it and give up.
            SystemClock.sleep(2500);
            if (!device.isScreenOn()) {
                panelModeUnsupported = true;
                Ln.w("This ROM puts the device to sleep when the panel is turned off: "
                        + "screen off mode disabled (enable Root mode to use the backlight method)");
                DisplayPower.setPowerMode(DisplayPower.POWER_MODE_NORMAL);
                synchronized (ScreenOffController.class) {
                    panelModeUsed = false;
                }
            }
        }
    }

    /** Gives the keeper the time of the last wake-up we triggered. */
    public interface WakeTracker {
        long lastWakeAt();
    }
}
