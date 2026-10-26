package com.tianxiaodudou.clipboardpolice.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/** 动态注册（onResume 注册 / onPause 注销）：Hook 节流后发出的刷新通知。 */
public class ClipUpdateReceiver extends BroadcastReceiver {

    public static final String ACTION_UPDATED = "com.tianxiaodudou.clipboardpolice.CLIP_UPDATED";

    private final Runnable onUpdate;

    public ClipUpdateReceiver(Runnable onUpdate) {
        this.onUpdate = onUpdate;
    }

    public static IntentFilter intentFilter() {
        return new IntentFilter(ACTION_UPDATED);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || onUpdate == null) return;
        if (ACTION_UPDATED.equals(intent.getAction())) {
            onUpdate.run();
        }
    }
}
