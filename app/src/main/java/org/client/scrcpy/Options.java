package org.client.scrcpy;

import android.text.TextUtils;

public class Options {

    public static final String CODEC_H264 = "h264";
    public static final String CODEC_H265 = "h265";
    public static final String DEFAULT_ENCODER = "-";

    /** Streaming priority: keep the defaults / keep the frame rate / keep the picture quality. */
    public static final String PRIORITY_BALANCED = "balanced";
    public static final String PRIORITY_FPS = "fps";
    public static final String PRIORITY_QUALITY = "quality";

    private String ip;
    private int maxSize;
    private int bitRate;
    private boolean tunnelForward;
    private boolean enableAudioForward = true;
    private String videoCodec = CODEC_H264;
    private String videoEncoder = DEFAULT_ENCODER;
    private int frameRate = 60;
    private String priority = PRIORITY_BALANCED;

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public boolean isEnableAudioForward() {
        return enableAudioForward;
    }

    public void setEnableAudioForward(boolean enableAudioForward) {
        this.enableAudioForward = enableAudioForward;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

    public int getBitRate() {
        return bitRate;
    }

    public void setBitRate(int bitRate) {
        this.bitRate = bitRate;
    }

    public boolean isTunnelForward() {
        return tunnelForward;
    }

    public void setTunnelForward(boolean tunnelForward) {
        this.tunnelForward = tunnelForward;
    }

    public String getVideoCodec() {
        return videoCodec;
    }

    public void setVideoCodec(String videoCodec) {
        this.videoCodec = normalizeCodec(videoCodec);
    }

    public String getVideoEncoder() {
        return videoEncoder;
    }

    public void setVideoEncoder(String videoEncoder) {
        if (TextUtils.isEmpty(videoEncoder) || DEFAULT_ENCODER.equals(videoEncoder)) {
            this.videoEncoder = DEFAULT_ENCODER;
        } else {
            this.videoEncoder = videoEncoder.trim();
        }
    }

    public int getFrameRate() {
        return frameRate;
    }

    public void setFrameRate(int frameRate) {
        this.frameRate = frameRate > 0 ? frameRate : 60;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        if (PRIORITY_FPS.equals(priority) || PRIORITY_QUALITY.equals(priority)) {
            this.priority = priority;
        } else {
            this.priority = PRIORITY_BALANCED;
        }
    }

    public String getVideoMimeType() {
        return mimeForCodec(videoCodec);
    }

    public boolean hasCustomEncoder() {
        return !TextUtils.isEmpty(videoEncoder) && !DEFAULT_ENCODER.equals(videoEncoder);
    }

    public static String normalizeCodec(String codec) {
        if (codec == null) {
            return CODEC_H264;
        }
        String value = codec.trim().toLowerCase();
        if ("h265".equals(value) || "hevc".equals(value)) {
            return CODEC_H265;
        }
        return CODEC_H264;
    }

    public static String mimeForCodec(String codec) {
        return CODEC_H265.equals(normalizeCodec(codec)) ? "video/hevc" : "video/avc";
    }

    /**
     * Restore options from server args. Extra args are optional for backward compatibility.
     */
    public static Options createOptions(String[] args) {
        Options options = new Options();
        options.ip = args[0];
        options.maxSize = Integer.parseInt(args[1]) & ~7;
        options.bitRate = Integer.parseInt(args[2]);
        options.tunnelForward = Boolean.parseBoolean(args[3]);
        options.enableAudioForward = Boolean.parseBoolean(args[4]);
        if (args.length > 5) {
            options.setVideoCodec(args[5]);
        }
        if (args.length > 6) {
            options.setVideoEncoder(args[6]);
        }
        if (args.length > 7) {
            options.setFrameRate(Integer.parseInt(args[7]));
        }
        if (args.length > 8) {
            options.setPriority(args[8]);
        }

        return options;
    }

    /**
     * Serialize options as args for the scrcpy server process.
     */
    public String[] optionsToArgs() {
        return new String[]{
                ip,
                Long.toString(maxSize),
                Long.toString(bitRate),
                String.valueOf(tunnelForward),
                String.valueOf(enableAudioForward),
                videoCodec,
                hasCustomEncoder() ? videoEncoder : DEFAULT_ENCODER,
                Integer.toString(frameRate),
                priority
        };
    }
}
