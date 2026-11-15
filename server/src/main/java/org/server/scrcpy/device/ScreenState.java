package org.server.scrcpy.device;

import android.view.Display;

/** Physical display state takes precedence over the global interactive flag. */
public enum ScreenState {
    ON, OFF, UNKNOWN;

    public static ScreenState resolve(int displayState, Boolean interactive) {
        switch (displayState) {
            case Display.STATE_ON:
            case Display.STATE_VR:
            case Display.STATE_ON_SUSPEND:
                return ON;
            case Display.STATE_OFF:
            case Display.STATE_DOZE:
            case Display.STATE_DOZE_SUSPEND:
                return OFF;
            default:
                return interactive == null ? UNKNOWN : interactive ? ON : OFF;
        }
    }
}
