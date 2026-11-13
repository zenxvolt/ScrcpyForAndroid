package org.client.scrcpy;

import android.app.Activity;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputLayout;

import org.client.scrcpy.utils.AdbAddress;
import org.client.scrcpy.utils.AdbDiscovery;
import org.client.scrcpy.utils.AdbHelper;
import org.client.scrcpy.utils.SessionLog;
import org.client.scrcpy.utils.ThreadUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dialogs that make it easier to get a target device connected:
 * <ul>
 *     <li>{@link #showDiscoverDialog}: lists devices found on the network via mDNS</li>
 *     <li>{@link #showPairDialog}: Android 11+ "Pair device with pairing code" + auto connect</li>
 *     <li>{@link #showHowToDialog}: short guide of the supported connection methods</li>
 *     <li>{@link #showConnectFailure}: explains why a connection failed and what to do</li>
 * </ul>
 */
public final class ConnectAssistant {

    private ConnectAssistant() {
    }

    public interface TargetCallback {
        /** A usable {@code host:port} was chosen (and is connected when coming from pairing). */
        void onTarget(String address);
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    public static void showDiscoverDialog(Activity activity, TargetCallback onPicked) {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_discover, null);
        ListView listView = view.findViewById(R.id.discover_list);
        TextView status = view.findViewById(R.id.discover_status);
        LinearProgressIndicator progress = view.findViewById(R.id.discover_progress);

        List<AdbDiscovery.Service> services = new ArrayList<>();
        ArrayAdapter<AdbDiscovery.Service> adapter = new ArrayAdapter<AdbDiscovery.Service>(activity,
                R.layout.item_discovered_device, R.id.discovered_title, services) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View item = super.getView(position, convertView, parent);
                AdbDiscovery.Service service = getItem(position);
                TextView title = item.findViewById(R.id.discovered_title);
                TextView subtitle = item.findViewById(R.id.discovered_subtitle);
                ImageView icon = item.findViewById(R.id.discovered_icon);
                if (service != null) {
                    title.setText(prettyName(service.name));
                    int kindRes;
                    switch (service.kind) {
                        case PAIRING:
                            kindRes = R.string.discover_kind_pairing;
                            icon.setImageResource(R.drawable.ic_link);
                            break;
                        case LEGACY:
                            kindRes = R.string.discover_kind_legacy;
                            icon.setImageResource(R.drawable.ic_wifi);
                            break;
                        default:
                            kindRes = R.string.discover_kind_connect;
                            icon.setImageResource(R.drawable.ic_phone);
                            break;
                    }
                    subtitle.setText(activity.getString(kindRes, service.address()));
                }
                return item;
            }
        };
        listView.setAdapter(adapter);

        AdbDiscovery discovery = new AdbDiscovery(activity, new AdbDiscovery.Listener() {
            @Override
            public void onServiceFound(AdbDiscovery.Service service) {
                for (int i = services.size() - 1; i >= 0; i--) {
                    AdbDiscovery.Service existing = services.get(i);
                    if (existing.kind == service.kind && existing.name.equals(service.name)) {
                        services.remove(i);
                    }
                }
                services.add(service);
                adapter.notifyDataSetChanged();
                status.setText(R.string.discover_tap_to_use);
            }

            @Override
            public void onServiceLost(AdbDiscovery.Kind kind, String name) {
                for (int i = services.size() - 1; i >= 0; i--) {
                    AdbDiscovery.Service existing = services.get(i);
                    if (existing.kind == kind && existing.name.equals(name)) {
                        services.remove(i);
                    }
                }
                adapter.notifyDataSetChanged();
                if (services.isEmpty()) {
                    status.setText(R.string.discover_searching);
                }
            }
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.discover_title)
                .setIcon(R.drawable.ic_search)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.action_pair, (d, w) -> showPairDialog(activity, null, onPicked))
                .create();

        Runnable noResultHint = () -> {
            if (dialog.isShowing() && services.isEmpty()) {
                progress.setVisibility(View.INVISIBLE);
                status.setText(R.string.discover_nothing_found);
            }
        };

        listView.setOnItemClickListener((parent, itemView, position, id) -> {
            AdbDiscovery.Service service = services.get(position);
            dialog.dismiss();
            if (service.kind == AdbDiscovery.Kind.PAIRING) {
                showPairDialog(activity, service.address(), onPicked);
            } else if (onPicked != null) {
                onPicked.onTarget(service.address());
            }
        });
        dialog.setOnDismissListener(d -> {
            ThreadUtils.removeCallbacks(noResultHint);
            discovery.stop();
        });
        dialog.show();
        discovery.start();
        ThreadUtils.postDelayed(noResultHint, 10_000);
    }

    private static String prettyName(String serviceName) {
        // Wireless debugging names look like "adb-R58N12345AB-AbCdEf".
        if (serviceName != null && serviceName.startsWith("adb-")) {
            String rest = serviceName.substring(4);
            int dash = rest.lastIndexOf('-');
            return dash > 0 ? rest.substring(0, dash) : rest;
        }
        return serviceName;
    }

    // ------------------------------------------------------------------
    // Pairing (Android 11+ wireless debugging)
    // ------------------------------------------------------------------

    /**
     * @param pairAddress optional {@code host:port} of the pairing service (pre-filled)
     */
    public static void showPairDialog(Activity activity, String pairAddress, TargetCallback onConnected) {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_pair, null);
        TextInputLayout addressLayout = view.findViewById(R.id.pair_address_layout);
        TextInputLayout codeLayout = view.findViewById(R.id.pair_code_layout);
        TextInputLayout connectPortLayout = view.findViewById(R.id.pair_connect_port_layout);
        TextView addressEdit = view.findViewById(R.id.pair_address);
        TextView codeEdit = view.findViewById(R.id.pair_code);
        TextView connectPortEdit = view.findViewById(R.id.pair_connect_port);
        View statusRow = view.findViewById(R.id.pair_status_row);
        View statusProgress = view.findViewById(R.id.pair_progress);
        TextView statusText = view.findViewById(R.id.pair_status);

        if (!TextUtils.isEmpty(pairAddress)) {
            addressEdit.setText(pairAddress);
        }
        clearErrorOnEdit(addressEdit, addressLayout);
        clearErrorOnEdit(codeEdit, codeLayout);

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.pair_title)
                .setIcon(R.drawable.ic_link)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.pair_action, null)
                .create();
        dialog.setCanceledOnTouchOutside(false);

        dialog.setOnShowListener(d -> {
            if (TextUtils.isEmpty(addressEdit.getText())) {
                addressEdit.requestFocus();
            } else {
                codeEdit.requestFocus();
            }
            View positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(v -> {
                String address = AdbAddress.normalize(addressEdit.getText().toString());
                String code = codeEdit.getText().toString().trim();
                String connectPortText = connectPortEdit.getText().toString().trim();
                boolean valid = true;
                if (address == null || !addressEdit.getText().toString().contains(":")) {
                    addressLayout.setError(activity.getString(R.string.pair_error_address));
                    valid = false;
                }
                if (!code.matches("\\d{6}")) {
                    codeLayout.setError(activity.getString(R.string.pair_error_code));
                    valid = false;
                }
                if (!valid) {
                    return;
                }
                positive.setEnabled(false);
                statusRow.setVisibility(View.VISIBLE);
                statusProgress.setVisibility(View.VISIBLE);
                statusText.setText(R.string.pair_status_pairing);

                ThreadUtils.workPost(() -> runPairing(activity, dialog, address, code, connectPortText,
                        statusText, statusProgress, positive, connectPortLayout, onConnected));
            });
        });
        dialog.show();
    }

    private static void runPairing(Activity activity, AlertDialog dialog, String pairAddress, String code,
                                   String connectPortText, TextView statusText, View statusProgress,
                                   View positive, TextInputLayout connectPortLayout,
                                   TargetCallback onConnected) {
        String host = AdbAddress.host(pairAddress);
        String output = AdbHelper.pair(activity, pairAddress, code);
        SessionLog.i("adb pair " + pairAddress + " -> " + output);
        if (!AdbHelper.isPairSuccess(output)) {
            String reason = TextUtils.isEmpty(output) ? "" : output.trim();
            ThreadUtils.post(() -> {
                statusProgress.setVisibility(View.GONE);
                statusText.setText(activity.getString(R.string.pair_status_failed, reason));
                positive.setEnabled(true);
            });
            return;
        }

        // Find the "connect" port: user supplied, already connected by adb's own mDNS, or discovery.
        ThreadUtils.post(() -> statusText.setText(R.string.pair_status_finding_port));
        int connectPort = -1;
        if (!TextUtils.isEmpty(connectPortText)) {
            try {
                connectPort = Integer.parseInt(connectPortText);
            } catch (NumberFormatException ignored) {
            }
        }
        if (connectPort <= 0) {
            connectPort = discoverConnectPort(activity, host, 8_000);
        }
        if (connectPort <= 0) {
            ThreadUtils.post(() -> {
                statusProgress.setVisibility(View.GONE);
                statusText.setText(R.string.pair_status_need_port);
                connectPortLayout.setError(activity.getString(R.string.pair_connect_port_required));
                connectPortLayout.requestFocus();
                positive.setEnabled(true);
            });
            return;
        }

        final int port = connectPort;
        ThreadUtils.post(() -> statusText.setText(activity.getString(R.string.pair_status_connecting,
                host + ":" + port)));
        AdbHelper.ConnectResult result = AdbHelper.connectDevice(activity, host, port,
                res -> ThreadUtils.post(() -> statusText.setText(res)));
        ThreadUtils.post(() -> {
            if (activity.isFinishing()) {
                return;
            }
            if (result.success) {
                dialog.dismiss();
                if (onConnected != null) {
                    onConnected.onTarget(host + ":" + port);
                }
            } else {
                statusProgress.setVisibility(View.GONE);
                statusText.setText(activity.getString(R.string.pair_status_paired_connect_failed,
                        describeFailure(activity, result.failure, host + ":" + port)));
                positive.setEnabled(true);
            }
        });
    }

    /** Waits (on a worker thread) for an {@code _adb-tls-connect} service of {@code host}. */
    private static int discoverConnectPort(Activity activity, String host, long timeoutMs) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Integer> found = new AtomicReference<>(-1);
        AtomicReference<AdbDiscovery> discoveryRef = new AtomicReference<>();
        ThreadUtils.post(() -> {
            AdbDiscovery discovery = new AdbDiscovery(activity, new AdbDiscovery.Listener() {
                @Override
                public void onServiceFound(AdbDiscovery.Service service) {
                    if (service.kind == AdbDiscovery.Kind.CONNECT && host.equals(service.host)) {
                        found.set(service.port);
                        latch.countDown();
                    }
                }

                @Override
                public void onServiceLost(AdbDiscovery.Kind kind, String name) {
                }
            });
            discoveryRef.set(discovery);
            discovery.start(AdbDiscovery.Kind.CONNECT);
        });
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ThreadUtils.post(() -> {
            AdbDiscovery discovery = discoveryRef.get();
            if (discovery != null) {
                discovery.stop();
            }
        });
        if (found.get() > 0) {
            return found.get();
        }
        // adb's own mDNS client may have auto-connected after pairing.
        String devices = AdbHelper.adbCmd(activity, "devices");
        if (devices != null) {
            for (String line : devices.split("\\r?\\n")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2 && parts[0].startsWith(host + ":")) {
                    try {
                        return Integer.parseInt(parts[0].substring(host.length() + 1));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return -1;
    }

    private static void clearErrorOnEdit(TextView edit, TextInputLayout layout) {
        edit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                layout.setError(null);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    // ------------------------------------------------------------------
    // Help & failures
    // ------------------------------------------------------------------

    public static void showHowToDialog(Activity activity) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.howto_title)
                .setIcon(R.drawable.ic_info)
                .setMessage(R.string.howto_message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    public static String describeFailure(Activity activity, AdbAddress.Failure failure, String address) {
        if (failure == null) {
            return activity.getString(R.string.connect_error_unknown, address);
        }
        switch (failure) {
            case UNAUTHORIZED:
                return activity.getString(R.string.connect_error_unauthorized, address);
            case REFUSED:
                return activity.getString(R.string.connect_error_refused, address);
            case UNREACHABLE:
            case TIMEOUT:
                return activity.getString(R.string.connect_error_unreachable, address);
            case BAD_ADDRESS:
                return activity.getString(R.string.connect_error_bad_address, address);
            case OFFLINE:
                return activity.getString(R.string.connect_error_offline, address);
            default:
                return activity.getString(R.string.connect_error_unknown, address);
        }
    }

    /**
     * Explain why connecting to {@code address} failed and offer the most
     * useful next step.
     *
     * @param result     connection diagnosis, null when the failure happened later
     *                   (push / server start)
     * @param stepDetail extra detail for failures after the connection
     */
    public static void showConnectFailure(Activity activity, AdbHelper.ConnectResult result, String stepDetail,
                                          String address, Runnable retry, TargetCallback onTarget) {
        if (activity.isFinishing()) {
            return;
        }
        String message;
        int titleRes;
        AdbAddress.Failure failure = result == null ? null : result.failure;
        if (result == null) {
            titleRes = R.string.connect_error_title_server;
            message = activity.getString(R.string.connect_error_server, address);
        } else if (failure == AdbAddress.Failure.UNAUTHORIZED) {
            titleRes = R.string.connect_error_title_unauthorized;
            message = describeFailure(activity, failure, address);
        } else {
            titleRes = R.string.connect_faild;
            message = describeFailure(activity, failure, address);
        }
        String detail = result != null ? result.detail : stepDetail;
        if (!TextUtils.isEmpty(detail)) {
            message = message + "\n\nadb: " + detail;
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle(titleRes)
                .setMessage(message)
                .setNegativeButton(R.string.action_close, null);
        if (retry != null) {
            builder.setPositiveButton(R.string.action_retry, (d, w) -> retry.run());
        }
        if (failure == AdbAddress.Failure.REFUSED || failure == AdbAddress.Failure.UNREACHABLE
                || failure == AdbAddress.Failure.TIMEOUT || failure == AdbAddress.Failure.OFFLINE) {
            builder.setNeutralButton(R.string.action_discover,
                    (d, w) -> showDiscoverDialog(activity, onTarget));
        }
        builder.show();
    }
}
