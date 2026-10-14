package com.kidguard.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启：拉起管控服务；若已超时则直接进锁屏。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent i) {
        try {
            Log.bind(c);
            Prefs.ensureDefaults(c);
            String act = i == null ? "" : String.valueOf(i.getAction());
            Log.i("boot receive: " + act);
            GuardService.start(c);
            Admin.applyAll(c);
            if (Prefs.isLocked(c)) {
                Intent g = new Intent(c, GuardActivity.class);
                g.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                g.putExtra("lock", true);
                c.startActivity(g);
            }
        } catch (Throwable t) {
            Log.e("BootReceiver fail", t);
        }
    }
}
