package cn.folotoy.passportnotify;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class AutoConnectReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction()) &&
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) !=
                        BluetoothAdapter.STATE_ON) {
            return;
        }
        BridgeService.requestAutomaticStart(context);
    }
}
