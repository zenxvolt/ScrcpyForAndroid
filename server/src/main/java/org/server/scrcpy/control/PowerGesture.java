package org.server.scrcpy.control;

import android.view.KeyEvent;
import android.view.MotionEvent;

/** Recognizes power shortcuts without consuming any part of the touch stream. */
public final class PowerGesture {
    private static final long TAP_TIMEOUT_MS = 250;
    private final double touchSlopSquared;
    private final double doubleTapSlopSquared;
    private int gestureKey = KeyEvent.KEYCODE_UNKNOWN;
    private boolean tracking;
    private long downTime;
    private int downX;
    private int downY;
    private long lastTapTime = -1;
    private int lastTapX;
    private int lastTapY;

    public PowerGesture(int densityDpi) {
        double scale = Math.max(1, densityDpi) / 160.0;
        touchSlopSquared = Math.pow(8 * scale, 2);
        doubleTapSlopSquared = Math.pow(100 * scale, 2);
    }

    public int onTouch(int action, int x, int y, long now, boolean screenOn, boolean proximity) {
        int key = !screenOn ? KeyEvent.KEYCODE_WAKEUP
                : proximity ? KeyEvent.KEYCODE_SLEEP : KeyEvent.KEYCODE_UNKNOWN;
        if (key != gestureKey) {
            reset();
            gestureKey = key;
        }
        if (key == KeyEvent.KEYCODE_UNKNOWN) {
            return KeyEvent.KEYCODE_UNKNOWN;
        }

        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                if (tracking) {
                    lastTapTime = -1;
                }
                tracking = true;
                downTime = now;
                downX = x;
                downY = y;
                break;
            case MotionEvent.ACTION_MOVE:
                if (tracking && distanceSquared(x, y, downX, downY) > touchSlopSquared) {
                    reset();
                }
                break;
            case MotionEvent.ACTION_UP:
                if (!tracking || now - downTime > TAP_TIMEOUT_MS
                        || distanceSquared(x, y, downX, downY) > touchSlopSquared) {
                    reset();
                    break;
                }
                tracking = false;
                if (lastTapTime >= 0 && now - lastTapTime < TAP_TIMEOUT_MS
                        && distanceSquared(x, y, lastTapX, lastTapY) <= doubleTapSlopSquared) {
                    reset();
                    return key;
                }
                lastTapTime = now;
                lastTapX = x;
                lastTapY = y;
                break;
            default:
                // Cancel and multi-finger gestures must never become power shortcuts.
                reset();
                break;
        }
        return KeyEvent.KEYCODE_UNKNOWN;
    }

    public void reset() {
        tracking = false;
        lastTapTime = -1;
    }

    private static double distanceSquared(int x, int y, int otherX, int otherY) {
        double dx = (double) x - otherX;
        double dy = (double) y - otherY;
        return dx * dx + dy * dy;
    }
}
