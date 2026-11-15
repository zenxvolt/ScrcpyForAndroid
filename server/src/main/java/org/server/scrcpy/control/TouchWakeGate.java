package org.server.scrcpy.control;

import android.view.MotionEvent;

/** Keeps the decision to deliver or consume a touch consistent for its entire gesture. */
public final class TouchWakeGate {
    public enum Result { DELIVER, CONSUME, WAKE_UP, WAKE_AND_DELIVER }

    private boolean consuming;

    public Result onTouch(int action, boolean screenOn, boolean proximity) {
        return onTouch(action, screenOn, proximity, false, false);
    }

    public Result onTouch(int action, boolean screenOn, boolean proximity, boolean screenOffMode, boolean panelOff) {
        int maskedAction = action & MotionEvent.ACTION_MASK;
        if (maskedAction == MotionEvent.ACTION_DOWN) {
            // A new DOWN also recovers from a missing UP/CANCEL in the previous gesture.
            boolean wake = !screenOn && !proximity && !(screenOffMode && panelOff);
            consuming = wake && !screenOffMode;
            if (wake) {
                return consuming ? Result.WAKE_UP : Result.WAKE_AND_DELIVER;
            }
            return Result.DELIVER;
        }
        if (consuming) {
            if (maskedAction == MotionEvent.ACTION_UP || maskedAction == MotionEvent.ACTION_CANCEL) {
                consuming = false;
            }
            return Result.CONSUME;
        }
        return Result.DELIVER;
    }
}
