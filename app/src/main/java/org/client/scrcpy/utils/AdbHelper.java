package org.client.scrcpy.utils;

import android.content.Context;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import org.client.scrcpy.App;
import org.client.scrcpy.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class AdbHelper {

    private static volatile boolean startAdbRun = false;

    private static volatile boolean running = false;

    private static CountDownLatch latch = new CountDownLatch(1);

    private static final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * If the mobile device enters a deep sleep state for an extended period,
     * the ADB process will become unresponsive
     * the connection via port 5137 can no longer be re-established,
     * and the process must be terminated.
     * <p>
     * 强杀 adb 进程，长时间休眠后，无法自动唤醒
     */
    public static void killAdbProccess() {
        ExecUtil.execCommend("pkill adb");
    }

    public static void restartAdb() {
        killAdbProccess();
        startAdbServer();
    }

    public static boolean isRunning() {
        return running;
    }

    /**
     * 更新状态的方法。
     * 由监控线程或 .so 状态回调调用。
     */
    private static void setRunning(boolean isRunning) {
        running = isRunning;
        if (isRunning && latch.getCount() > 0) {
            latch.countDown();
        } else if (!isRunning) {
            if (latch.getCount() == 0) {
                latch = new CountDownLatch(1);
            }
        }
    }

    public static boolean waitForRunning(long timeoutSeconds) throws InterruptedException {
        if (running) return true;
        // 等待倒计时归零
        return latch.await(timeoutSeconds, TimeUnit.SECONDS);
    }

    /**
     * Check if the ADB service is running properly.
     * If the process becomes unresponsive, it will block and time out.
     */
    public static boolean checkAdbServer() {
        return executeWithTimeout(() -> {
            // run devices cmd, check server
            String deviceList = AdbHelper.adbCmd(App.mContext, "devices");
            Log.i("Scrcpy", "adb devices: " + deviceList);
        }, 5, TimeUnit.SECONDS);
    }

    public static boolean executeWithTimeout(Runnable task, long timeout, TimeUnit unit) {
        Future<?> future = executor.submit(task);

        try {
            future.get(timeout, unit);
            return true; // 顺利执行完毕
        } catch (TimeoutException e) {
            future.cancel(true);
            System.err.println("任务执行超时");
            return false;
        } catch (InterruptedException e) {
            // 当前等待的线程被别人中断了
            future.cancel(true);
            Thread.currentThread().interrupt(); // 恢复中断标志位
            return false;
        } catch (ExecutionException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 启动 adb 服务
     */
    public static void startAdbServer() {
        if (startAdbRun) {
            // 当前正在启动过程中，退出
            return;
        }
        setRunning(false);
        startAdbRun = true;
        ThreadUtils.execute(() -> {
            // 启动 adb 服务
            Log.i("Scrcpy", "start adb server ...");
            adbCmd(App.mContext, "kill-server");
            adbCmd(App.mContext, "start-server");
            setRunning(true);
            // 启动完毕，重置为false，使其下次可以被重新调用
            startAdbRun = false;
        });
    }

    /**
     * https://github.com/lzhiyong/android-sdk-tools
     * <p>
     * 尝试自动生成 adb key文件
     */
    private static void genKeyFile(Context mContext) {
        File keyDir = new File(mContext.getFilesDir().getAbsolutePath(), ".android");
        File privateKey = new File(keyDir, "adbkey");
        File publicKey = new File(keyDir, "adbkey.pub");
        if (!keyDir.exists()) {
            keyDir.mkdirs();
        }
        if (!privateKey.exists() || !publicKey.exists()) {
            privateKey.delete();
            publicKey.delete();
            //
            Log.i("Scrcpy", "adbkey not exists, call gen: " + privateKey.getAbsolutePath());
            adbCmd(mContext, false, new String[]{
                    "keygen", privateKey.getAbsolutePath()
            });
        }
    }


    /**
     * True if the wireless ADB target is listed as {@code device} (not offline/unauthorized).
     */
    public static boolean isDeviceOnline(Context mContext, String host, int port) {
        if (mContext == null || TextUtils.isEmpty(host)) {
            return false;
        }
        String serial = host + ":" + port;
        if ("device".equals(parseDeviceState(adbCmd(mContext, "devices"), serial))) {
            return true;
        }
        boolean connectFinished = executeWithTimeout(() -> adbCmd(mContext, "connect", serial),
                4, TimeUnit.SECONDS);
        if (!connectFinished) {
            return false;
        }
        return "device".equals(parseDeviceState(adbCmd(mContext, "devices"), serial));
    }

    /**
     * State of {@code serial} in {@code adb devices}: "device", "offline",
     * "unauthorized", "authorizing", ... or null when it is not listed.
     */
    public static String getDeviceState(Context mContext, String serial) {
        return parseDeviceState(adbCmd(mContext, "devices"), serial);
    }

    static String parseDeviceState(String devicesOutput, String serial) {
        if (TextUtils.isEmpty(devicesOutput) || TextUtils.isEmpty(serial)) {
            return null;
        }
        String[] lines = devicesOutput.split("\\r?\\n");
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2 && parts[0].equals(serial)) {
                return parts[1];
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Robust "adb connect" with diagnosis
    // ------------------------------------------------------------------

    /** Receives human readable progress while connecting. */
    public interface ConnectListener {
        void onStep(int stringRes);
    }

    public static final class ConnectResult {
        public final boolean success;
        public final AdbAddress.Failure failure;
        public final String detail;

        private ConnectResult(boolean success, AdbAddress.Failure failure, String detail) {
            this.success = success;
            this.failure = failure;
            this.detail = detail == null ? "" : detail.trim();
        }

        static ConnectResult ok() {
            return new ConnectResult(true, null, "");
        }

        static ConnectResult fail(AdbAddress.Failure failure, String detail) {
            return new ConnectResult(false, failure, detail);
        }
    }

    /** How long we wait for the user to tap "Allow" on the target device. */
    private static final long AUTHORIZATION_WAIT_MS = 60_000;
    /** How long a freshly connected device may stay "offline"/"connecting". */
    private static final long SETTLE_WAIT_MS = 12_000;

    /**
     * Connect to {@code host:port} and wait until it is usable ({@code device}
     * state). Handles the "Allow USB debugging?" prompt on the target by
     * waiting for it instead of failing immediately, and reconnects stale
     * offline entries.
     */
    public static ConnectResult connectDevice(Context ctx, String host, int port, ConnectListener listener) {
        String serial = host + ":" + port;
        String state = getDeviceState(ctx, serial);
        if ("device".equals(state)) {
            return ConnectResult.ok();
        }
        if (state != null) {
            // Stale entry (offline / unauthorized from a previous attempt): start fresh.
            adbCmd(ctx, "disconnect", serial);
        }
        notifyStep(listener, R.string.step_connecting);

        String output = connectOnce(ctx, serial);
        if (output == null) {
            return ConnectResult.fail(AdbAddress.Failure.TIMEOUT, "");
        }
        AdbAddress.Failure failure = AdbAddress.classifyConnectOutput(output);
        if (failure != null && failure != AdbAddress.Failure.UNAUTHORIZED) {
            return ConnectResult.fail(failure, output);
        }

        boolean waitingForUser = failure == AdbAddress.Failure.UNAUTHORIZED;
        if (waitingForUser) {
            notifyStep(listener, R.string.step_waiting_authorization);
        }
        long start = SystemClock.elapsedRealtime();
        int reconnects = 0;
        while (true) {
            state = getDeviceState(ctx, serial);
            if ("device".equals(state)) {
                return ConnectResult.ok();
            }
            if ("unauthorized".equals(state) || "authorizing".equals(state)) {
                if (!waitingForUser) {
                    waitingForUser = true;
                    notifyStep(listener, R.string.step_waiting_authorization);
                }
            } else if (state == null && reconnects < 3) {
                // Dropped (e.g. the prompt was dismissed or adbd restarted): try again.
                reconnects++;
                output = connectOnce(ctx, serial);
                AdbAddress.Failure again = output == null
                        ? AdbAddress.Failure.TIMEOUT : AdbAddress.classifyConnectOutput(output);
                if (again != null && again != AdbAddress.Failure.UNAUTHORIZED) {
                    return ConnectResult.fail(again, output);
                }
            }
            long elapsed = SystemClock.elapsedRealtime() - start;
            long limit = waitingForUser ? AUTHORIZATION_WAIT_MS : SETTLE_WAIT_MS;
            if (elapsed > limit) {
                if (waitingForUser) {
                    return ConnectResult.fail(AdbAddress.Failure.UNAUTHORIZED, output);
                }
                return ConnectResult.fail("offline".equals(state)
                        ? AdbAddress.Failure.OFFLINE : AdbAddress.Failure.TIMEOUT, output);
            }
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ConnectResult.fail(AdbAddress.Failure.TIMEOUT, output);
            }
        }
    }

    /** Runs {@code adb connect}; returns its output or null when it hung. */
    private static String connectOnce(Context ctx, String serial) {
        final String[] out = {""};
        boolean finished = executeWithTimeout(() -> out[0] = adbCmdAll(ctx, "connect", serial),
                12, TimeUnit.SECONDS);
        Log.i("Scrcpy", "adb connect " + serial + " -> " + out[0]);
        return finished ? out[0] : null;
    }

    private static void notifyStep(ConnectListener listener, int res) {
        if (listener != null) {
            listener.onStep(res);
        }
    }

    /**
     * {@code adb pair host:port code} (Android 11+ wireless debugging).
     *
     * @return the adb output; success when it contains "Successfully paired".
     */
    public static String pair(Context ctx, String hostPort, String code) {
        final String[] out = {""};
        boolean finished = executeWithTimeout(() -> out[0] = adbCmdAll(ctx, "pair", hostPort, code),
                20, TimeUnit.SECONDS);
        Log.i("Scrcpy", "adb pair " + hostPort + " -> " + out[0]);
        return finished ? out[0] : "timeout";
    }

    public static boolean isPairSuccess(String output) {
        return output != null && output.toLowerCase(java.util.Locale.ROOT).contains("successfully paired");
    }

    public static String adbCmd(Context mContext, String... cmd) {
        return adbCmd(mContext, true, false, cmd);
    }

    /** Like {@link #adbCmd(Context, String...)} but stderr is included in the result. */
    public static String adbCmdAll(Context mContext, String... cmd) {
        return adbCmd(mContext, true, true, cmd);
    }

    private static String adbCmd(Context mContext, boolean checkKeyFile, String... cmd) {
        return adbCmd(mContext, checkKeyFile, false, cmd);
    }

    private static String adbCmd(Context mContext, boolean checkKeyFile, boolean includeStderr, String... cmd) {
        if (cmd == null) {
            return "";
        }
        String[] cmds = new String[cmd.length + 1];
        cmds[0] = mContext.getApplicationInfo().nativeLibraryDir + "/libadb.so";
        System.arraycopy(cmd, 0, cmds, 1, cmd.length);

        HashMap<String, String> env = new HashMap<>();
        env.put("HOME", mContext.getFilesDir().getAbsolutePath());
        env.put("TMPDIR", mContext.getCacheDir().getAbsolutePath());
        env.put("ANDROID_ADB_SERVER_PORT", "5137");
        // 尝试使用可构建的 adb 二进制文件
        // https://github.com/lzhiyong/android-sdk-tools
        // 此工具需要设置 $ADB_VENDOR_KEYS
        File keyDir = new File(mContext.getFilesDir().getAbsolutePath(), ".android");
        env.put("ADB_VENDOR_KEYS", keyDir.getAbsolutePath());

        if (checkKeyFile) {
            genKeyFile(mContext);
        }

        return ExecUtil.adbCommend(cmds, env, mContext.getFilesDir(), includeStderr);
    }

    public static void runCustomAdbCommand(Context mContext, String commandLine, ExecUtil.AdbOutputListener listener) {
        ThreadUtils.execute(() -> {
            if (mContext == null) {
                if (listener != null) {
                    listener.onLine("Context is null", true);
                    listener.onComplete(-1);
                }
                return;
            }
            if (TextUtils.isEmpty(commandLine)) {
                if (listener != null) {
                    listener.onLine("Empty command", true);
                    listener.onComplete(-1);
                }
                return;
            }

            java.util.List<String> tokens = parseCommandArgs(commandLine.trim());
            if (tokens.isEmpty()) {
                if (listener != null) {
                    listener.onLine("Empty command", true);
                    listener.onComplete(-1);
                }
                return;
            }

            // Remove leading "adb" if user typed "adb pair ..." or "./adb pair ..."
            if (tokens.get(0).equalsIgnoreCase("adb") || tokens.get(0).equalsIgnoreCase("./adb")) {
                tokens.remove(0);
            }

            if (tokens.isEmpty()) {
                if (listener != null) {
                    listener.onLine("Please specify an ADB command (e.g. pair, connect, devices)", true);
                    listener.onComplete(-1);
                }
                return;
            }

            String adbBinary = mContext.getApplicationInfo().nativeLibraryDir + "/libadb.so";
            java.util.List<String> fullCmd = new java.util.ArrayList<>();
            fullCmd.add(adbBinary);
            fullCmd.addAll(tokens);

            HashMap<String, String> env = new HashMap<>();
            env.put("HOME", mContext.getFilesDir().getAbsolutePath());
            env.put("TMPDIR", mContext.getCacheDir().getAbsolutePath());
            env.put("ANDROID_ADB_SERVER_PORT", "5137");
            File keyDir = new File(mContext.getFilesDir().getAbsolutePath(), ".android");
            env.put("ADB_VENDOR_KEYS", keyDir.getAbsolutePath());

            genKeyFile(mContext);

            ExecUtil.execAdbWithListener(fullCmd.toArray(new String[0]), env, mContext.getFilesDir(), listener);
        });
    }

    public static java.util.List<String> parseCommandArgs(String cmd) {
        java.util.List<String> args = new java.util.ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (c == '\"' || c == '\'') {
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            args.add(current.toString());
        }
        return args;
    }

    /**
     * scrcpy-server.jar is kept in internal storage. External app directories can
     * be missing or unavailable (for example when getExternalFilesDir() returns
     * null because the ROM never sets sys.user.0.ce_available), which previously
     * aborted the whole connect flow.
     */
    public static File getServerJarFile(Context context) {
        return new File(context.getFilesDir(), "scrcpy-server.jar");
    }

    public static void writeAssetsJarServer(Context context) {
        Log.d("Scrcpy", "File scrcpy-server.jar try write");
        File jarFile = getServerJarFile(context);
        try (InputStream inputStream = context.getAssets().open("scrcpy-server.jar");
             FileOutputStream outputStream = new FileOutputStream(jarFile)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, length);
            }
            outputStream.flush();
        } catch (IOException e) {
            Log.e("Scrcpy", "File scrcpy-server.jar write failed", e);
        }
    }

}
