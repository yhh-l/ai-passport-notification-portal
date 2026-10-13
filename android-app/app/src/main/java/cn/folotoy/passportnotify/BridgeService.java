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
import android.os.PowerManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.UUID;

public class BridgeService extends Service {
    public static final String START = "cn.folotoy.passportnotify.START";
    public static final String STOP = "cn.folotoy.passportnotify.STOP";
    public static final String SEND = "cn.folotoy.passportnotify.SEND";
    public static final String ACTION_STATUS = "cn.folotoy.passportnotify.STATUS";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_CONNECTED = "connected";
    public static final String EXTRA_FIRMWARE_SUPPORTED = "firmware_supported";
    public static final String EXTRA_TRANSFER_ACTIVE = "transfer_active";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_SLOT_PRESENT = "slot_present";
    public static final String EXTRA_SLOT_NAME = "slot_name";

    private static final UUID SERVICE = UUID.fromString("4a17d400-34ad-4d7b-93f8-84237aacc001");
    private static final UUID WRITE = UUID.fromString("4a17d400-34ad-4d7b-93f8-84237aacc002");
    private static final UUID FIRMWARE_CONTROL = UUID.fromString("4a17d400-34ad-4d7b-93f8-84237aacc010");
    private static final UUID FIRMWARE_DATA = UUID.fromString("4a17d400-34ad-4d7b-93f8-84237aacc011");
    private static final UUID FIRMWARE_STATUS = UUID.fromString("4a17d400-34ad-4d7b-93f8-84237aacc012");
    private static final String CHANNEL = "bridge";
    private static final String CONNECTION_PREFS = "bridge_connection";
    private static final String AUTO_CONNECT = "auto_connect";
    private static final int NOTIFICATION_ID = 41;
    private static final int OP_NONE = 0;
    private static final int OP_NOTIFICATION = 1;
    private static final int OP_FIRMWARE_START = 2;
    private static final int OP_FIRMWARE_DATA = 3;
    private static final int OP_FIRMWARE_FINISH = 4;
    private static final int OP_FIRMWARE_CLEAR = 5;
    private static final int OP_STATUS_READ = 6;
    private static final int OP_FIRMWARE_SELECT = 7;
    private static final int PHASE_PREPARE = 0;
    private static final int PHASE_START = 1;
    private static final int PHASE_DATA = 2;
    private static final int PHASE_FINISH = 3;
    private static final int PHASE_VERIFY = 4;

    private static volatile BridgeService instance;
    private static volatile String currentStatus = "服务未启动";
    private static volatile boolean currentConnected;
    private static volatile boolean currentFirmwareSupported;
    private static volatile boolean currentTransferActive;
    private static volatile int currentProgress;
    private static volatile boolean currentSlotPresent;
    private static volatile String currentSlotName = "未读取";
    private static volatile DeviceSlot[] currentSlots = new DeviceSlot[0];
    private static volatile int currentSelectedSlot = -1;
    private static volatile long currentTotalFree;
    private static volatile long currentLargestFreeSlot;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Queue<byte[]> packets = new ArrayDeque<>();
    private BluetoothLeScanner scanner;
    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic notificationCharacteristic;
    private volatile BluetoothGattCharacteristic firmwareControlCharacteristic;
    private volatile BluetoothGattCharacteristic firmwareDataCharacteristic;
    private volatile BluetoothGattCharacteristic firmwareStatusCharacteristic;
    private byte[] currentPacket;
    private int packetOffset;
    private int mtu = 23;
    private boolean scanning;
    private boolean gattBusy;
    private boolean mtuPending;
    private boolean connectedGreetingSent;
    private boolean gattCacheRefreshAttempted;
    private int pendingOperation = OP_NONE;
    private int pendingLength;
    private boolean statusReadPending;
    private int clearSlotPending = -1;
    private int selectSlotPending = -1;

    private boolean firmwareActive;
    private int firmwarePhase;
    private FileInputStream firmwareStream;
    private long firmwareSize;
    private long firmwareSent;
    private String firmwareName;
    private int firmwareTargetSlot = -1;
    private byte[] firmwareChunk;
    private byte[] firmwareStartPacket;
    private PowerManager.WakeLock firmwareWakeLock;

