package cn.folotoy.passportnotify;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.UUID;

public class BridgeService extends Service {
    public static final String START = "cn.folotoy.passportnotify.START";
    public static final String SEND = "cn.folotoy.passportnotify.SEND";
    private static final UUID SERVICE = UUID.fromString("4a17d400-34ad-4d7b-93f8-84237aacc001");
    private static final UUID WRITE = UUID.fromString("4a17d400-34ad-4d7b-93f8-84237aacc002");
    private static final String CHANNEL = "bridge";
    private static volatile BridgeService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Queue<byte[]> packets = new ArrayDeque<>();
    private BluetoothLeScanner scanner;
    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic characteristic;
    private byte[] current;
    private int offset;
    private int mtu = 23;
    private boolean scanning, writing, mtuPending, connectedGreetingSent;

    public static boolean publish(int type, String text) {
        BridgeService service = instance;
        if (service == null || service.gatt == null || service.characteristic == null) return false;
        service.handler.post(() -> service.enqueue(type, text));
        return true;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "设备连接", NotificationManager.IMPORTANCE_LOW));
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(bondReceiver, filter);
    }
    private Notification status(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("随身消息通知桥").setContentText(text).setContentIntent(pending)
                .setOngoing(true).build();
    }
    private void show(String text) {
        getSystemService(NotificationManager.class).notify(41, status(text));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && SEND.equals(intent.getAction())) {
            enqueue(intent.getIntExtra("type", 2), intent.getStringExtra("text"));
            return START_STICKY;
        }
        if (Build.VERSION.SDK_INT >= 29) startForeground(41, status("正在扫描 PassportNotify"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(41, status("正在扫描 PassportNotify"));
        scan();
        return START_STICKY;
    }
    private boolean permitted() {
        return Build.VERSION.SDK_INT < 31 ? checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                : (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED);
    }
    private void scan() {
        if (scanning || gatt != null || !permitted()) return;
        try {
            BluetoothManager manager = getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) return;
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) return;
            scanning = true;
            scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
            handler.postDelayed(() -> { if (scanning) { stopScan(); handler.postDelayed(this::scan, 4000); } }, 12000);
        } catch (SecurityException ignored) { scanning = false; show("请授权蓝牙权限"); }
    }
    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        try { scanner.stopScan(scanCallback); } catch (SecurityException ignored) { }
    }
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            android.bluetooth.le.ScanRecord record = result.getScanRecord();
            if (record == null || !"PassportNotify".equals(record.getDeviceName())) return;
            handler.post(() -> {
                if (gatt != null) return;
                stopScan();
                try { gatt = result.getDevice().connectGatt(BridgeService.this, false, callback, BluetoothDevice.TRANSPORT_LE); }
                catch (SecurityException ignored) { show("连接失败：蓝牙权限"); }
            });
        }
    };
    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            BluetoothDevice device = Build.VERSION.SDK_INT >= 33 ?
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class) :
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (gatt == null || device == null || !device.getAddress().equals(gatt.getDevice().getAddress())) return;
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
            if (state == BluetoothDevice.BOND_BONDED) handler.post(() -> {
                if (gatt == null || !permitted()) return;
                try { gatt.discoverServices(); } catch (SecurityException ignored) { show("缺少蓝牙连接权限"); }
            });
            if (state == BluetoothDevice.BOND_NONE) show("配对未完成，请重新连接并输入屏幕配对码");
        }
    };
    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt device, int status, int state) {
            handler.post(() -> {
                if (gatt != device) return;
                if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                    show("蓝牙已连接，正在配对");
                    try {
                        if (device.getDevice().getBondState() == BluetoothDevice.BOND_BONDED) device.discoverServices();
                        else device.getDevice().createBond();
                    } catch (SecurityException ignored) { show("配对失败：蓝牙权限"); }
                } else {
                    characteristic = null; current = null; packets.clear(); writing = false; mtuPending = false;
                    connectedGreetingSent = false; mtu = 23;
                    gatt = null;
                    try { device.close(); } catch (SecurityException ignored) { }
                    show("连接中断，正在重试");
                    handler.postDelayed(BridgeService.this::scan, 2500);
                }
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt device, int status) {
            handler.post(() -> {
                if (device != gatt || status != BluetoothGatt.GATT_SUCCESS) return;
                BluetoothGattService service = device.getService(SERVICE);
                characteristic = service == null ? null : service.getCharacteristic(WRITE);
                if (characteristic == null) { show("固件不匹配：缺少通知服务"); return; }
                show("已连接，等待通知");
                if (!permitted()) { show("缺少蓝牙连接权限"); return; }
                try { mtuPending = device.requestMtu(185); }
                catch (SecurityException ignored) { show("缺少蓝牙连接权限"); return; }
                if (!mtuPending) greet();
                else handler.postDelayed(() -> {
                    if (device == gatt && mtuPending) { mtuPending = false; greet(); writeNext(); }
                }, 4000);
            });
        }
        @Override public void onMtuChanged(BluetoothGatt device, int value, int status) {
            handler.post(() -> {
                if (device != gatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = value;
                mtuPending = false; greet(); writeNext();
            });
        }
        @Override public void onCharacteristicWrite(BluetoothGatt device, BluetoothGattCharacteristic chr, int status) {
            handler.post(() -> {
                if (device != gatt) return;
                writing = false;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    current = null; offset = 0; show("发送失败，请检查配对状态");
                }
                writeNext();
            });
        }
    };
    private void greet() {
        if (!connectedGreetingSent) { connectedGreetingSent = true; enqueue(3, "手机已连接 · 随身消息通知桥"); }
    }
    private static byte[] utf8Limited(String input) {
        if (input == null) return new byte[0];
        StringBuilder builder = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < input.length();) {
            int cp = input.codePointAt(i); i += Character.charCount(cp);
            byte[] symbol = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            if (bytes + symbol.length > 160) break;
            builder.appendCodePoint(cp); bytes += symbol.length;
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }
    private void enqueue(int type, String text) {
        if (characteristic == null || gatt == null) return; // Never queue sensitive notifications while offline.
        byte[] body = utf8Limited(text);
        if (body.length == 0 || type < 1 || type > 3) return;
        byte[] packet = new byte[4 + body.length];
        packet[0] = (byte)0xa5; packet[1] = (byte)type;
        packet[2] = (byte)body.length; packet[3] = (byte)(body.length >> 8);
        System.arraycopy(body, 0, packet, 4, body.length);
        if (packets.size() >= 8) packets.poll();
        packets.add(packet);
        writeNext();
    }
    private void writeNext() {
        if (writing || mtuPending || characteristic == null || gatt == null) return;
        if (current == null) { current = packets.poll(); offset = 0; }
        if (current == null) return;
        int end = Math.min(offset + mtu - 3, current.length);
        byte[] piece = Arrays.copyOfRange(current, offset, end);
        writing = true;
        int result;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                result = gatt.writeCharacteristic(characteristic, piece, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else {
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                characteristic.setValue(piece);
                result = gatt.writeCharacteristic(characteristic) ? BluetoothGatt.GATT_SUCCESS : -1;
            }
        } catch (SecurityException ignored) {
            writing = false; current = null; packets.clear(); show("缺少蓝牙连接权限"); return;
        }
        if (result != BluetoothGatt.GATT_SUCCESS) { writing = false; current = null; show("发送失败"); return; }
        offset = end;
        if (offset == current.length) current = null;
    }
    @Override public void onDestroy() {
        instance = null;
        stopScan();
        handler.removeCallbacksAndMessages(null);
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (SecurityException ignored) { }
            gatt = null;
        }
        unregisterReceiver(bondReceiver);
        packets.clear();
        super.onDestroy();
    }
}
