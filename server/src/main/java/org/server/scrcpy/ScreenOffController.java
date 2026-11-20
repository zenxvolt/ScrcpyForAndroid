package org.server.scrcpy;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import org.server.scrcpy.wrappers.DisplayPower;
import org.server.scrcpy.wrappers.InputManager;
import org.server.scrcpy.device.ScreenState;

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
 * When the server exits (disconnect), the target is put to sleep instead of
 * lighting the panel back up; Android restores the brightness itself on the
 * next wake-up. The panel is only restored directly if sleeping fails.
 */
public final class ScreenOffController {

    private static final String[] BACKLIGHT_CLASS_DIRS = {"/sys/class/backlight"};
    private static final String[] BACKLIGHT_EXTRA_NODES = {"/sys/class/leds/lcd-backlight/brightness"};

    private static final Map<File, String> savedBacklight = new LinkedHashMap<>();
    private static boolean panelModeUsed = false;
    private static volatile boolean panelModeUnsupported = false;
    private static Thread keeper;
    private static Device device;
    private static List<File> backlightNodes = new ArrayList<>();

    /** Backlight polling: fast right after a wake-up, when Android re-applies the brightness. */
    static final long FAST_POLL_MS = 16;
    static final long SLOW_POLL_MS = 100;
    static final long FAST_POLL_WINDOW_MS = 3000;
    private static final long SLEEP_TIMEOUT_MS = 2000;

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
        ScreenOffController.device = device;
        backlightNodes = isRoot() ? findBacklightNodes() : new ArrayList<>();
        final boolean useBacklight = !backlightNodes.isEmpty();
        Ln.i("Screen off mode: " + (useBacklight ? "backlight (root)" : "panel power mode"));
        keeper = new Thread(() -> {
            boolean first = true;
            while (true) {
                long sinceWake = SystemClock.uptimeMillis() - wakeTracker.lastWakeAt();
                if (useBacklight) {
                    // Android rewrites the brightness on wake-up and during its dim
                    // animation: overwrite it before the panel visibly lights up.
                    SystemClock.sleep(pollIntervalMs(sinceWake));
                } else {
                    SystemClock.sleep(1000);
                }
                ScreenState screenState = device.getScreenState();
                // Detect delayed sleep too, not only the first panel-off attempt.
                // Otherwise the next touch wakes the device and the keeper turns
                // it off again, producing a wake/off loop on the lock screen.
                if (!useBacklight && isPanelOff() && screenState == ScreenState.OFF) {
                    disablePanelMode();
                    continue;
                }
                if (screenState != ScreenState.ON) {
                    continue;
                }
                if (useBacklight) {
                    backlightOff(first);
                } else if (SystemClock.uptimeMillis() - wakeTracker.lastWakeAt() < 1500) {
                    // Panel power mode: leave time for a wake-up to finish first,
                    // switching the panel off mid wake-up makes some ROMs sleep.
                    continue;
                } else if (!panelModeUnsupported) {
                    panelOff(device, first);
                }
                first = false;
            }
        }, "screen-off-keeper");
        keeper.setDaemon(true);
        keeper.start();
    }

    static long pollIntervalMs(long sinceWakeMs) {
        return sinceWakeMs >= 0 && sinceWakeMs < FAST_POLL_WINDOW_MS ? FAST_POLL_MS : SLOW_POLL_MS;
    }

    /**
     * Called when the server exits. The user asked for a dark screen, so a
     * disconnect must not light it up: lock the target instead. Safe to call
     * several times.
     */
    public static void restore() {
        Device target;
        synchronized (ScreenOffController.class) {
            target = device;
            if (target == null || (savedBacklight.isEmpty() && !panelModeUsed)) {
                restorePanel();
                return;
            }
        }
        // Not under the lock: the keeper must keep the panel dark while the
        // device goes to sleep.
        ScreenState state = target.getScreenState();
        boolean asleep = state == ScreenState.OFF || (state == ScreenState.ON && goToSleep(target));
        synchronized (ScreenOffController.class) {
            if (asleep) {
                // Android turns the display off itself and re-applies the
                // brightness and panel mode on the next wake-up. Writing them
                // back now would light the panel.
                if (!savedBacklight.isEmpty() || panelModeUsed) {
                    Ln.i("Disconnected: target screen left off (was " + state + ")");
                }
                savedBacklight.clear();
                panelModeUsed = false;
            } else {
                restorePanel();
            }
        }
    }

    private static boolean goToSleep(Device device) {
        long now = SystemClock.uptimeMillis();
        boolean injected = injectKey(device, KeyEvent.ACTION_DOWN, now)
                && injectKey(device, KeyEvent.ACTION_UP, now);
        if (!injected) {
            return false;
        }
        long deadline = SystemClock.uptimeMillis() + SLEEP_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50);
            if (device.getScreenState() == ScreenState.OFF) {
                return true;
            }
        }
        Ln.w("Target did not go to sleep: turning its screen back on");
        return false;
    }

    private static boolean injectKey(Device device, int action, long time) {
        KeyEvent event = new KeyEvent(time, time, action, KeyEvent.KEYCODE_SLEEP, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        return device.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
    }

    private static synchronized void restorePanel() {
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
        for (File node : backlightNodes) {
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

    public static synchronized boolean isPanelOff() {
        return panelModeUsed;
    }

    private static synchronized void disablePanelMode() {
        panelModeUnsupported = true;
        Ln.w("This ROM puts the device to sleep when the panel is turned off: "
                + "screen off mode disabled (enable Root mode to use the backlight method)");
        DisplayPower.setPowerMode(DisplayPower.POWER_MODE_NORMAL);
        panelModeUsed = false;
    }

    private static void panelOff(Device device, boolean log) {
        synchronized (ScreenOffController.class) {
            if (!DisplayPower.setPowerMode(DisplayPower.POWER_MODE_OFF, log)) {
                return;
            }
            panelModeUsed = true;
        }
        if (log) {
            // Some ROMs (MIUI) put the device to sleep when the panel is switched off:
            // touching would then wake it and blink forever. Detect it and give up.
            SystemClock.sleep(2500);
            if (device.getScreenState() == ScreenState.OFF) {
                disablePanelMode();
            }
        }
    }

    /** Gives the keeper the time of the last wake-up we triggered. */
    public interface WakeTracker {
        long lastWakeAt();
    }
}
