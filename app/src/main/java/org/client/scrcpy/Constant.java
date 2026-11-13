package org.client.scrcpy;


public class Constant {

    public static final String AUDIO_FORWARD = "enable_audio_forward";
    public static final String CONTROL_NAV = "control_nav";
    public static final String CONTROL_NO = "no_control";
    public static final String CONTROL_REMOTE_ADDR = "control_remote_addr";

    public static final String PREFERENCE_SPINNER_RESOLUTION = "spinner_resolution";
    /** v2: higher bitrates were added at the top of options_bitrate_keys. */
    public static final String PREFERENCE_SPINNER_BITRATE = "spinner_bitrate_v2";
    public static final String PREFERENCE_SPINNER_BITRATE_LEGACY = "spinner_bitrate";
    /** Number of entries added before the old first entry (8 Mbps). */
    public static final int BITRATE_OPTIONS_ADDED = 6;
    public static final String PREFERENCE_SPINNER_PRIORITY = "spinner_stream_priority";
    public static final String ROOT_MODE = "root_mode";
    public static final String SCREEN_OFF = "turn_screen_off";

  /** Index of the "Auto" entry in {@code options_resolution_values}. */
    public static final int RESOLUTION_AUTO_INDEX = 0;

    public static final String PREFERENCE_SPINNER_DELAY = "delay_control";
    public static final String PREFERENCE_SPINNER_CODEC = "spinner_video_codec";
    public static final String PREFERENCE_SPINNER_FPS = "spinner_video_fps";
    public static final String PREFERENCE_VIDEO_ENCODER = "video_encoder";

    public static final String HISTORY_LIST_KEY = "history_list_key";
    public static final String USER_ID = "user_id";

}
