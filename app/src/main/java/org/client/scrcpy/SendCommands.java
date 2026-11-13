package org.client.scrcpy;


import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.client.scrcpy.utils.AdbHelper;
import org.client.scrcpy.utils.ThreadUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class SendCommands {

    public final static int WAIT_TIME = 15000;

    public enum CmdStatus {
        SUCCESS,
        RUNNING,
        ERROR
    }

    public SendCommands() {

    }

    public CmdStatus SendAdbCommands(Context context, final String ip, int port, int forwardport, String localip, int bitrate, int size, boolean enableAudio) {
        Options options = new Options();
        options.setIp(localip);
        options.setMaxSize(size);
        options.setBitRate(bitrate);
        options.setTunnelForward(true);
        options.setEnableAudioForward(enableAudio);
        return SendAdbCommands(context, ip, port, forwardport, options);
    }

    public CmdStatus SendAdbCommands(Context context, final byte[] fileBase64, final String ip, int port, int forwardport, String localip, int bitrate, int size, boolean enableAudio) {
        return SendAdbCommands(context, ip, port, forwardport, localip, bitrate, size, enableAudio);
    }

    /** Detailed reason of the last failed {@link #SendAdbCommands} call. */
    private volatile AdbHelper.ConnectResult lastFailure;
    private volatile String lastFailureStep = "";

    public AdbHelper.ConnectResult getLastFailure() {
        return lastFailure;
    }

    /** Short description of the step that failed (push / forward / start). */
    public String getLastFailureStep() {
        return lastFailureStep;
    }

    public CmdStatus SendAdbCommands(Context context, final String ip, int port, int forwardport, Options options) {
        return SendAdbCommands(context, ip, port, forwardport, options, null);
    }

    /**
     * Connect to {@code ip:port}, push the server, forward the port and start
     * the scrcpy server. Must be called from a worker thread.
     *
     * @param listener optional progress listener (string resource per step)
     */
    public CmdStatus SendAdbCommands(Context context, final String ip, int port, int forwardport, Options options,
                                     AdbHelper.ConnectListener listener) {
        lastFailure = null;
        lastFailureStep = "";
        final String serial = ip + ":" + port;

        String[] commands = buildServerCommand(serial, options);

        // 1. Make sure our private adb server is alive (it can hang after deep sleep).
        reportStep(listener, R.string.step_starting_adb);
        boolean serverIsRunning = AdbHelper.checkAdbServer();
        Log.i("Scrcpy", "serverIsRunning: " + serverIsRunning);
        if (!serverIsRunning || !AdbHelper.isRunning()) {
            AdbHelper.restartAdb();
            try {
                AdbHelper.waitForRunning(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 2. Connect and wait for authorization (with a clear diagnosis on failure).
        AdbHelper.ConnectResult connectResult = AdbHelper.connectDevice(context, ip, port, listener);
        if (!connectResult.success) {
            lastFailure = connectResult;
            return CmdStatus.ERROR;
        }

        // 3. Push server, forward port, start server (guarded by a timeout).
        AtomicReference<CmdStatus> status = new AtomicReference<>(CmdStatus.RUNNING);
        ThreadUtils.execute(() -> {
            try {
                reportStep(listener, R.string.step_pushing_server);
                CmdStatus curStatus = startPortForward(context, ip, port, forwardport);
                status.set(curStatus);
                if (curStatus == CmdStatus.SUCCESS) {
                    reportStep(listener, R.string.step_starting_server);
                    newAdbServerStart(commands);
                }
            } catch (Exception e) {
                e.printStackTrace();
                status.set(CmdStatus.ERROR);
            }
        });
        int count = 0;
        while (status.get() == CmdStatus.RUNNING && count < (WAIT_TIME / 100)) {
            try {
                Thread.sleep(100);
                count++;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        if (status.get() == CmdStatus.RUNNING) {
            status.set(CmdStatus.ERROR);
        }
        if (status.get() == CmdStatus.SUCCESS) {
            count = 0;
            //  检测程序是否已经启动，如果启动了，该文件会被删除
            while (status.get() == CmdStatus.SUCCESS && count < 40) {
                String adbTextCmd = AdbHelper.adbCmd(App.mContext,
                        "-s", serial, "shell", "ls", "-alh", "/data/local/tmp/scrcpy-server.jar");
                if (TextUtils.isEmpty(adbTextCmd)) {
                    break;
                } else {
                    try {
                        Thread.sleep(100);
                        count++;
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        return status.get();
    }

    /** adb arguments that start the scrcpy server on {@code serial}. */
    static String[] buildServerCommand(String serial, Options options) {
        List<String> cmdList = new ArrayList<>();
        Collections.addAll(cmdList,
                "-s", serial,
                "shell",
                " CLASSPATH=/data/local/tmp/scrcpy-server.jar",
                "app_process",
                "/",
                "org.server.scrcpy.Server"
        );
        Collections.addAll(cmdList, options.optionsToArgs());
        cmdList.add(";");
        return cmdList.toArray(new String[0]);
    }

    private static void reportStep(AdbHelper.ConnectListener listener, int res) {
        if (listener != null) {
            listener.onStep(res);
        }
    }

    private CmdStatus startPortForward(Context context, String ip, int port, int serverport) {
        Log.i("Scrcpy", "push server to: " + ip + ":" + port);
        // 复制server端到可执行目录（内部存储，避免外部存储不可用时连接失败）
        String pushRet = AdbHelper.adbCmdAll(App.mContext, "-s", ip + ":" + port, "push",
                AdbHelper.getServerJarFile(context).getAbsolutePath(), "/data/local/tmp/scrcpy-server.jar");

        Log.i("Scrcpy", "pushRet: " + pushRet);

        String adbTextCmd = AdbHelper.adbCmd(App.mContext, "-s", ip + ":" + port, "shell", "ls", "-alh", "/data/local/tmp/scrcpy-server.jar");
        if (TextUtils.isEmpty(adbTextCmd)) {
            lastFailureStep = "push: " + (pushRet == null ? "" : pushRet.trim());
            return CmdStatus.ERROR;
        }
        // 开启本地端口 forward 转发
        Log.i("Scrcpy", "开启本地端口转发");
        AdbHelper.adbCmd(App.mContext, "-s", ip + ":" + port, "forward", "tcp:" + serverport, "tcp:" + 7007);
        return CmdStatus.SUCCESS;
    }

    private void newAdbServerStart(String[] command) {
        // 执行启动命令
        AdbHelper.adbCmd(App.mContext, command);
    }

}
