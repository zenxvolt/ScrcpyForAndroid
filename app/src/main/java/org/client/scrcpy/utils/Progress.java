package org.client.scrcpy.utils;

import android.app.Activity;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.client.scrcpy.R;

/**
 * Material 3 styled, non-cancelable progress dialog (replacement for the
 * deprecated {@link android.app.ProgressDialog}).
 */
public final class Progress {

    private static AlertDialog progressDialog;
    private static TextView titleView;
    private static TextView messageView;
    private static LinearProgressIndicator linearIndicator;
    private static CircularProgressIndicator circularIndicator;

    public static void showDialog(Activity context, String title) {
        showDialog(context, title, false);
    }

    public static void showDialog(Activity context, String title, String msg) {
        showDialog(context, title, msg, false);
    }

    public static void showDialog(Activity context, String title, boolean setup) {
        showDialog(context, title, "", setup);
    }

    public static void showDialog(Activity context, String title, String msg, boolean setup) {
        ThreadUtils.post(() -> {
            if (context == null || context.isFinishing()) {
                return;
            }
            dismissQuietly();
            View view = LayoutInflater.from(context).inflate(R.layout.dialog_progress, null);
            titleView = view.findViewById(R.id.progress_title);
            messageView = view.findViewById(R.id.progress_message);
            linearIndicator = view.findViewById(R.id.progress_linear);
            circularIndicator = view.findViewById(R.id.progress_circular);
            titleView.setText(title);
            setMessageInternal(msg);
            if (setup) {
                linearIndicator.setVisibility(View.VISIBLE);
            }
            progressDialog = new MaterialAlertDialogBuilder(context)
                    .setView(view)
                    .setCancelable(false)
                    .create();
            progressDialog.setCanceledOnTouchOutside(false);
            try {
                progressDialog.show();
            } catch (Exception e) {
                e.printStackTrace();
                progressDialog = null;
            }
        });
    }

    private static void setMessageInternal(String msg) {
        if (messageView == null) {
            return;
        }
        if (TextUtils.isEmpty(msg)) {
            messageView.setVisibility(View.GONE);
        } else {
            messageView.setText(msg);
            messageView.setVisibility(View.VISIBLE);
        }
    }

    /** Update the secondary (detail) line, e.g. the current connection step. */
    public static void updateMessage(String msg) {
        ThreadUtils.post(() -> {
            if (progressDialog != null) {
                setMessageInternal(msg);
            }
        });
    }

    public static void updateDialog(String title) {
        ThreadUtils.post(() -> {
            if (progressDialog != null && titleView != null) {
                titleView.setText(title);
            }
        });
    }

    public static boolean isShowing() {
        return progressDialog != null && progressDialog.isShowing();
    }

    public static void updateDialog(int progress) {
        ThreadUtils.post(() -> {
            if (progressDialog != null && linearIndicator != null) {
                linearIndicator.setVisibility(View.VISIBLE);
                linearIndicator.setProgressCompat(progress, true);
            }
        });
    }

    public static void closeDialog() {
        ThreadUtils.post(Progress::dismissQuietly);
    }

    private static void dismissQuietly() {
        if (progressDialog != null) {
            if (progressDialog.isShowing()) {
                try {
                    progressDialog.dismiss();
                } catch (IllegalArgumentException e) {
                    e.printStackTrace();
                }
            }
            progressDialog = null;
        }
        titleView = null;
        messageView = null;
        linearIndicator = null;
        circularIndicator = null;
    }
}
