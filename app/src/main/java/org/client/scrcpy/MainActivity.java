package org.client.scrcpy;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Filter;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import org.client.scrcpy.usb.AdbConnection;
import org.client.scrcpy.usb.UsbAdb;
import org.client.scrcpy.utils.AdbAddress;
import org.client.scrcpy.utils.AdbHelper;
import org.client.scrcpy.utils.PreUtils;
import org.client.scrcpy.utils.Progress;
import org.client.scrcpy.utils.ResolutionHelper;
import org.client.scrcpy.utils.SessionLog;
import org.client.scrcpy.utils.ThreadUtils;
import org.client.scrcpy.utils.Util;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;


public class MainActivity extends AppCompatActivity implements Scrcpy.ServiceCallbacks, SensorEventListener {

    // 是否直接连接远程
    public final static String START_REMOTE = "start_remote_headless";

    private boolean headlessMode = false;  // 是否为无头模式，不显示操作选项等
    private int screenWidth;
    private int screenHeight;
    private boolean landscape = false;
    private boolean first_time = true;
    private boolean result_of_Rotation = false;
    private boolean serviceBound = false;
    // 如果 pause 切换到后台，断开后，自动重连
    // 该状态禁止保存恢复
    private boolean resumeScrcpy = false;
    SensorManager sensorManager;
    private SendCommands sendCommands;
    private int videoBitrate;
    private int delayControl;
    private String videoCodec = Options.CODEC_H264;
    private String videoEncoder = "";
    private int videoFrameRate = 60;
    private Context context;
    private String serverAdr = null;
    private SurfaceView surfaceView;
    private Surface surface;
    private SurfaceHolder.Callback surfaceCallback;
    private Scrcpy scrcpy;
    private long timestamp = 0;

    private LinearLayout linearLayout;

// USB ADB support
    // Distinct from AdbShellActivity's action so the two screens never react to
    // each other's USB permission results.
    private static final String ACTION_USB_PERMISSION = "org.client.scrcpy.USB_PERMISSION_MAIN";

    private UsbManager usbManager;
    private UsbAdb usbAdb;

    /** What to do once the user grants USB permission. */
    private enum UsbAction {MIRROR, ENABLE_WIFI}

