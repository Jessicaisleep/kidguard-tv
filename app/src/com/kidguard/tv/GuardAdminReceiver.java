package com.kidguard.tv;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/** 设备管理员回调。 */
public class GuardAdminReceiver extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context c, Intent i) {
        Log.bind(c);
        Log.i("device admin enabled");
        Admin.applyAll(c);
        GuardService.start(c);
    }

    @Override
    public CharSequence onDisableRequested(Context c, Intent i) {
        return "关闭后将无法限制电视使用时长，孩子可以随意观看。确定要关闭吗？";
    }

    @Override
    public void onDisabled(Context c, Intent i) {
        Log.bind(c);
        Log.w("device admin disabled");
    }

    @Override
    public void onReceive(Context c, Intent i) {
        try {
            super.onReceive(c, i);
        } catch (Throwable t) {
            Log.e("GuardAdminReceiver", t);
        }
    }
}