    public static boolean publish(int type, String text) {
        BridgeService service = instance;
        if (service == null || !currentConnected || currentTransferActive) return false;
        service.handler.post(() -> service.enqueue(type, text));
        return true;
    }

    public static boolean installFirmware(File file, String displayName, int slot) {
        BridgeService service = instance;
        if (service == null || !currentConnected || !currentFirmwareSupported ||
                currentTransferActive || file == null || !file.isFile()) return false;
        service.handler.post(() -> service.beginFirmwareInstall(file, displayName, slot));
        return true;
    }

    public static boolean clearDeviceFirmware(int slot) {
        BridgeService service = instance;
        if (service == null || !currentConnected || !currentFirmwareSupported || currentTransferActive) {
            return false;
        }
        service.handler.post(() -> {
            service.clearSlotPending = slot;
            service.pump();
        });
        return true;
    }

    public static boolean selectDeviceFirmware(int slot) {
        BridgeService service = instance;
        if (service == null || !currentConnected || !currentFirmwareSupported || currentTransferActive) {
            return false;
        }
        service.handler.post(() -> {
            service.selectSlotPending = slot;
            service.pump();
        });
        return true;
    }

    public static String statusText() { return currentStatus; }
    public static boolean isConnected() { return currentConnected; }
    public static boolean isFirmwareSupported() { return currentFirmwareSupported; }
    public static boolean isTransferActive() { return currentTransferActive; }
    public static int transferProgress() { return currentProgress; }
    public static boolean isSlotPresent() { return currentSlotPresent; }
    public static String slotName() { return currentSlotName; }
    public static int selectedSlot() { return currentSelectedSlot; }
    public static long totalFree() { return currentTotalFree; }
    public static long largestFreeSlot() { return currentLargestFreeSlot; }
    public static DeviceSlot[] slots() { return Arrays.copyOf(currentSlots, currentSlots.length); }

    public static int findInstallSlot(long imageSize) {
        int best = -1;
        long bestCapacity = Long.MAX_VALUE;
        for (DeviceSlot slot : currentSlots) {
            if (!slot.available || slot.present || imageSize > slot.capacity) continue;
            if (slot.capacity < bestCapacity) {
                best = slot.index;
                bestCapacity = slot.capacity;
            }
        }
        return best;
    }

    public static final class DeviceSlot {
        public final int index;
        public final boolean available;
        public final boolean present;
        public final long capacity;
        public final long size;
        public final String name;

        DeviceSlot(int index, boolean available, boolean present,
                   long capacity, long size, String name) {
            this.index = index;
            this.available = available;
            this.present = present;
            this.capacity = capacity;
            this.size = size;
            this.name = name;
        }
    }

    public static boolean isAutoConnectEnabled(Context context) {
        return context.getSharedPreferences(CONNECTION_PREFS, MODE_PRIVATE)
                .getBoolean(AUTO_CONNECT, true);
    }

