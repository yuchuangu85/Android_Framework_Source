package com.android.clockwork.globalactions;

import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.TypedArray;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Switch;
import com.android.internal.globalactions.Action;
import com.android.internal.globalactions.LongPressAction;
import com.android.internal.globalactions.SinglePressAction;
import com.android.internal.globalactions.ToggleAction;
import com.android.internal.R;
import com.android.server.policy.GlobalActionsProvider;
import com.android.server.policy.PhoneWindowManager;
import com.android.server.policy.PowerAction;
import com.android.server.policy.RestartAction;
import com.android.server.policy.WindowManagerPolicy;

final class GlobalActionsProviderImpl implements
        DialogInterface.OnDismissListener,
        DialogInterface.OnShowListener,
        GlobalActionsProvider,
        View.OnClickListener,
        View.OnLongClickListener {
    private static final int MESSAGE_DISMISS = 0;
    private static final int MESSAGE_SHOW = 2;
    private static final int MESSAGE_UPDATE_POWER_SAVER = 5;
    private static final String TAG = "GlobalActionsService";

    private final ContentResolver mContentResolver;
    private final Context mContext;
    private final WindowManagerPolicy.WindowManagerFuncs mWindowManagerFuncs;
    private final IDreamManager mDreamManager;
    private final PowerManager mPowerManager;
    private boolean mDeviceProvisioned = false;
    private boolean mIsCharging = false;
    private View mSettingsView;
    private PowerSaverAction mPowerSaverAction;
    private View mPowerSaverView;
    private Dialog mDialog;
    private LayoutInflater mInflater;
    private GlobalActionsProvider.GlobalActionsListener mListener;

    GlobalActionsProviderImpl(Context context,
            WindowManagerPolicy.WindowManagerFuncs windowManagerFuncs) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mInflater = LayoutInflater.from(mContext);
        mWindowManagerFuncs = windowManagerFuncs;

        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.getService(DreamService.DREAM_SERVICE));
        mPowerManager = mContext.getSystemService(PowerManager.class);

        // receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        context.registerReceiver(mBroadcastReceiver, filter);
    }

    public boolean isGlobalActionsDisabled() {
        return false; // always available on wear
    }

    public void setGlobalActionsListener(GlobalActionsProvider.GlobalActionsListener listener) {
        mListener = listener;
        mListener.onGlobalActionsAvailableChanged(true);
    }

    @Override
    public void showGlobalActions() {
        mDeviceProvisioned = Settings.Global.getInt(
                mContentResolver, Settings.Global.DEVICE_PROVISIONED, 0) != 0;
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
        // Ensure it runs on correct thread. Also show delayed, so that the dismiss of the previous
        // dialog completes
        mHandler.sendEmptyMessage(MESSAGE_SHOW);
    }

    @Override
    public void onShow(DialogInterface dialog) {
        if (mListener != null) {
            mListener.onGlobalActionsShown();
        }
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        mListener.onGlobalActionsDismissed();
    }

    private void awakenIfNecessary() {
        if (mDreamManager != null) {
            try {
                if (mDreamManager.isDreaming()) {
                    mDreamManager.awaken();
                }
            } catch (RemoteException e) {
                // we tried
            }
        }
    }

    private void handleShow() {
        awakenIfNecessary();
        mDialog = createDialog();
        prepareDialog();

        WindowManager.LayoutParams attrs = mDialog.getWindow().getAttributes();
        attrs.setTitle("WearGlobalActions");
        mDialog.getWindow().setAttributes(attrs);
        mDialog.show();
    }

    /**
     * Create the global actions dialog.
     * @return A new dialog.
     */
    private Dialog createDialog() {
        ViewGroup container = (ViewGroup) mInflater.inflate(R.layout.global_actions, null);

        addAction(container, new PowerAction(mContext, mWindowManagerFuncs));
        addAction(container, new RestartAction(mContext, mWindowManagerFuncs));
        mPowerSaverView = addAction(container, mPowerSaverAction = new PowerSaverAction());
        mSettingsView = addAction(container, new SettingsAction());

        Dialog dialog = new Dialog(mContext);
        dialog.setContentView(container);
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        dialog.setOnDismissListener(this);
        dialog.setOnShowListener(this);
        return dialog;
    }

    private View addAction(ViewGroup parent, Action action) {
        View v = action.create(mContext, null, parent, mInflater);

        v.setTag(action);

        v.setOnClickListener(this);
        if (action instanceof LongPressAction) {
            v.setOnLongClickListener(this);
        }

        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) v.getLayoutParams();
        params.weight = 1;

        parent.addView(v);
        return v;
    }

    @Override
    public void onClick(View v) {
        mHandler.sendEmptyMessage(MESSAGE_DISMISS);
        Object tag = v.getTag();
        if (tag instanceof Action) {
            ((Action) tag).onPress();
        }
    }

    @Override
    public boolean onLongClick(View v) {
        Object tag = v.getTag();
        if (tag instanceof LongPressAction) {
            return ((LongPressAction) tag).onLongPress();
        }
        return false;
    }

    private void prepareDialog() {
        refreshPowerSaverMode();
        mDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        mSettingsView.setVisibility(mDeviceProvisioned ? View.GONE : View.VISIBLE);
        mPowerSaverView.setVisibility(mDeviceProvisioned ? View.VISIBLE : View.GONE);
    }

    private void refreshPowerSaverMode() {
        if (mPowerSaverAction != null) {
            final boolean powerSaverOn = mPowerManager.isPowerSaveMode();
            mPowerSaverAction.updateState(
                    powerSaverOn ? ToggleAction.State.On : ToggleAction.State.Off);
        }
        if (mPowerSaverView != null) {
            mPowerSaverView.setEnabled(!mIsCharging);
        }
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                    || Intent.ACTION_SCREEN_OFF.equals(action)) {
                String reason = intent.getStringExtra(PhoneWindowManager.SYSTEM_DIALOG_REASON_KEY);
                if (!PhoneWindowManager.SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS.equals(reason)) {
                    mHandler.sendEmptyMessage(MESSAGE_DISMISS);
                }
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                mIsCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
                mHandler.sendEmptyMessage(MESSAGE_UPDATE_POWER_SAVER);
            }
        }
    };

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MESSAGE_DISMISS:
                if (mDialog != null) {
                    mDialog.dismiss();
                    mDialog = null;
                }
                break;
            case MESSAGE_SHOW:
                handleShow();
                break;
            case MESSAGE_UPDATE_POWER_SAVER:
                refreshPowerSaverMode();
                break;
            }
        }
    };

    private class PowerSaverAction extends ToggleAction {
        private Switch mSwitchWidget;

        public PowerSaverAction() {
            super(com.android.internal.R.drawable.ic_qs_battery_saver,
                    com.android.internal.R.drawable.ic_qs_battery_saver,
                    R.string.global_action_toggle_battery_saver,
                    R.string.global_action_battery_saver_off_status,
                    R.string.global_action_battery_saver_on_status);
        }

        @Override
        public void onToggle(boolean on) {
            if (!mPowerManager.setPowerSaveMode(on)) {
                Log.e(TAG, "Setting power save mode to " + on + " failed");
            }
            mContext.startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return false;
        }

        @Override
        public View create(Context context, View convertView, ViewGroup parent,
                LayoutInflater inflater) {
            View v = super.create(context, convertView, parent, inflater);

            int switchPreferenceStyleResId = 0;
            TypedArray themeAttributes = context.obtainStyledAttributes(
                    new int[] { R.attr.switchPreferenceStyle });
            try {
                switchPreferenceStyleResId = themeAttributes.getResourceId(0, 0);
            } finally {
                themeAttributes.recycle();
            }

            int widgetlayoutResId = 0;
            if (switchPreferenceStyleResId != 0) {
                TypedArray preferenceAttributes =
                        context.obtainStyledAttributes(
                                switchPreferenceStyleResId,
                                android.R.styleable.Preference);
                try {
                    widgetlayoutResId = preferenceAttributes.getResourceId(
                            R.styleable.Preference_widgetLayout, 0);
                } finally {
                    preferenceAttributes.recycle();
                }
            }

            if (widgetlayoutResId != 0) {
                v.findViewById(R.id.icon).setVisibility(View.GONE);
                ViewGroup frame = v.findViewById(R.id.widget_frame);
                frame.setVisibility(View.VISIBLE);
                mSwitchWidget = (Switch) inflater.inflate(widgetlayoutResId, frame, false);
                mSwitchWidget.setDuplicateParentStateEnabled(true);
                updateState(mState);
                frame.addView(mSwitchWidget);
            }

            return v;
        }

        @Override
        public void updateState(State state) {
            super.updateState(state);
            if (mSwitchWidget != null) {
                mSwitchWidget.setChecked((state == State.On) || (state == State.TurningOn));
            }
        }
    }

    private class SettingsAction extends SinglePressAction {
        public SettingsAction() {
            super(R.drawable.ic_settings, R.string.global_action_settings);
        }

        @Override
        public void onPress() {
            Intent intent = new Intent(Settings.ACTION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mContext.startActivity(intent);
        }

        @Override
        public boolean showDuringKeyguard() {
            return false;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }
    }
}