    private UsbAction pendingUsbAction = UsbAction.MIRROR;

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction()) || isFinishing()) {
                return;
            }
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && device != null) {
                if (pendingUsbAction == UsbAction.ENABLE_WIFI) {
                    startUsbToWifi(device);
                } else {
                    startUsbConnect(device);
                }
            } else {
                Progress.closeDialog();
                Toast.makeText(MainActivity.this, R.string.usb_permission_denied,
                        Toast.LENGTH_SHORT).show();
            }
        }
    };

    private boolean autoResolutionEnabled = false;
    private ResolutionHelper.LimitSource resolutionLimitSource = null;

    private volatile boolean statusMonitorActive = false;
    private volatile int statusCheckGeneration = 0;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            scrcpy = ((Scrcpy.MyServiceBinder) iBinder).getService();
            scrcpy.setServiceCallbacks(MainActivity.this);
            serviceBound = true;
            if (first_time) {
                if (!Progress.isShowing()) {
                    Progress.showDialog(MainActivity.this, getString(R.string.please_wait));
                }
                scrcpy.start(surface, Scrcpy.LOCAL_IP + ":" + Scrcpy.LOCAL_FORWART_PORT,
                        screenHeight, screenWidth, delayControl,
                        PreUtils.get(MainActivity.this, Constant.AUDIO_FORWARD, true),
                        videoCodec);
                ThreadUtils.workPost(() -> {
                    boolean success = AdbHelper.executeWithTimeout(() -> {
                        while (!scrcpy.check_socket_connection()) {
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                break;
                            }
                        }
                    }, SendCommands.WAIT_TIME, TimeUnit.MILLISECONDS);
                    ThreadUtils.post(() -> {
                        Progress.closeDialog();
                        if (!success) {
                            if (serviceBound) {
                                showMainView();
                            }
                            Toast.makeText(context, R.string.connect_error_stream_timeout, Toast.LENGTH_LONG).show();
                        } else {
                            first_time = false;
                            applyClientOrientationFromRemote();
                            refreshMirrorLayout();
                            connectSuccessExt();
                        }
                    });
                });
            } else {
                scrcpy.setParms(surface, screenWidth, screenHeight);
                refreshMirrorLayout();
                connectSuccessExt();
            }
            // set_display_nd_touch();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            serviceBound = false;
        }
    };

    private void showMainView() {
        showMainView(false);
    }

    // userDisconnect ：是否为用户手动断开连接
    private void showMainView(boolean userDisconnect) {
        // 先停止 Scrcpy 服务（停止视频流），再关闭 usbAdb。
        // 若先关 USB 连接，readerThread 可能正阻塞在 bulkTransfer 上，
        // 强制中断传输会导致设备被重新枚举（deviceId 变化），授权丢失，
        // 下次启动连接时就会重新弹 USB 授权请求。
        if (scrcpy != null) {
            scrcpy.StopService();
        }
        if (usbAdb != null) {
            usbAdb.close();
            usbAdb = null;
        }
        try {
            // 可能会导致重复解绑，所以捕获异常
            unbindService(serviceConnection);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (surfaceView != null && surfaceCallback != null) {
            surfaceView.getHolder().removeCallback(surfaceCallback);
            surfaceView = null;
        }
        surfaceCallback = null;
        if (surface != null) {
            surface = null;
        }
        serviceBound = false;
        scrcpy_main();

        if (scrcpy != null) {
            scrcpy = null;
        }
        // 退出连接，需要处理额外的事件
        connectExitExt(userDisconnect);
    }

    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        this.context = this;
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        registerUsbPermissionReceiver();
        setTitle(getString(R.string.app_name));
        if (savedInstanceState != null) {
            first_time = savedInstanceState.getBoolean("first_time");
            landscape = savedInstanceState.getBoolean("landscape");
            headlessMode = savedInstanceState.getBoolean("headlessMode");
            resumeScrcpy = savedInstanceState.getBoolean("resumeScrcpy");
            screenHeight = savedInstanceState.getInt("screenHeight");
            screenWidth = savedInstanceState.getInt("screenWidth");
        }
        if (first_time) {
            scrcpy_main();
        } else {
            landscape = getResources().getConfiguration().orientation
                    != Configuration.ORIENTATION_PORTRAIT;
            Log.e("Scrcpy: ", "from onCreate");
            start_screen_copy_magic();
        }
        sensorManager = (SensorManager) this.getSystemService(SENSOR_SERVICE);
        Sensor proximity;
        proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        sensorManager.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL);

        if (savedInstanceState != null) {
            Log.i("Scrcpy", "outState: " + savedInstanceState.getBoolean("from_save_instance"));
        }
        // 从销毁状态恢复
        if (savedInstanceState == null || !savedInstanceState.getBoolean("from_save_instance", false)) {
            // 初次进入 app
            if (getIntent() != null && getIntent().getExtras() != null) {
                headlessMode = getIntent().getExtras().getBoolean(START_REMOTE, headlessMode);
            }
        }
        if (headlessMode && first_time) {
            getAttributes();
            connectScrcpyServer(PreUtils.get(this, Constant.CONTROL_REMOTE_ADDR, ""));
        }
        if (headlessMode) {
            View scrollView = findViewById(R.id.main_scroll_view);
            if (scrollView != null) {
                scrollView.setVisibility(View.INVISIBLE);
            }
        }
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackPressed();
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        Log.i("Scrcpy", "enter onSaveInstanceState");
        outState.putBoolean("from_save_instance", true);
        outState.putBoolean("first_time", first_time);
        outState.putBoolean("landscape", landscape);
        outState.putBoolean("headlessMode", headlessMode);  // 第二次进入时，intent会被重置，需要保存状态
        // 小窗模式、半屏模式切换避免恢复横竖屏，会导致黑屏（因为scrcpy恢复的resume只允许一次连接）
        // outState.putBoolean("resumeScrcpy", resumeScrcpy);
        outState.putInt("screenHeight", screenHeight);
        outState.putInt("screenWidth", screenWidth);
    }

    @SuppressLint("SourceLockedOrientationActivity")
    public void scrcpy_main() {
        // Leave the immersive mirror mode and go back to edge-to-edge with visible bars.
        final View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        EdgeToEdge.enable(this);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        landscape = false;  // 将模式重新置为 竖屏，模式不正确将导致连接黑屏
        setContentView(R.layout.activity_main);

        // Bottom / side system bar insets for the scrolling content.
        View content = findViewById(R.id.main_content);
        final int padLeft = content.getPaddingLeft();
        final int padRight = content.getPaddingRight();
        final int padBottom = content.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(padLeft + bars.left, v.getPaddingTop(), padRight + bars.right, padBottom + bars.bottom);
            return insets;
        });

        setupToolbar();

        // find view by id
        NestedScrollView scrollView = findViewById(R.id.main_scroll_view);
        Button startButton = findViewById(R.id.button_start);
        MaterialButton btnMoreSettings = findViewById(R.id.btn_more_settings);
        View layoutMoreSettings = findViewById(R.id.layout_more_settings);

        sendCommands = new SendCommands();

        startButton.setOnClickListener(v -> onStartClicked());
        Button rebootButton = findViewById(R.id.button_reboot);
        if (rebootButton != null) {
            rebootButton.setOnClickListener(v -> confirmRebootRemoteDevice());
        }
        Button powerOffButton = findViewById(R.id.button_power_off);
        if (powerOffButton != null) {
            powerOffButton.setOnClickListener(v -> confirmPowerOffRemoteDevice());
        }
        Button shareLogButton = findViewById(R.id.button_share_log);
        if (shareLogButton != null) {
            shareLogButton.setOnClickListener(v -> shareSessionLog());
        }
        Button adbTerminalButton = findViewById(R.id.button_adb_terminal);
        if (adbTerminalButton != null) {
            adbTerminalButton.setOnClickListener(v -> {
                EditText hostEdit = findViewById(R.id.editText_server_host);
                String currentHost = hostEdit == null ? "" : hostEdit.getText().toString().trim();
                AdbTerminalDialog dialog = new AdbTerminalDialog(this, currentHost, this::applyTarget);
                dialog.show();
            });
        }
        Button adbShellButton = findViewById(R.id.button_adb_shell);
        if (adbShellButton != null) {
            adbShellButton.setOnClickListener(v -> {
                // 进入 ADB 命令行前，必须彻底停止 scrcpy/USB 会话：
                // 关闭 usbAdb（释放 USB 设备与 7008 端口）、停止 scrcpy 服务、
                // 解绑服务并清除 resumeScrcpy。
                // 否则 USB 设备被旧会话占用，AdbShellActivity 会再次弹授权，
                // 且从命令行返回后 onResume/onStart 会自动恢复 scrcpy 画面。
                cleanupUsbSessionForShell();
                EditText hostEdit = findViewById(R.id.editText_server_host);
                String device = hostEdit == null ? "" : hostEdit.getText().toString().trim();
                Intent shellIntent = new Intent(this, AdbShellActivity.class);
                shellIntent.putExtra(AdbShellActivity.EXTRA_DEVICE, device);
                startActivity(shellIntent);
            });
        }
        btnMoreSettings.setOnClickListener(v -> {
            AutoTransition autoTransition = new AutoTransition();
            TransitionManager.beginDelayedTransition(scrollView, autoTransition);

            if (layoutMoreSettings.getVisibility() == View.GONE) {
                layoutMoreSettings.setVisibility(View.VISIBLE);
                btnMoreSettings.setText(R.string.collapse_settings);
            } else {
                layoutMoreSettings.setVisibility(View.GONE);
                btnMoreSettings.setText(R.string.more_settings);
            }
        });

        // Connection helpers
        findViewById(R.id.chip_discover).setOnClickListener(v ->
                ConnectAssistant.showDiscoverDialog(this, this::applyTarget));
        findViewById(R.id.chip_pair).setOnClickListener(v ->
                ConnectAssistant.showPairDialog(this, null, this::applyTarget));
        findViewById(R.id.chip_usb_to_wifi).setOnClickListener(v -> enableWifiDebuggingOverUsb());
        findViewById(R.id.chip_help).setOnClickListener(v -> ConnectAssistant.showHowToDialog(this));

        get_saved_preferences();

        EditText editText = findViewById(R.id.editText_server_host);
        TextInputLayout hostLayout = findViewById(R.id.layout_server_host);

        hostLayout.setEndIconOnClickListener(v -> {
            Log.i("Scrcpy", "focus true");
            editText.clearFocus();
            showListPopulWindow(editText);
        });
        editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                onStartClicked();
                return true;
            }
            return false;
        });

        editText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                updateResolutionPreview();
            }
        });
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                hostLayout.setError(null);
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (statusMonitorActive) {
                    scheduleStatusCheck(800);
                }
            }
        });

        startStatusMonitor();

        // 无头模式，实际上要隐藏掉所有控件，否则会被显示出 ip 地址
        if (headlessMode) {
            if (scrollView != null) {
                scrollView.setVisibility(View.INVISIBLE);
            }
        }
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.app_toolbar);
        if (toolbar == null) {
            return;
        }
        toolbar.setSubtitle("v" + BuildConfig.VERSION_NAME);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_discover) {
                ConnectAssistant.showDiscoverDialog(this, this::applyTarget);
                return true;
            } else if (id == R.id.menu_share_log) {
                shareSessionLog();
                return true;
            } else if (id == R.id.menu_reboot) {
                confirmRebootRemoteDevice();
                return true;
            } else if (id == R.id.menu_power_off) {
                confirmPowerOffRemoteDevice();
                return true;
            }
            return false;
        });
    }

    /** Put a (normalised) address in the target field and refresh the status pill. */
    private void applyTarget(String address) {
        if (TextUtils.isEmpty(address)) {
            return;
        }
        EditText hostEdit = findViewById(R.id.editText_server_host);
        if (hostEdit == null) {
            return;
        }
        hostEdit.setText(address);
        hostEdit.setSelection(hostEdit.getText().length());
        if (!UsbShellCompat.isUsbSelection(address)) {
            PreUtils.put(context, Constant.CONTROL_REMOTE_ADDR, address);
            saveHistory(address);
        }
        if (statusMonitorActive) {
            scheduleStatusCheck(0);
        } else {
            startStatusMonitor();
        }
        updateResolutionPreview();
    }

    /**
     * Validates the target, normalises what the user typed (e.g. a pasted
     * "adb connect 192.168.1.5:5555") and starts the connection.
     */
    private void onStartClicked() {
        EditText hostEdit = findViewById(R.id.editText_server_host);
        TextInputLayout hostLayout = findViewById(R.id.layout_server_host);
        String raw = hostEdit == null ? "" : hostEdit.getText().toString().trim();
        if (!UsbShellCompat.isUsbSelection(raw)) {
            if (TextUtils.isEmpty(raw)) {
                // Nothing typed: a plugged-in USB device is the obvious choice.
                String usbLabel = getUsbAdbDeviceLabel();
                if (usbLabel != null && hostEdit != null) {
                    hostEdit.setText(usbLabel);
                } else {
                    if (hostLayout != null) {
                        hostLayout.setError(getString(R.string.error_address_empty));
                    }
                    return;
                }
            } else {
                String normalized = AdbAddress.normalize(raw);
                if (normalized == null) {
                    if (hostLayout != null) {
                        hostLayout.setError(getString(R.string.error_address_invalid));
                    }
                    return;
                }
                if (!normalized.equals(raw) && hostEdit != null) {
                    hostEdit.setText(normalized);
                }
            }
        }
        stopStatusMonitor();
        // 启动连接前先释放上一次的 USB/scrcpy 会话（停止服务、关闭 usbAdb、
        // 释放 7008 与 USB 设备），避免横屏/反复连接后授权丢失或 openDevice 失败
        cleanupUsbSessionForShell();
        getAttributes();
        SessionLog.i("Start clicked host=" + serverAdr
                + " max=" + Math.max(screenHeight, screenWidth)
                + " bitrate=" + videoBitrate
                + " codec=" + videoCodec
                + " encoder=" + videoEncoder
                + " fps=" + videoFrameRate);
        if (UsbShellCompat.isUsbSelection(serverAdr)) {
            connectViaUsb();
        } else {
            connectScrcpyServer(serverAdr);
        }
    }

    private void showListPopulWindow(EditText mEditText) {
        String[] history = getHistoryList();//要填充的数据
        if (history.length == 0) {  // 如果list为空，则使用本机填充一个
            history = new String[]{"127.0.0.1"};
        }
        // 第一项固定为 USB 连接入口，其余为历史 IP 记录
        // 若检测到 USB 设备，显示其在 adb devices 中的名称
        String usbLabel = getUsbAdbDeviceLabel();
        final List<String> items = new ArrayList<>();
        items.add(usbLabel != null ? usbLabel : getString(R.string.action_start_usb));
        items.addAll(Arrays.asList(history));

        final ListPopupWindow listPopupWindow;
        listPopupWindow = new ListPopupWindow(this);
        // 自定义列表项：设备名 + 右侧删除按钮
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, R.layout.item_history_device, R.id.text_device, items) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                View deleteButton = view.findViewById(R.id.button_delete);
                ImageView icon = view.findViewById(R.id.icon_device);
                if (position == 0) {
                    // USB 连接入口不允许删除
                    deleteButton.setVisibility(View.GONE);
                    icon.setImageResource(R.drawable.ic_usb);
                } else {
                    deleteButton.setVisibility(View.VISIBLE);
                    icon.setImageResource(R.drawable.ic_history);
                    final String device = getItem(position);
                    deleteButton.setOnClickListener(v -> {
                        removeHistory(device);
                        remove(device);
                        notifyDataSetChanged();
                    });
                }
                return view;
            }
        };
        listPopupWindow.setAdapter(adapter);
        View anchor = findViewById(R.id.layout_server_host);
        listPopupWindow.setAnchorView(anchor != null ? anchor : mEditText);//以哪个控件为基准
        listPopupWindow.setBackgroundDrawable(getDrawable(R.drawable.bg_popup));
        listPopupWindow.setModal(true);
        listPopupWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        listPopupWindow.setOnItemClickListener(new AdapterView.OnItemClickListener() {//设置项点击监听
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                listPopupWindow.dismiss();
                mEditText.setText(items.get(i));
                if (i == 0) {  // 选择了 USB 连接入口，仅填入设备，由用户点击启动/ADB命令按钮
                    // 触发一次状态刷新，检测 USB 设备在线状态
                    if (statusMonitorActive) {
                        scheduleStatusCheck(0);
                    } else {
                        startStatusMonitor();
                    }
                }
            }
        });
        listPopupWindow.show();
    }

    /**
     * 从历史连接记录中删除指定设备
     */
    private void removeHistory(String device) {
        String[] historyList = getHistoryList();
        JSONArray historyJson = new JSONArray();
        for (String item : historyList) {
            if (!item.equals(device)) {
                historyJson.put(item);
            }
        }
        PreUtils.put(context, Constant.HISTORY_LIST_KEY, historyJson.toString());
    }

    private void shareSessionLog() {
        java.io.File logFile = SessionLog.getFile();
        if (logFile == null || !logFile.exists() || logFile.length() == 0) {
            Toast.makeText(this, R.string.share_log_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String subject = getString(R.string.share_log_subject, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE);
        Uri uri = Uri.parse("content://" + getPackageName() + ".log/session.log");
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, subject);
        // WhatsApp: solo riassunto ~400 caratteri. Il log intero è l'allegato.
        send.putExtra(Intent.EXTRA_TEXT, SessionLog.readPreview());
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri("scrcpy-session.log", uri));
        try {
            startActivity(Intent.createChooser(send, getString(R.string.action_share_log)));
        } catch (Exception e) {
            SessionLog.e("Share log failed", e);
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }


//    private void showDisplayWindow() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//            if (!Settings.canDrawOverlays(this)) {
//                //启动Activity让用户授权
//                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
//                startActivity(intent);
//                return;
//            }
//        }
//        Intent it = new Intent(this, FloatService.class);
//        it.putExtra("ip", serverAdr);
//        it.putExtra("w", screenWidth);
//        it.putExtra("h", screenHeight);
//        it.putExtra("b", videoBitrate);
//        startService(it);
//        finish();
//    }


    public void get_saved_preferences() {
        EditText editTextServerHost = findViewById(R.id.editText_server_host);
        CompoundButton aSwitch0 = findViewById(R.id.switch0);
        CompoundButton aSwitch1 = findViewById(R.id.switch1);
        CompoundButton audioForwardSwitch = findViewById(R.id.switch_audio_enable);
        String historySpServerAdr = PreUtils.get(context, Constant.CONTROL_REMOTE_ADDR, "");
        if (TextUtils.isEmpty(historySpServerAdr)) {
            String[] historyList = getHistoryList();
            if (historyList.length > 0) {
                editTextServerHost.setText(historyList[0]);
            }
        } else {
            editTextServerHost.setText(historySpServerAdr);
        }
        // 若检测到 USB 设备，则默认预选 USB 连接（显示 adb devices 中的名称）
        String usbLabel = getUsbAdbDeviceLabel();
        if (usbLabel != null) {
            editTextServerHost.setText(usbLabel);
        }
        aSwitch0.setChecked(PreUtils.get(context, Constant.CONTROL_NO, false));
        aSwitch1.setChecked(PreUtils.get(context, Constant.CONTROL_NAV, false));
        audioForwardSwitch.setChecked(PreUtils.get(context, Constant.AUDIO_FORWARD, true));

        setupDropdown(R.array.options_bitrate_keys, R.id.spinner_video_bitrate, Constant.PREFERENCE_SPINNER_BITRATE, null);
        setupDropdown(R.array.options_codec_keys, R.id.spinner_video_codec, Constant.PREFERENCE_SPINNER_CODEC, null);
        setupDropdown(R.array.options_fps_keys, R.id.spinner_video_fps, Constant.PREFERENCE_SPINNER_FPS, null);
        setupDropdown(R.array.options_delay_keys, R.id.delay_control_spinner, Constant.PREFERENCE_SPINNER_DELAY, null);
        setupDropdown(R.array.options_resolution_keys, R.id.spinner_video_resolution,
                Constant.PREFERENCE_SPINNER_RESOLUTION, this::updateResolutionPreview);
        updateResolutionPreview();

        EditText encoderEdit = findViewById(R.id.editText_video_encoder);
        encoderEdit.setText(PreUtils.get(context, Constant.PREFERENCE_VIDEO_ENCODER, ""));

        // The navigation bar only makes sense when control is enabled.
        aSwitch1.setEnabled(!aSwitch0.isChecked());
        aSwitch0.setOnCheckedChangeListener((buttonView, isChecked) -> aSwitch1.setEnabled(!isChecked));
    }

    @SuppressLint("ClickableViewAccessibility")
    public void set_display_nd_touch() {
        DisplayMetrics metrics = new DisplayMetrics();
        if (ViewConfiguration.get(context).hasPermanentMenuKey()) {
            getWindowManager().getDefaultDisplay().getMetrics(metrics);
        } else {
            final Display display = getWindowManager().getDefaultDisplay();
            display.getRealMetrics(metrics);
        }
//        float this_dev_height = metrics.heightPixels;
//        float this_dev_width = metrics.widthPixels;

        if (linearLayout == null || surfaceView == null || scrcpy == null) {
            return;
        }
        float this_dev_height = linearLayout.getHeight();
        float this_dev_width = linearLayout.getWidth();
        if (PreUtils.get(context, Constant.CONTROL_NAV, false) &&
                !PreUtils.get(context, Constant.CONTROL_NO, false)) {
            if (landscape) {
                this_dev_width = this_dev_width - 96;
            } else {                                                 //100 is the height of nav bar but need multiples of 8.
                this_dev_height = this_dev_height - 96;
            }
        }
        int[] remote = scrcpy.getOrientedRemoteSize(landscape);
        int remoteW = remote[0];
        int remoteH = remote[1];
        if (remoteW <= 0 || remoteH <= 0 || this_dev_width <= 0 || this_dev_height <= 0) {
            if (!PreUtils.get(context, Constant.CONTROL_NO, false)) {
                surfaceView.setOnTouchListener((view, event) ->
                        scrcpy.touchevent(event, landscape, surfaceView.getWidth(), surfaceView.getHeight()));
            }
            return;
        }

        float scale = Math.min(this_dev_width / remoteW, this_dev_height / remoteH);
        int contentW = Math.round(remoteW * scale);
        int contentH = Math.round(remoteH * scale);
        int padH = Math.max(0, ((int) this_dev_width - contentW) / 2);
        int padV = Math.max(0, ((int) this_dev_height - contentH) / 2);
        linearLayout.setPadding(padH, padV, padH, padV);
        Log.i("Scrcpy", "Fit remote " + remoteW + "x" + remoteH
                + " into " + (int) this_dev_width + "x" + (int) this_dev_height
                + " content=" + contentW + "x" + contentH
                + " pad=" + padH + "," + padV);
        if (!PreUtils.get(context, Constant.CONTROL_NO, false)) {
            // Log.i("Screen", "setOnTouchListener: " + surfaceView.getWidth() + "x" + surfaceView.getHeight());
            surfaceView.setOnTouchListener((view, event) -> scrcpy.touchevent(event, landscape, surfaceView.getWidth(), surfaceView.getHeight()));
        }

        if (PreUtils.get(context, Constant.CONTROL_NAV, false) &&
                !PreUtils.get(context, Constant.CONTROL_NO, false)) {
            final View backButton = findViewById(R.id.back_button);
            final View homeButton = findViewById(R.id.home_button);
            final View appswitchButton = findViewById(R.id.appswitch_button);
            final View powerButton = findViewById(R.id.power_button);

            if (backButton != null) {
                backButton.setOnClickListener(v -> scrcpy.sendKeyevent(KeyEvent.KEYCODE_BACK));
            }
            if (homeButton != null) {
                homeButton.setOnClickListener(v -> scrcpy.sendKeyevent(KeyEvent.KEYCODE_HOME));
            }
            if (appswitchButton != null) {
                appswitchButton.setOnClickListener(v -> scrcpy.sendKeyevent(KeyEvent.KEYCODE_APP_SWITCH));
            }
            if (powerButton != null) {
                powerButton.setOnClickListener(v -> scrcpy.sendKeyevent(KeyEvent.KEYCODE_POWER));
            }
        }
    }

    /**
     * Material exposed dropdown (replacement for Spinner). The selected index
     * is persisted under {@code preferenceId}.
     */
    private void setupDropdown(final int textArrayOptionResId, final int viewResId, final String preferenceId,
                               Runnable onChanged) {
        final MaterialAutoCompleteTextView dropdown = findViewById(viewResId);
        final String[] items = getResources().getStringArray(textArrayOptionResId);
        dropdown.setAdapter(new NoFilterArrayAdapter(this, items));
        int selection = getDropdownIndex(preferenceId, textArrayOptionResId);
        dropdown.setText(items[selection], false);
        dropdown.setOnItemClickListener((parent, view, position, id) -> {
            PreUtils.put(context, preferenceId, position);
            if (onChanged != null) {
                onChanged.run();
            }
        });
    }

    /** Persisted dropdown index, clamped to the option array. */
    private int getDropdownIndex(String preferenceId, int textArrayOptionResId) {
        int count = getResources().getStringArray(textArrayOptionResId).length;
        int selection = PreUtils.get(context, preferenceId, 0);
        return (selection >= 0 && selection < count) ? selection : 0;
    }

    /** ArrayAdapter that never filters, so the dropdown always shows every option. */
    private static final class NoFilterArrayAdapter extends ArrayAdapter<String> {
        private final String[] items;

        NoFilterArrayAdapter(Context context, String[] items) {
            super(context, R.layout.item_dropdown, items);
            this.items = items;
        }

        @NonNull
        @Override
        public Filter getFilter() {
            return new Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    FilterResults results = new FilterResults();
                    results.values = items;
                    results.count = items.length;
                    return results;
                }

                @Override
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    notifyDataSetChanged();
                }
            };
        }
    }

    private void updateResolutionPreview() {
        TextView info = findViewById(R.id.text_resolution_info);
        if (info == null) {
            return;
        }
        if (getDropdownIndex(Constant.PREFERENCE_SPINNER_RESOLUTION, R.array.options_resolution_keys)
                != Constant.RESOLUTION_AUTO_INDEX) {
            info.setVisibility(View.GONE);
            return;
        }
        EditText editText = findViewById(R.id.editText_server_host);
        String remoteAdr = editText != null ? editText.getText().toString().trim() : "";
        if (TextUtils.isEmpty(remoteAdr) || UsbShellCompat.isUsbSelection(remoteAdr)) {
            info.setText(R.string.resolution_auto_need_ip);
            info.setVisibility(View.VISIBLE);
            return;
        }
        info.setText(R.string.resolution_auto_detecting);
        info.setVisibility(View.VISIBLE);
        ThreadUtils.workPost(() -> {
            try {
                ResolutionHelper.AutoResolution autoResolution =
                        ResolutionHelper.computeAuto(context, remoteAdr);
                final String message = formatResolutionMessage(autoResolution, false);
                ThreadUtils.post(() -> {
                    if (!isFinishing()) {
                        updateResolutionInfoText(message);
                    }
                });
            } catch (Exception e) {
                Log.e("Scrcpy", "Resolution preview failed: " + e.getMessage());
                ThreadUtils.post(() -> {
                    if (!isFinishing()) {
                        info.setText(R.string.resolution_auto_failed);
                        info.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private void updateResolutionInfoText(String message) {
        TextView info = findViewById(R.id.text_resolution_info);
        if (info == null) {
            return;
        }
        if (TextUtils.isEmpty(message)) {
            info.setVisibility(View.GONE);
            return;
        }
        info.setText(message);
        info.setVisibility(View.VISIBLE);
    }

    private String formatResolutionMessage(ResolutionHelper.AutoResolution autoResolution, boolean activeStream) {
        if (autoResolution.limitSource == ResolutionHelper.LimitSource.LOCAL) {
            return getString(activeStream ? R.string.resolution_auto_stream_local : R.string.resolution_auto_local,
                    autoResolution.displayWidth, autoResolution.displayHeight);
        }
        return getString(activeStream ? R.string.resolution_auto_stream_remote : R.string.resolution_auto_remote,
                autoResolution.displayWidth, autoResolution.displayHeight);
    }

    private String formatActiveStreamResolutionMessage() {
        return formatResolutionMessage(
                new ResolutionHelper.AutoResolution(
                        Math.max(screenWidth, screenHeight),
                        screenWidth,
                        screenHeight,
                        ResolutionHelper.getLocalMaxDimension(context),
                        screenWidth,
                        screenHeight,
                        resolutionLimitSource),
                true);
    }

    private void getAttributes() {

        final EditText editTextServerHost = findViewById(R.id.editText_server_host);
        serverAdr = editTextServerHost.getText().toString();
        if (!TextUtils.isEmpty(serverAdr)) {
            serverAdr = serverAdr.trim();
        }
        // 不要把 USB 选择项当作 IP 地址持久化
        if (!TextUtils.isEmpty(serverAdr) && !UsbShellCompat.isUsbSelection(serverAdr)) {
            PreUtils.put(context, Constant.CONTROL_REMOTE_ADDR, serverAdr);
        }
        final int resolutionIndex = getDropdownIndex(Constant.PREFERENCE_SPINNER_RESOLUTION, R.array.options_resolution_keys);
        final int bitrateIndex = getDropdownIndex(Constant.PREFERENCE_SPINNER_BITRATE, R.array.options_bitrate_keys);
        final int codecIndex = getDropdownIndex(Constant.PREFERENCE_SPINNER_CODEC, R.array.options_codec_keys);
        final int fpsIndex = getDropdownIndex(Constant.PREFERENCE_SPINNER_FPS, R.array.options_fps_keys);
        final int delayIndex = getDropdownIndex(Constant.PREFERENCE_SPINNER_DELAY, R.array.options_delay_keys);
        final EditText encoderEdit = findViewById(R.id.editText_video_encoder);

        CompoundButton a_Switch0 = findViewById(R.id.switch0);
        CompoundButton a_Switch1 = findViewById(R.id.switch1);
        CompoundButton audioEnableSwitch = findViewById(R.id.switch_audio_enable);
        PreUtils.put(context, Constant.CONTROL_NO, a_Switch0.isChecked());
        PreUtils.put(context, Constant.CONTROL_NAV, a_Switch1.isChecked());
        PreUtils.put(context, Constant.AUDIO_FORWARD, audioEnableSwitch.isChecked());

        String resolutionValue = getResources().getStringArray(R.array.options_resolution_values)[resolutionIndex];
        autoResolutionEnabled = resolutionIndex == Constant.RESOLUTION_AUTO_INDEX
                || "0".equals(resolutionValue) || "auto".equalsIgnoreCase(resolutionValue);
        resolutionLimitSource = null;
        if (autoResolutionEnabled) {
            screenHeight = 0;
            screenWidth = 0;
        } else if (resolutionValue.contains("x")) {
            final String[] videoResolutions = resolutionValue.split("x");
            screenHeight = Integer.parseInt(videoResolutions[0]);
            screenWidth = Integer.parseInt(videoResolutions[1]);
        } else {
            int maxSize = Integer.parseInt(resolutionValue);
            screenHeight = maxSize;
            screenWidth = maxSize;
        }
        videoBitrate = getResources().getIntArray(R.array.options_bitrate_values)[bitrateIndex];
        videoCodec = getResources().getStringArray(R.array.options_codec_values)[codecIndex];
        videoFrameRate = getResources().getIntArray(R.array.options_fps_values)[fpsIndex];
        delayControl = getResources().getIntArray(R.array.options_delay_values)[delayIndex];
        videoEncoder = encoderEdit.getText() == null ? "" : encoderEdit.getText().toString().trim();
        PreUtils.put(context, Constant.PREFERENCE_VIDEO_ENCODER, videoEncoder);
    }

    private String[] getHistoryList() {
        String historyList = PreUtils.get(context, Constant.HISTORY_LIST_KEY, "");
        if (TextUtils.isEmpty(historyList)) {
            return new String[]{};
        }
        try {
            JSONArray historyJson = new JSONArray(historyList);
            String[] retList = new String[historyJson.length()];
            for (int i = 0; i < historyJson.length(); i++) {
                retList[i] = historyJson.get(i).toString();
            }
            return retList;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new String[]{};
    }

    /**
     * 保存设备历史连接记录
     */
    private boolean saveHistory(String device) {
        if (headlessMode) {
            // 无头模式不保存记录
            return false;
        }
        JSONArray historyJson = new JSONArray();
        String[] historyList = getHistoryList();
        if (historyList.length == 0) {
            historyJson.put(device);
        } else {
            try {
                historyJson.put(0, device);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            // 最多记录 30 个
            int count = Math.min(historyList.length, 30);
            for (int i = 0; i < count; i++) {
                if (!historyList[i].equals(device)) {
                    historyJson.put(historyList[i]);
                }
            }
        }
        try {
            return PreUtils.put(context, Constant.HISTORY_LIST_KEY, historyJson.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private void swapDimensions() {
        int temp = screenHeight;
        screenHeight = screenWidth;
        screenWidth = temp;
    }

    private void syncLandscapeFromConfiguration() {
        landscape = getResources().getConfiguration().orientation
                != Configuration.ORIENTATION_PORTRAIT;
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private void applyClientOrientationFromRemote() {
        if (scrcpy == null) {
            return;
        }
        int[] remote = scrcpy.getOrientedRemoteSize(landscape);
        if (remote[0] <= 0 || remote[1] <= 0) {
            return;
        }
        boolean remoteLandscape = remote[0] > remote[1];
        landscape = remoteLandscape;
        result_of_Rotation = true;
        Log.i("Scrcpy", "Client orientation from remote " + remote[0] + "x" + remote[1]
                + " landscape=" + remoteLandscape);
        if (remoteLandscape) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        }
    }

    private void applyMirrorImmersiveMode() {
        final View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void refreshMirrorLayout() {
        if (linearLayout == null || scrcpy == null) {
            return;
        }
        linearLayout.setPadding(0, 0, 0, 0);
        linearLayout.post(() -> {
            if (serviceBound && scrcpy != null) {
                set_display_nd_touch();
                if (surfaceView != null) {
                    Surface currentSurface = surfaceView.getHolder().getSurface();
                    if (isSurfaceReady(currentSurface)) {
                        surface = currentSurface;
                        scrcpy.setParms(surface, screenWidth, screenHeight);
                    }
                }
            }
        });
    }

    private void setMirrorContentView() {
        Configuration overrideConfig = new Configuration(getResources().getConfiguration());
        overrideConfig.orientation = landscape
                ? Configuration.ORIENTATION_LANDSCAPE
                : Configuration.ORIENTATION_PORTRAIT;
        View root = LayoutInflater.from(createConfigurationContext(overrideConfig))
                .inflate(R.layout.surface, null);
        setContentView(root);
    }

    private void setupNavBar() {
        final LinearLayout nav_bar = findViewById(R.id.nav_button_bar);
        if (nav_bar == null) {
            return;
        }
        if (PreUtils.get(context, Constant.CONTROL_NAV, false) &&
                !PreUtils.get(context, Constant.CONTROL_NO, false)) {
            nav_bar.setVisibility(LinearLayout.VISIBLE);
        } else {
            nav_bar.setVisibility(LinearLayout.GONE);
        }
    }

    private void bindMirrorSurfaceHolder() {
        surfaceView = findViewById(R.id.decoder_surface);
        if (surfaceView == null) {
            return;
        }
        if (surfaceCallback != null) {
            surfaceView.getHolder().removeCallback(surfaceCallback);
        }
        surfaceCallback = new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surface = holder.getSurface();
                // 仅在用户主动发起连接（first_time）且服务未绑定时启动服务。
                // 横屏旋转/退出后 surface 重建时（first_time=false）不重启服务，
                // 否则会误启动 scrcpy 会话，导致与下次连接冲突。
                if (!serviceBound && first_time) {
                    start_Scrcpy_service();
                } else if (scrcpy != null && isSurfaceReady(surface)) {
                    scrcpy.setParms(surface, screenWidth, screenHeight);
                    refreshMirrorLayout();
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                surface = holder.getSurface();
                if (serviceBound && scrcpy != null && isSurfaceReady(surface)) {
                    scrcpy.setParms(surface, screenWidth, screenHeight);
                    refreshMirrorLayout();
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surface = null;
            }
        };
        surfaceView.getHolder().addCallback(surfaceCallback);
    }

    private void setupMirrorContentView() {
        syncLandscapeFromConfiguration();
        setMirrorContentView();
        applyMirrorImmersiveMode();
        bindMirrorSurfaceHolder();
        setupNavBar();
        linearLayout = findViewById(R.id.container1);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!first_time && serviceBound) {
            result_of_Rotation = true;
            setupMirrorContentView();
        }
    }

    private boolean isSurfaceReady(Surface displaySurface) {
        if (displaySurface == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return displaySurface.isValid();
        }
        return true;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void start_screen_copy_magic() {
        stopStatusMonitor();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        setupMirrorContentView();
    }

    private void start_Scrcpy_service() {
        Intent intent = new Intent(this, Scrcpy.class);
        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void loadNewRotation() {
        ThreadUtils.post(() -> {
            result_of_Rotation = true;
            applyClientOrientationFromRemote();
            refreshMirrorLayout();
        });
    }

    @Override
    public void errorDisconnect() {
        // 必须退出
        // 退出重连
        Dialog.displayDialog(this, getString(R.string.disconnect),
                getString(R.string.disconnect_ask), () -> {
                    if (serviceBound) {
                        showMainView();
                        first_time = true;
                    } else {
                        MainActivity.this.finish();
                    }
                }, false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (usbAdb != null) {
            usbAdb.close();
            usbAdb = null;
        }
        try {
            unregisterReceiver(usbPermissionReceiver);
        } catch (Exception e) {
            // Receiver may not be registered.
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (resumeScrcpy && !isChangingConfigurations()) {
            // 返回到主页面，属于用户主动断开场景（旋转屏幕时不断开）
            showMainView(true);
            first_time = true;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d("Scrcpy", "onStart: " + serviceBound);
        if (resumeScrcpy) {
            if (!serviceBound) {
                resumeScrcpy = false;
                connectScrcpyServer(PreUtils.get(context, Constant.CONTROL_REMOTE_ADDR, ""));
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d("Scrcpy", "onPause: " + serviceBound);
        if (first_time) {
            stopStatusMonitor();
        }
        if (serviceBound && scrcpy != null) {
            scrcpy.pause();
            resumeScrcpy = true;
        }
    }

    /**
     * Registered for the whole lifetime of the activity: the system USB
     * permission dialog pauses this activity, and the result broadcast is sent
     * while we are paused. Unregistering in onPause (as before) lost the result,
     * so users had to press "Start" twice.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerUsbPermissionReceiver() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION),
                        Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (first_time) {
            startStatusMonitor();
        }
        if (!first_time && !result_of_Rotation) {
            final View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            if (serviceBound) {
                if (surfaceView != null) {
                    Surface currentSurface = surfaceView.getHolder().getSurface();
                    if (isSurfaceReady(currentSurface)) {
                        surface = currentSurface;
                        scrcpy.setParms(surface, screenWidth, screenHeight);
                    }
                }
                scrcpy.resume();
            }
        }
        if (resumeScrcpy && !result_of_Rotation && scrcpy != null) {
            scrcpy.resume();
        }
        resumeScrcpy = false;  // 两处都要resumeScrcpy设置为false
        result_of_Rotation = false;
    }

    private void handleBackPressed() {
        if (timestamp == 0) {
            if (serviceBound) {
                timestamp = SystemClock.uptimeMillis();
                Toast.makeText(context, R.string.press_again_to_exit, Toast.LENGTH_SHORT).show();
            } else {
                finish();
            }
        } else {
            long now = SystemClock.uptimeMillis();
            if (now < timestamp + 1000) {
                timestamp = 0;
                if (serviceBound) {
                    showMainView(true);
                    first_time = true;
                } else {
                    finish();
                }
            }
            timestamp = 0;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        if (sensorEvent.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            if (sensorEvent.values[0] == 0) {
                if (serviceBound) {
                    // 该事件会使远程手机 按下电源键，触发方式：按住距离传感器，然后点击屏幕即可锁屏
                    // 发送横竖屏会导致抬起事件无效
                    // scrcpy.sendKeyevent(28);
                }
            } else {
                if (serviceBound) {
                    // 发送横竖屏会导致抬起事件无效
                    // scrcpy.sendKeyevent(29);
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    private void confirmRebootRemoteDevice() {
        getAttributes();
        if (TextUtils.isEmpty(serverAdr)) {
            Toast.makeText(context, R.string.error_address_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] serverInfo = Util.getServerHostAndPort(serverAdr);
        String device = serverInfo[0] + ":" + serverInfo[1];
        Dialog.displayDialog(this,
                getString(R.string.reboot_title),
                getString(R.string.reboot_ask, device),
                () -> rebootRemoteDevice(device),
                () -> {
                });
    }

    private void rebootRemoteDevice(String device) {
        Progress.showDialog(MainActivity.this, getString(R.string.reboot_wait));
        ThreadUtils.workPost(() -> {
            AdbHelper.adbCmd(App.mContext, "connect", device);
            String result = AdbHelper.adbCmd(App.mContext, "-s", device, "reboot");
            SessionLog.i("ADB reboot device=" + device + " result=" + result);
            ThreadUtils.post(() -> {
                Progress.closeDialog();
                if (MainActivity.this.isFinishing()) {
                    return;
                }
                Toast.makeText(context, getString(R.string.reboot_sent, device), Toast.LENGTH_SHORT).show();
                startStatusMonitor();
            });
        });
    }

    private void confirmPowerOffRemoteDevice() {
        getAttributes();
        if (TextUtils.isEmpty(serverAdr)) {
            Toast.makeText(context, R.string.error_address_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] serverInfo = Util.getServerHostAndPort(serverAdr);
        String device = serverInfo[0] + ":" + serverInfo[1];
        Dialog.displayDialog(this,
                getString(R.string.power_off_title),
                getString(R.string.power_off_ask, device),
                () -> powerOffRemoteDevice(device),
                () -> {
                });
    }

    private void powerOffRemoteDevice(String device) {
        Progress.showDialog(MainActivity.this, getString(R.string.power_off_wait));
        ThreadUtils.workPost(() -> {
            AdbHelper.adbCmd(App.mContext, "connect", device);
            String result = AdbHelper.adbCmd(App.mContext, "-s", device, "reboot", "-p");
            SessionLog.i("ADB power off device=" + device + " result=" + result);
            ThreadUtils.post(() -> {
                Progress.closeDialog();
                if (MainActivity.this.isFinishing()) {
                    return;
                }
                Toast.makeText(context, getString(R.string.power_off_sent, device), Toast.LENGTH_SHORT).show();
                startStatusMonitor();
            });
        });
    }

    private void connectScrcpyServer(String rawServerAdr) {
        String normalized = AdbAddress.normalize(rawServerAdr);
        final String serverAdr = normalized != null ? normalized : rawServerAdr;
        if (!TextUtils.isEmpty(serverAdr)) {
            saveHistory(serverAdr);  // 保存到历史记录
            String[] serverInfo = Util.getServerHostAndPort(serverAdr);
            String serverHost = serverInfo[0];
            int serverPort = Integer.parseInt(serverInfo[1]);
            int localForwardPort = Scrcpy.LOCAL_FORWART_PORT;

            Progress.showDialog(MainActivity.this, getString(R.string.please_wait));
            ThreadUtils.workPost(() -> {
                AdbHelper.writeAssetsJarServer(App.mContext);
                if (autoResolutionEnabled) {
                    ResolutionHelper.AutoResolution autoResolution =
                            ResolutionHelper.computeAuto(context, serverAdr);
                    screenWidth = autoResolution.displayWidth;
                    screenHeight = autoResolution.displayHeight;
                    resolutionLimitSource = autoResolution.limitSource;
                    final String resolutionMessage = formatResolutionMessage(autoResolution, false);
                    ThreadUtils.post(() -> updateResolutionInfoText(resolutionMessage));
                }
                Options options = new Options();
                options.setIp(Scrcpy.LOCAL_IP);
                options.setMaxSize(Math.max(screenHeight, screenWidth));
                options.setBitRate(videoBitrate);
                options.setTunnelForward(true);
                options.setEnableAudioForward(PreUtils.get(context, Constant.AUDIO_FORWARD, true));
                options.setVideoCodec(videoCodec);
                options.setVideoEncoder(videoEncoder);
                options.setFrameRate(videoFrameRate);
                SendCommands.CmdStatus sendStatus = sendCommands.SendAdbCommands(context, serverHost,
                        serverPort,
                        localForwardPort,
                        options,
                        step -> Progress.updateMessage(getString(step)));
                if (sendStatus == SendCommands.CmdStatus.SUCCESS) {
                    ThreadUtils.post(() -> {
                        if (!MainActivity.this.isFinishing()) {
                            // 进入主线程
                            Log.e("Scrcpy: ", "from startButton");
                            start_screen_copy_magic();
                        }
                    });
                } else {
                    final AdbHelper.ConnectResult failure = sendCommands.getLastFailure();
                    final String failedStep = sendCommands.getLastFailureStep();
                    SessionLog.i("Connect failed " + serverAdr
                            + " reason=" + (failure == null ? "server" : failure.failure)
                            + " detail=" + (failure == null ? failedStep : failure.detail));
                    ThreadUtils.post(() -> {
                        Progress.closeDialog();
                        startStatusMonitor();
                        connectExitExt();
                        if (!headlessMode && !MainActivity.this.isFinishing()) {
                            ConnectAssistant.showConnectFailure(MainActivity.this, failure, failedStep,
                                    serverAdr, MainActivity.this::onStartClicked, MainActivity.this::applyTarget);
                        }
                    });
                }
            });
        } else {
            Toast.makeText(context, R.string.error_address_empty, Toast.LENGTH_SHORT).show();
            startStatusMonitor();
            connectExitExt();
        }
    }

    private void startStatusMonitor() {
        if (headlessMode || isFinishing()) {
            return;
        }
        View led = findViewById(R.id.status_led);
        if (led == null) {
            return;
        }
        statusMonitorActive = true;
        scheduleStatusCheck(0);
    }

    private void stopStatusMonitor() {
        statusMonitorActive = false;
        statusCheckGeneration++;
    }

    private void scheduleStatusCheck(long delayMs) {
        final int gen = ++statusCheckGeneration;
        ThreadUtils.postDelayed(() -> {
            if (!statusMonitorActive || gen != statusCheckGeneration) {
                return;
            }
            EditText hostEdit = findViewById(R.id.editText_server_host);
            final String hostText = hostEdit != null ? hostEdit.getText().toString().trim() : "";
            ThreadUtils.execute(() -> {
                boolean online = false;
                if (!TextUtils.isEmpty(hostText)) {
                    if (UsbShellCompat.isUsbSelection(hostText)) {
                        online = isUsbDeviceOnline();
                    } else {
                        String[] serverInfo = Util.getServerHostAndPort(hostText);
                        try {
                            int port = Integer.parseInt(serverInfo[1]);
                            online = AdbHelper.isDeviceOnline(App.mContext, serverInfo[0], port);
                        } catch (NumberFormatException ignored) {
                            online = false;
                        }
                    }
                }
                final boolean onlineNow = online;
                ThreadUtils.post(() -> {
                    if (!statusMonitorActive || gen != statusCheckGeneration) {
                        return;
                    }
                    applyStatusLed(onlineNow);
                    scheduleStatusCheck(4000);
                });
            });
        }, delayMs);
    }

    /**
     * A USB device is considered online when it is attached, exposes an ADB
     * interface and has been granted USB access permission.
     */
    private boolean isUsbDeviceOnline() {
        if (usbManager == null) {
            usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        }
        UsbDevice device = UsbAdb.findAdbDevice(usbManager);
        if (device == null || AdbConnection.findAdbInterface(device) == null) {
            return false;
        }
        return usbManager.hasPermission(device);
    }

    /**
     * 进入 ADB 命令行前，彻底停止 scrcpy/USB 会话。
     * <p>
     * 1. 先停止 scrcpy 服务（停止视频流），再关闭 usbAdb：释放 USB 设备
     *    连接与 7008 端口（bridge）。若先关 USB 连接，readerThread 阻塞在
     *    bulkTransfer 时强制中断会导致设备重新枚举，授权丢失；
     * 2. 解绑服务并清除 resumeScrcpy，防止从命令行页面返回后
     *    onResume/onStart 自动恢复远程画面。
     */
    private void cleanupUsbSessionForShell() {
        if (scrcpy != null) {
            try {
                scrcpy.StopService();
            } catch (Exception e) {
                Log.e("Scrcpy", "stop scrcpy failed", e);
            }
            scrcpy = null;
        }
        if (usbAdb != null) {
            try {
                usbAdb.close();
            } catch (Exception e) {
                Log.e("Scrcpy", "close usbAdb failed", e);
            }
            usbAdb = null;
        }
        try {
            unbindService(serviceConnection);
        } catch (Exception e) {
            // 可能未绑定，忽略
        }
        serviceBound = false;
        resumeScrcpy = false;
    }

    private void applyStatusLed(boolean online) {
        View led = findViewById(R.id.status_led);
        TextView label = findViewById(R.id.status_led_label);
        if (led == null) {
            return;
        }
        int color = getColor(online ? R.color.status_online : R.color.status_offline);
        View pill = findViewById(R.id.status_pill);
        if (pill != null && pill.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) pill.getBackground().mutate()).setColor(getColor(
                    online ? R.color.status_online_container : R.color.status_offline_container));
        }
        Drawable background = led.getBackground();
        if (background instanceof GradientDrawable) {
            ((GradientDrawable) background.mutate()).setColor(color);
        } else if (background != null) {
            background.mutate().setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP);
        } else {
            led.setBackgroundColor(color);
        }
        if (label != null) {
            label.setText(online ? R.string.status_online : R.string.status_offline);
            label.setTextColor(color);
        }
    }

    private String getUsbAdbDeviceLabel() {
        if (usbManager == null) {
            usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        }
        UsbDevice device = UsbAdb.findAdbDevice(usbManager);
        if (device == null || AdbConnection.findAdbInterface(device) == null) {
            return null;
        }
        return UsbShellCompat.USB_PREFIX + usbDeviceName(device);
    }

    private String usbDeviceName(UsbDevice device) {
        // Prefer the serial number so it matches `adb devices`; fall back to the
        // product name (serial needs USB permission on newer Android versions).
        try {
            if (usbManager.hasPermission(device)) {
                String serial = device.getSerialNumber();
                if (!TextUtils.isEmpty(serial)) {
                    return serial;
                }
            }
        } catch (Exception ignore) {
            // Serial not accessible without permission.
        }
        String product = device.getProductName();
        if (!TextUtils.isEmpty(product)) {
            return product;
        }
        String name = device.getDeviceName();
        return TextUtils.isEmpty(name) ? "device" : name;
    }

    private void connectViaUsb() {
        if (usbManager == null) {
            usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        }
        UsbDevice device = UsbAdb.findAdbDevice(usbManager);
        if (device == null) {
            Toast.makeText(context, R.string.usb_no_device, Toast.LENGTH_LONG).show();
            return;
        }
        if (AdbConnection.findAdbInterface(device) == null) {
            Toast.makeText(context, R.string.usb_no_adb_interface, Toast.LENGTH_LONG).show();
            return;
        }
        if (usbManager.hasPermission(device)) {
            startUsbConnect(device);
        } else {
            requestUsbPermission(device, UsbAction.MIRROR);
        }
    }

    private void requestUsbPermission(UsbDevice device, UsbAction action) {
        pendingUsbAction = action;
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_MUTABLE : 0;
        // The intent must be explicit: a mutable PendingIntent wrapping an implicit
        // intent throws IllegalArgumentException on Android 14+ (targetSdk >= 34),
        // which made USB connections fail before the permission prompt appeared.
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        PendingIntent permissionIntent = PendingIntent.getBroadcast(this, action.ordinal(), intent, flags);
        usbManager.requestPermission(device, permissionIntent);
    }

    // ------------------------------------------------------------------
    // USB -> Wi-Fi: enable "adb tcpip 5555" over USB, then connect by IP
    // ------------------------------------------------------------------

    private void enableWifiDebuggingOverUsb() {
        if (usbManager == null) {
            usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        }
        UsbDevice device = UsbAdb.findAdbDevice(usbManager);
        if (device == null) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.action_usb_to_wifi)
                    .setIcon(R.drawable.ic_usb)
                    .setMessage(R.string.usb_to_wifi_explain)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        if (AdbConnection.findAdbInterface(device) == null) {
            Toast.makeText(context, R.string.usb_no_adb_interface, Toast.LENGTH_LONG).show();
            return;
        }
        // The USB device must not be held by a mirroring session.
        cleanupUsbSessionForShell();
        if (usbManager.hasPermission(device)) {
            startUsbToWifi(device);
        } else {
            requestUsbPermission(device, UsbAction.ENABLE_WIFI);
        }
    }

    private void startUsbToWifi(UsbDevice device) {
        pendingUsbAction = UsbAction.MIRROR;
        stopStatusMonitor();
        Progress.showDialog(MainActivity.this, getString(R.string.action_usb_to_wifi),
                getString(R.string.usb_to_wifi_step_usb));
        ThreadUtils.workPost(() -> {
            String error = null;
            String address = null;
            UsbAdb adb = null;
            try {
                adb = UsbAdb.connect(context, usbManager, device);
                Progress.updateMessage(getString(R.string.usb_to_wifi_step_ip));
                String ip = adb.findWifiIpAddress();
                if (ip == null) {
                    error = getString(R.string.usb_to_wifi_no_wifi);
                } else {
                    Progress.updateMessage(getString(R.string.usb_to_wifi_step_tcpip));
                    String reply = adb.enableTcpip(AdbAddress.DEFAULT_PORT);
                    SessionLog.i("USB tcpip reply: " + reply + " ip=" + ip);
                    address = ip + ":" + AdbAddress.DEFAULT_PORT;
                }
            } catch (Exception e) {
                Log.e("Scrcpy", "USB -> Wi-Fi failed", e);
                error = e.getMessage();
            } finally {
                if (adb != null) {
                    try {
                        adb.close();
                    } catch (Exception ignored) {
                    }
                }
            }

            AdbHelper.ConnectResult result = null;
            if (address != null) {
                // adbd restarts in TCP mode; give it a moment before connecting.
                Progress.updateMessage(getString(R.string.usb_to_wifi_step_connect, address));
                SystemClock.sleep(2000);
                String host = AdbAddress.host(address);
                result = AdbHelper.connectDevice(context, host, AdbAddress.DEFAULT_PORT,
                        step -> Progress.updateMessage(getString(step)));
                if (!result.success) {
                    // One more try: some devices need a little longer to restart adbd.
                    SystemClock.sleep(2500);
                    result = AdbHelper.connectDevice(context, host, AdbAddress.DEFAULT_PORT,
                            step -> Progress.updateMessage(getString(step)));
                }
            }
            final String finalError = error;
            final String finalAddress = address;
            final AdbHelper.ConnectResult finalResult = result;
            ThreadUtils.post(() -> {
                Progress.closeDialog();
                if (isFinishing()) {
                    return;
                }
                if (finalAddress != null) {
                    applyTarget(finalAddress);
                }
                if (finalError != null) {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.usb_to_wifi_failed_title)
                            .setMessage(getString(R.string.usb_to_wifi_failed, finalError))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                } else if (finalResult != null && finalResult.success) {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.usb_to_wifi_done_title)
                            .setIcon(R.drawable.ic_wifi)
                            .setMessage(getString(R.string.usb_to_wifi_done, finalAddress))
                            .setPositiveButton(R.string.action_start, (d, w) -> onStartClicked())
                            .setNegativeButton(R.string.action_close, null)
                            .show();
                } else {
                    ConnectAssistant.showConnectFailure(this, finalResult, null, finalAddress,
                            this::onStartClicked, this::applyTarget);
                }
                startStatusMonitor();
            });
        });
    }

    private void startUsbConnect(UsbDevice device) {
        if (!Progress.isShowing()) {
            Progress.showDialog(MainActivity.this, getString(R.string.please_wait));
        }
        ThreadUtils.workPost(() -> {
            UsbAdb adb = null;
            try {
                // 先释放上一次的 USB 会话（释放 7008 监听端口和 USB 设备），再建立新连接
                if (usbAdb != null) {
                    usbAdb.close();
                    usbAdb = null;
                }
                // 移除 adb server 中的端口转发（tcp:7008 → tcp:7007）。
                // WiFi（IP）连接时会在 adb server 内创建该 forward 并持续监听 7008，
                // 若不移除，USB 连接的 AdbForwardBridge 绑定 7008 会报
                // "bind failed: address already in use"。
                try {
                    AdbHelper.adbCmd(App.mContext, "forward", "--remove-all");
                } catch (Exception ignored) {
                }
                AdbHelper.writeAssetsJarServer(App.mContext);
                File jar = AdbHelper.getServerJarFile(context);
                adb = UsbAdb.connect(context, usbManager, device);
                adb.startServerAndForward(jar, Scrcpy.LOCAL_FORWART_PORT, Scrcpy.LOCAL_IP,
                        videoBitrate, Math.max(screenHeight, screenWidth),
                        PreUtils.get(context, Constant.AUDIO_FORWARD, true));
                usbAdb = adb;
                adb = null;  // 所有权已转移给 usbAdb，防止 catch 误关
                ThreadUtils.post(() -> {
                    if (!MainActivity.this.isFinishing()) {
                        Log.e("Scrcpy: ", "from USB connect");
                        start_screen_copy_magic();
                    } else {
                        // activity 已销毁，立即释放连接，避免监听端口泄漏
                        if (usbAdb != null) {
                            usbAdb.close();
                            usbAdb = null;
                        }
                    }
                });
            } catch (Exception e) {
                Log.e("Scrcpy", "USB connect failed", e);
                final String message = e.getMessage();
                // 失败时释放已建立的 bridge/connection，防止 7008 端口被占用
                if (adb != null) {
                    adb.close();
                }
                ThreadUtils.post(() -> {
                    Progress.closeDialog();
                    Toast.makeText(context,
                            getString(R.string.usb_connect_failed) + " " + message,
                            Toast.LENGTH_LONG).show();
                    connectExitExt();
                });
            }
        });
    }

    /**
     * 连接成功了，而且成功的显示了画面出来
     */
    protected void connectSuccessExt() {
        Dialog.closeDialogs();
        if (autoResolutionEnabled && resolutionLimitSource != null) {
            Toast.makeText(context, formatActiveStreamResolutionMessage(), Toast.LENGTH_LONG).show();
        }
    }

    protected void connectExitExt() {
        this.connectExitExt(false);
    }

    /**
     * 连接失败的额外处理
     */
    protected void connectExitExt(boolean userDisconnect) {
        if (!userDisconnect) {  // userDisconnect : 用户主动断开连接
            // 如果自动断开了端口连接，在系统恢复时，重启adb，避免
            // 警告！！！ 重启将会导致 adb 配对过程失效，从而无法连接新设备，需要更智能的重启机制
            // AdbHelper.restartAdb();
        }
        // 移除 adb server 中的端口转发（WiFi 连接创建的 tcp:7008 → tcp:7007），
        // 否则该 forward 会持续占用 7008 端口，之后用 USB 连接会报
        // "bind failed: address already in use"
        try {
            AdbHelper.adbCmd(App.mContext, "forward", "--remove-all");
        } catch (Exception ignored) {
        }
        if (headlessMode && !resumeScrcpy && !result_of_Rotation) {
            if (!userDisconnect) {
                Dialog.displayDialog(this, getString(R.string.connect_faild),
                        getString(R.string.connect_faild_ask), () -> {
                            // 重试连接
                            connectScrcpyServer(PreUtils.get(context, Constant.CONTROL_REMOTE_ADDR, ""));
                        }, () -> {
                            // 取消重试
                            finishAndRemoveTask();
                        });
            } else {
                finishAndRemoveTask();
            }
        }
//        Log.i("Scrcpy", "headlessMode： " + headlessMode +
//                " ,resumeScrcpy: " + resumeScrcpy + " ,result_of_Rotation: " + result_of_Rotation);
    }

}