    public static void setAutoConnectEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(CONNECTION_PREFS, MODE_PRIVATE).edit()
                .putBoolean(AUTO_CONNECT, enabled).apply();
    }

    public static boolean requestAutomaticStart(Context context) {
        Context app = context.getApplicationContext();
        if (!isAutoConnectEnabled(app)) return false;
        if (Build.VERSION.SDK_INT >= 31 &&
                (app.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                 app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
            return false;
        }
        if (Build.VERSION.SDK_INT < 31 &&
                app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        BluetoothManager manager = app.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        try {
            if (adapter == null || !adapter.isEnabled()) return false;
            Intent start = new Intent(app, BridgeService.class).setAction(START);
            app.startForegroundService(start);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "AI Passport 连接", NotificationManager.IMPORTANCE_LOW));
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(bondReceiver, filter);
        }
        setStatus("准备连接设备");
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("AI Passport 蓝牙安装器")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void setStatus(String text) {
        currentStatus = text;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, notification(text));
        emitStatus();
    }

    private void emitStatus() {
        Intent status = new Intent(ACTION_STATUS).setPackage(getPackageName());
        status.putExtra(EXTRA_STATUS, currentStatus);
        status.putExtra(EXTRA_CONNECTED, currentConnected);
        status.putExtra(EXTRA_FIRMWARE_SUPPORTED, currentFirmwareSupported);
        status.putExtra(EXTRA_TRANSFER_ACTIVE, currentTransferActive);
        status.putExtra(EXTRA_PROGRESS, currentProgress);
        status.putExtra(EXTRA_SLOT_PRESENT, currentSlotPresent);
        status.putExtra(EXTRA_SLOT_NAME, currentSlotName);
        sendBroadcast(status);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && SEND.equals(intent.getAction())) {
            enqueue(intent.getIntExtra("type", 2), intent.getStringExtra("text"));
            return START_STICKY;
        }
        String foregroundText = currentConnected ? currentStatus : "正在扫描 PassportNotify";
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification(foregroundText),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification(foregroundText));
        }
        scan();
        return START_STICKY;
    }

    private boolean permitted() {
        return Build.VERSION.SDK_INT < 31 ?
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED :
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void scan() {
        if (scanning || gatt != null) return;
        if (!permitted()) {
            setStatus("请先授权附近设备权限");
            return;
        }
        try {
            BluetoothManager manager = getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                setStatus("手机蓝牙未开启");
                return;
            }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                setStatus("无法启动蓝牙扫描");
                return;
            }
            scanning = true;
            setStatus("正在查找 PassportNotify");
            scanner.startScan(null, new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
            handler.postDelayed(() -> {
                if (scanning) {
                    stopScan();
                    setStatus("暂未发现设备，正在重试");
                    handler.postDelayed(this::scan, 3000);
                }
            }, 12000);
        } catch (SecurityException ignored) {
            scanning = false;
            setStatus("请授权附近设备权限");
        }
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        try {
            if (scanner != null) scanner.stopScan(scanCallback);
        } catch (SecurityException ignored) { }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            android.bluetooth.le.ScanRecord record = result.getScanRecord();
            if (record == null || !"PassportNotify".equals(record.getDeviceName())) return;
            handler.post(() -> {
                if (gatt != null) return;
                stopScan();
                setStatus("已发现设备，正在连接");
                try {
                    gattCacheRefreshAttempted = false;
                    gatt = result.getDevice().connectGatt(BridgeService.this, false,
                            callback, BluetoothDevice.TRANSPORT_LE);
                } catch (SecurityException ignored) {
                    setStatus("连接失败：缺少蓝牙权限");
                }
            });
        }

        @Override public void onScanFailed(int errorCode) {
            handler.post(() -> {
                scanning = false;
                setStatus("蓝牙扫描失败（" + errorCode + "）");
                handler.postDelayed(BridgeService.this::scan, 4000);
            });
        }
    };

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int adapterState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR);
                if (adapterState == BluetoothAdapter.STATE_ON) {
                    handler.post(BridgeService.this::scan);
                } else if (adapterState == BluetoothAdapter.STATE_OFF) {
                    handler.post(() -> {
                        stopScan();
                        closeGatt();
                        setStatus("手机蓝牙未开启");
                    });
                }
                return;
            }
            BluetoothDevice device = Build.VERSION.SDK_INT >= 33 ?
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class) :
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (gatt == null || device == null ||
                    !device.getAddress().equals(gatt.getDevice().getAddress())) return;
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
            if (state == BluetoothDevice.BOND_BONDED) handler.post(() -> {
                if (gatt == null || !permitted()) return;
                setStatus("安全配对完成，正在读取服务");
                try {
                    gatt.discoverServices();
                } catch (SecurityException ignored) {
                    setStatus("缺少蓝牙连接权限");
                }
            });
            if (state == BluetoothDevice.BOND_NONE) {
                setStatus("配对未完成，请重连并输入设备配对码");
            }
        }
    };

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt device, int status, int state) {
            handler.post(() -> {
                if (gatt != device) return;
                if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                    setStatus("蓝牙已连接，正在安全配对");
                    try {
                        if (device.getDevice().getBondState() == BluetoothDevice.BOND_BONDED) {
                            device.discoverServices();
                        } else {
                            device.getDevice().createBond();
                        }
                    } catch (SecurityException ignored) {
                        setStatus("配对失败：缺少蓝牙权限");
                    }
                } else {
                    handleDisconnect(device);
                }
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt device, int status) {
            handler.post(() -> {
                if (device != gatt || status != BluetoothGatt.GATT_SUCCESS) return;
                BluetoothGattService service = device.getService(SERVICE);
                notificationCharacteristic = service == null ? null : service.getCharacteristic(WRITE);
                firmwareControlCharacteristic = service == null ? null : service.getCharacteristic(FIRMWARE_CONTROL);
                firmwareDataCharacteristic = service == null ? null : service.getCharacteristic(FIRMWARE_DATA);
                firmwareStatusCharacteristic = service == null ? null : service.getCharacteristic(FIRMWARE_STATUS);
                currentConnected = notificationCharacteristic != null;
                currentFirmwareSupported = firmwareControlCharacteristic != null &&
                        firmwareDataCharacteristic != null && firmwareStatusCharacteristic != null;
                if (currentConnected && !currentFirmwareSupported && !gattCacheRefreshAttempted) {
                    gattCacheRefreshAttempted = true;
                    if (refreshGattCache(device)) {
                        notificationCharacteristic = null;
                        firmwareControlCharacteristic = null;
                        firmwareDataCharacteristic = null;
                        firmwareStatusCharacteristic = null;
                        currentConnected = false;
                        setStatus("检测到旧蓝牙缓存，正在刷新设备能力");
                        handler.postDelayed(() -> discoverServices(device), 800);
                        return;
                    }
                }
                if (!currentConnected) {
                    setStatus("固件不匹配：缺少通知服务");
                    return;
                }
                setStatus(currentFirmwareSupported ? "已连接 · 门户功能可用" :
                        "已连接 · 请先升级设备门户固件");
                if (!permitted()) {
                    setStatus("缺少蓝牙连接权限");
                    return;
                }
                try {
                    mtuPending = device.requestMtu(247);
                } catch (SecurityException ignored) {
                    setStatus("缺少蓝牙连接权限");
                    return;
                }
                if (!mtuPending) connectionReady();
                else handler.postDelayed(() -> {
                    if (device == gatt && mtuPending) {
                        mtuPending = false;
                        connectionReady();
                    }
                }, 4000);
            });
        }

        @Override public void onMtuChanged(BluetoothGatt device, int value, int status) {
            handler.post(() -> {
                if (device != gatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = value;
                mtuPending = false;
                connectionReady();
            });
        }

        @Override public void onCharacteristicWrite(BluetoothGatt device,
                                                     BluetoothGattCharacteristic characteristic,
                                                     int status) {
            handler.post(() -> handleWriteResult(device, status));
        }

        @Override public void onCharacteristicRead(BluetoothGatt device,
                                                    BluetoothGattCharacteristic characteristic,
                                                    int status) {
            byte[] value = characteristic.getValue();
            handler.post(() -> handleStatusRead(device, value, status));
        }

        @Override public void onCharacteristicRead(BluetoothGatt device,
                                                    BluetoothGattCharacteristic characteristic,
                                                    byte[] value, int status) {
            byte[] copy = value == null ? null : Arrays.copyOf(value, value.length);
            handler.post(() -> handleStatusRead(device, copy, status));
        }
    };

    private boolean refreshGattCache(BluetoothGatt device) {
        try {
            Method refresh = device.getClass().getMethod("refresh");
            Object result = refresh.invoke(device);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private void discoverServices(BluetoothGatt device) {
        if (device != gatt || !permitted()) return;
        try {
            if (!device.discoverServices()) {
                setStatus("读取设备能力失败，请重新连接");
            }
        } catch (SecurityException ignored) {
            setStatus("缺少蓝牙连接权限");
        }
    }

    private void connectionReady() {
        connectedGreetingSent = true;
        if (currentFirmwareSupported) statusReadPending = true;
        pump();
    }

    private void handleDisconnect(BluetoothGatt device) {
        boolean interrupted = firmwareActive;
        closeFirmwareStream();
        releaseFirmwareWakeLock();
        firmwareActive = false;
        currentTransferActive = false;
        currentProgress = 0;
        currentConnected = false;
        currentFirmwareSupported = false;
        currentSlots = new DeviceSlot[0];
        currentSelectedSlot = -1;
        currentTotalFree = 0;
        currentLargestFreeSlot = 0;
        notificationCharacteristic = null;
        firmwareControlCharacteristic = null;
        firmwareDataCharacteristic = null;
        firmwareStatusCharacteristic = null;
        currentPacket = null;
        packets.clear();
        gattBusy = false;
        pendingOperation = OP_NONE;
        mtuPending = false;
        connectedGreetingSent = false;
        statusReadPending = false;
        clearSlotPending = -1;
        selectSlotPending = -1;
        mtu = 23;
        gatt = null;
        try { device.close(); } catch (SecurityException ignored) { }
        setStatus(interrupted ? "固件传输中断，正在重新连接" : "连接中断，正在重试");
        handler.postDelayed(this::scan, 2500);
    }

    private static byte[] utf8Limited(String input, int limit) {
        if (input == null) return new byte[0];
        StringBuilder builder = new StringBuilder();
        int bytes = 0;
        for (int index = 0; index < input.length();) {
            int codePoint = input.codePointAt(index);
            index += Character.charCount(codePoint);
            byte[] symbol = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
            if (bytes + symbol.length > limit) break;
            builder.appendCodePoint(codePoint);
            bytes += symbol.length;
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void enqueue(int type, String text) {
        if (notificationCharacteristic == null || gatt == null || firmwareActive) return;
        byte[] body = utf8Limited(text, 160);
        if (body.length == 0 || type < 1 || type > 3) return;
        byte[] packet = new byte[4 + body.length];
        packet[0] = (byte)0xa5;
        packet[1] = (byte)type;
        packet[2] = (byte)body.length;
        packet[3] = (byte)(body.length >> 8);
        System.arraycopy(body, 0, packet, 4, body.length);
        if (packets.size() >= 8) packets.poll();
        packets.add(packet);
        pump();
    }

    private void beginFirmwareInstall(File file, String displayName, int slot) {
        if (firmwareActive || !currentFirmwareSupported || gatt == null) return;
        long size = file.length();
        DeviceSlot target = null;
        for (DeviceSlot candidate : currentSlots) {
            if (candidate.index == slot) target = candidate;
        }
        if (size < 176 || target == null || !target.available || size > target.capacity) {
            setStatus("固件大小超过所选设备槽容量");
            return;
        }
        try {
            firmwareStream = new FileInputStream(file);
        } catch (IOException error) {
            setStatus("无法读取固件文件");
            return;
        }
        byte[] name = utf8Limited(displayName, 47);
        firmwareStartPacket = new byte[8 + name.length];
        firmwareStartPacket[0] = (byte)0xf0;
        firmwareStartPacket[1] = 0x01;
        firmwareStartPacket[2] = (byte)slot;
        int intSize = (int)size;
        firmwareStartPacket[3] = (byte)intSize;
        firmwareStartPacket[4] = (byte)(intSize >> 8);
        firmwareStartPacket[5] = (byte)(intSize >> 16);
        firmwareStartPacket[6] = (byte)(intSize >> 24);
        firmwareStartPacket[7] = (byte)name.length;
        System.arraycopy(name, 0, firmwareStartPacket, 8, name.length);
        firmwareName = new String(name, StandardCharsets.UTF_8);
        firmwareTargetSlot = slot;
        firmwareSize = size;
        firmwareSent = 0;
        firmwareChunk = null;
        firmwarePhase = PHASE_PREPARE;
        acquireFirmwareWakeLock();
        firmwareActive = true;
        currentTransferActive = true;
        currentProgress = 0;
        setStatus("正在准备安装 " + firmwareName);
        pump();
    }

    private void pump() {
        if (gattBusy || mtuPending || gatt == null) return;

        if (firmwareActive) {
            if (firmwarePhase == PHASE_PREPARE) {
                // Finish the one notification packet already in flight so the
                // device notification parser is never left with a partial frame.
                if (currentPacket != null) {
                    sendNextNotificationChunk();
                    return;
                }
                packets.clear();
                firmwarePhase = PHASE_START;
            }
            if (firmwarePhase == PHASE_START) {
                issueWrite(firmwareControlCharacteristic, firmwareStartPacket, OP_FIRMWARE_START);
                return;
            }
            if (firmwarePhase == PHASE_DATA) {
                if (firmwareSent >= firmwareSize) {
                    firmwarePhase = PHASE_FINISH;
                    pump();
                    return;
                }
                if (firmwareChunk == null) {
                    int wanted = (int)Math.min(Math.max(20, mtu - 3), firmwareSize - firmwareSent);
                    byte[] buffer = new byte[wanted];
                    int count;
                    try {
                        count = firmwareStream.read(buffer);
                    } catch (IOException error) {
                        failFirmware("读取固件失败");
                        return;
                    }
                    if (count <= 0) {
                        failFirmware("固件文件提前结束");
                        return;
                    }
                    firmwareChunk = count == buffer.length ? buffer : Arrays.copyOf(buffer, count);
                }
                issueWrite(firmwareDataCharacteristic, firmwareChunk, OP_FIRMWARE_DATA);
                return;
            }
            if (firmwarePhase == PHASE_FINISH) {
                issueWrite(firmwareControlCharacteristic, new byte[]{(byte)0xf0, 0x02},
                        OP_FIRMWARE_FINISH);
                return;
            }
            if (firmwarePhase == PHASE_VERIFY) {
                statusReadPending = true;
            }
        }

        if (clearSlotPending >= 0) {
            int slot = clearSlotPending;
            clearSlotPending = -1;
            issueWrite(firmwareControlCharacteristic,
                    new byte[]{(byte)0xf0, 0x05, (byte)slot},
                    OP_FIRMWARE_CLEAR);
            return;
        }

        if (selectSlotPending >= 0) {
            int slot = selectSlotPending;
            selectSlotPending = -1;
            issueWrite(firmwareControlCharacteristic,
                    new byte[]{(byte)0xf0, 0x06, (byte)slot},
                    OP_FIRMWARE_SELECT);
            return;
        }

        if (!firmwareActive) {
            if (currentPacket == null) {
                currentPacket = packets.poll();
                packetOffset = 0;
            }
            if (currentPacket != null) {
                sendNextNotificationChunk();
                return;
            }
        }

        if (statusReadPending && firmwareStatusCharacteristic != null) {
            statusReadPending = false;
            issueStatusRead();
        }
    }

    private void sendNextNotificationChunk() {
        if (currentPacket == null) return;
        int end = Math.min(packetOffset + Math.max(20, mtu - 3), currentPacket.length);
        byte[] piece = Arrays.copyOfRange(currentPacket, packetOffset, end);
        issueWrite(notificationCharacteristic, piece, OP_NOTIFICATION);
    }

    private void issueWrite(BluetoothGattCharacteristic characteristic, byte[] value, int operation) {
        if (characteristic == null || gatt == null || value == null) {
            if (firmwareActive) failFirmware("设备固件传输服务不可用");
            return;
        }
        gattBusy = true;
        pendingOperation = operation;
        pendingLength = value.length;
        int result;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                result = gatt.writeCharacteristic(characteristic, value,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else {
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                characteristic.setValue(value);
                result = gatt.writeCharacteristic(characteristic) ? BluetoothGatt.GATT_SUCCESS : -1;
            }
        } catch (SecurityException ignored) {
            result = -1;
        }
        if (result != BluetoothGatt.GATT_SUCCESS) {
            gattBusy = false;
            pendingOperation = OP_NONE;
            if (firmwareActive || operation == OP_FIRMWARE_CLEAR ||
                    operation == OP_FIRMWARE_SELECT) {
                failFirmware("固件操作启动失败");
            } else {
                setStatus("消息发送失败，请检查配对状态");
            }
        }
    }

    private void issueStatusRead() {
        if (firmwareStatusCharacteristic == null || gatt == null) return;
        gattBusy = true;
        pendingOperation = OP_STATUS_READ;
        boolean started;
        try {
            started = gatt.readCharacteristic(firmwareStatusCharacteristic);
        } catch (SecurityException ignored) {
            started = false;
        }
        if (!started) {
            gattBusy = false;
            pendingOperation = OP_NONE;
            if (firmwareActive) failFirmware("无法确认设备安装结果");
        }
    }

    private void handleWriteResult(BluetoothGatt device, int status) {
        if (device != gatt) return;
        int operation = pendingOperation;
        int length = pendingLength;
        gattBusy = false;
        pendingOperation = OP_NONE;
        pendingLength = 0;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            if (firmwareActive || operation == OP_FIRMWARE_CLEAR ||
                    operation == OP_FIRMWARE_SELECT) {
                failFirmware("固件写入失败（" + status + "）");
            } else {
                currentPacket = null;
                packetOffset = 0;
                setStatus("消息发送失败，请检查配对状态");
                pump();
            }
            return;
        }

        if (operation == OP_NOTIFICATION) {
            packetOffset += length;
            if (packetOffset >= currentPacket.length) {
                currentPacket = null;
                packetOffset = 0;
            }
        } else if (operation == OP_FIRMWARE_START) {
            firmwarePhase = PHASE_DATA;
            setStatus("正在安装 " + firmwareName + " · 0%");
        } else if (operation == OP_FIRMWARE_DATA) {
            firmwareSent += length;
            firmwareChunk = null;
            int progress = (int)Math.min(99, firmwareSent * 100 / firmwareSize);
            if (progress != currentProgress) {
                currentProgress = progress;
                setStatus("正在安装 " + firmwareName + " · " + progress + "%");
            }
        } else if (operation == OP_FIRMWARE_FINISH) {
            closeFirmwareStream();
            firmwarePhase = PHASE_VERIFY;
            statusReadPending = true;
            setStatus("固件已写入，正在校验");
        } else if (operation == OP_FIRMWARE_CLEAR) {
            statusReadPending = true;
            setStatus("设备玩法槽已清空，正在刷新空间");
        } else if (operation == OP_FIRMWARE_SELECT) {
            statusReadPending = true;
            setStatus("已切换设备玩法选择");
        }
        pump();
    }

    private static long readUnsignedInt(byte[] value, int offset) {
        return (value[offset] & 0xffL) |
                ((value[offset + 1] & 0xffL) << 8) |
                ((value[offset + 2] & 0xffL) << 16) |
                ((value[offset + 3] & 0xffL) << 24);
    }

    private static String formatBytes(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.2f MiB", bytes / 1048576.0);
    }

    private void handleStatusRead(BluetoothGatt device, byte[] value, int status) {
        if (device != gatt || pendingOperation != OP_STATUS_READ) return;
        gattBusy = false;
        pendingOperation = OP_NONE;
        if (status != BluetoothGatt.GATT_SUCCESS || value == null || value.length < 13) {
            if (firmwareActive) failFirmware("设备未返回有效的安装状态");
            else {
                setStatus("已连接 · 无法读取设备玩法槽");
                pump();
            }
            return;
        }

        int state = value[1] & 0xff;
        int progress = value[2] & 0xff;
        if (value[0] == 2) {
            if (value.length < 18) {
                failFirmware("设备返回的多槽状态不完整");
                return;
            }
            currentSelectedSlot = value[3] & 0xff;
            if (currentSelectedSlot == 0xff) currentSelectedSlot = -1;
            int count = value[5] & 0xff;
            currentTotalFree = readUnsignedInt(value, 10);
            currentLargestFreeSlot = readUnsignedInt(value, 14);
            DeviceSlot[] slots = new DeviceSlot[count];
            int offset = 18;
            for (int item = 0; item < count; item++) {
                if (offset + 11 > value.length) {
                    failFirmware("设备返回的槽位状态不完整");
                    return;
                }
                int index = value[offset++] & 0xff;
                int flags = value[offset++] & 0xff;
                long capacity = readUnsignedInt(value, offset);
                offset += 4;
                long imageSize = readUnsignedInt(value, offset);
                offset += 4;
                int nameLength = value[offset++] & 0xff;
                if (offset + nameLength > value.length) {
                    failFirmware("设备返回的槽位名称不完整");
                    return;
                }
                String name = nameLength == 0 ? "空槽" :
                        new String(value, offset, nameLength, StandardCharsets.UTF_8);
                offset += nameLength;
                slots[item] = new DeviceSlot(index, (flags & 0x01) != 0,
                        (flags & 0x02) != 0, capacity, imageSize, name);
            }
            currentSlots = slots;
            DeviceSlot selected = null;
            for (DeviceSlot slot : slots) {
                if (slot.index == currentSelectedSlot) selected = slot;
            }
            currentSlotPresent = selected != null && selected.present;
            currentSlotName = currentSlotPresent ? selected.name : "未选择";
        } else if (value[0] == 1) {
            currentSlotPresent = value[3] != 0;
            int nameLength = Math.min(value[12] & 0xff, value.length - 13);
            currentSlotName = nameLength == 0 ? "自定义玩法槽" :
                    new String(value, 13, nameLength, StandardCharsets.UTF_8);
            currentSelectedSlot = currentSlotPresent ? 0 : -1;
            currentSlots = new DeviceSlot[]{new DeviceSlot(0, true, currentSlotPresent,
                    FirmwareRepository.LEGACY_SLOT_SIZE,
                    currentSlotPresent ? readUnsignedInt(value, 4) : 0,
                    currentSlotName)};
            currentTotalFree = currentSlotPresent ? 0 : FirmwareRepository.LEGACY_SLOT_SIZE;
            currentLargestFreeSlot = currentTotalFree;
        } else {
            if (firmwareActive) failFirmware("设备固件协议版本不受支持");
            else setStatus("已连接 · 设备固件协议版本不受支持");
            return;
        }
        currentProgress = progress;

        if (firmwareActive && firmwarePhase == PHASE_VERIFY) {
            boolean installed = false;
            for (DeviceSlot slot : currentSlots) {
                if (slot.index == firmwareTargetSlot && slot.present) installed = true;
            }
            if (state == 2 && installed) {
                firmwareActive = false;
                currentTransferActive = false;
                currentProgress = 100;
                firmwareChunk = null;
                firmwareStartPacket = null;
                firmwareTargetSlot = -1;
                firmwareSize = 0;
                firmwareSent = 0;
                firmwarePhase = PHASE_PREPARE;
                releaseFirmwareWakeLock();
                setStatus("安装完成 · 可在设备上选择并启动玩法");
            } else {
                failFirmware("设备校验固件失败");
                return;
            }
        } else {
            int installedCount = 0;
            for (DeviceSlot slot : currentSlots) if (slot.present) installedCount++;
            setStatus("已连接 · 已安装 " + installedCount + " 个玩法 · 剩余 " +
                    formatBytes(currentTotalFree));
        }
        emitStatus();
        pump();
    }

    private void failFirmware(String reason) {
        boolean transferHadStarted = firmwareActive;
        closeFirmwareStream();
        releaseFirmwareWakeLock();
        firmwareActive = false;
        currentTransferActive = false;
        currentProgress = 0;
        firmwareChunk = null;
        firmwareStartPacket = null;
        firmwareTargetSlot = -1;
        firmwarePhase = PHASE_PREPARE;
        gattBusy = false;
        pendingOperation = OP_NONE;
        setStatus(reason);
        if (transferHadStarted && gatt != null) {
            // A local read error or rejected ATT write can leave the device OTA
            // handle open. Disconnect so the portal aborts that partial image.
            try { gatt.disconnect(); } catch (SecurityException ignored) { }
            return;
        }
        pump();
    }

    private void acquireFirmwareWakeLock() {
        if (firmwareWakeLock == null) {
            PowerManager manager = getSystemService(PowerManager.class);
            if (manager != null) {
                firmwareWakeLock = manager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "PassportNotify:FirmwareTransfer");
                firmwareWakeLock.setReferenceCounted(false);
            }
        }
        if (firmwareWakeLock != null && !firmwareWakeLock.isHeld()) {
            firmwareWakeLock.acquire(30 * 60 * 1000L);
        }
    }

    private void releaseFirmwareWakeLock() {
        if (firmwareWakeLock != null && firmwareWakeLock.isHeld()) firmwareWakeLock.release();
    }

    private void closeFirmwareStream() {
        if (firmwareStream == null) return;
        try { firmwareStream.close(); } catch (IOException ignored) { }
        firmwareStream = null;
    }

    private void closeGatt() {
        BluetoothGatt activeGatt = gatt;
        gatt = null;
        notificationCharacteristic = null;
        firmwareControlCharacteristic = null;
        firmwareDataCharacteristic = null;
        firmwareStatusCharacteristic = null;
        currentConnected = false;
        currentFirmwareSupported = false;
        currentSlots = new DeviceSlot[0];
        currentSelectedSlot = -1;
        currentTotalFree = 0;
        currentLargestFreeSlot = 0;
        if (activeGatt == null) return;
        try {
            activeGatt.disconnect();
            activeGatt.close();
        } catch (SecurityException ignored) { }
    }

    @Override public void onDestroy() {
        instance = null;
        stopScan();
        handler.removeCallbacksAndMessages(null);
        closeFirmwareStream();
        releaseFirmwareWakeLock();
        closeGatt();
        try { unregisterReceiver(bondReceiver); } catch (IllegalArgumentException ignored) { }
        packets.clear();
        currentConnected = false;
        currentFirmwareSupported = false;
        currentTransferActive = false;
        currentProgress = 0;
        currentStatus = "服务已停止";
        emitStatus();
        super.onDestroy();
    }
}
