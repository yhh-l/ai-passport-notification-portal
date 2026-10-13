package cn.folotoy.passportnotify;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Activity;
import android.app.DownloadManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 101;
    private static final int REQUEST_STORAGE = 102;
    private static final int REQUEST_IMPORT = 103;
    private static final int COLOR_BACKGROUND = Color.rgb(8, 18, 16);
    private static final int COLOR_CARD = Color.rgb(20, 38, 34);
    private static final int COLOR_CARD_ALT = Color.rgb(29, 52, 47);
    private static final int COLOR_TEXT = Color.rgb(235, 250, 245);
    private static final int COLOR_MUTED = Color.rgb(157, 187, 178);
    private static final int COLOR_PRIMARY = Color.rgb(72, 213, 181);
    private static final int COLOR_WARNING = Color.rgb(244, 184, 96);

    private FirmwareRepository firmwareRepository;
    private OfficialFirmwareService officialFirmwareService;
    private TextView connectionStatus;
    private TextView connectionDetail;
    private TextView capacityStatus;
    private TextView officialStatus;
    private LinearLayout deviceSlotList;
    private LinearLayout officialList;
    private LinearLayout firmwareList;
    private ProgressBar transferProgress;
    private ProgressBar downloadProgress;
    private Button connectButton;
    private Button refreshButton;
    private boolean receiverRegistered;
    private List<OfficialFirmware> officialFirmwares = Collections.emptyList();
    private OfficialFirmware pendingStorageDownload;
    private String downloadingSlug;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refreshConnectionState();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(COLOR_BACKGROUND);
        getWindow().setNavigationBarColor(COLOR_BACKGROUND);
        firmwareRepository = new FirmwareRepository(this);
        officialFirmwareService = new OfficialFirmwareService(this);
        setContentView(buildContent());
        refreshConnectionState();
        refreshFirmwareList();
        applyOfficialCatalog(officialFirmwareService.loadCached(), false);
        startBridge(false);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(BridgeService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        receiverRegistered = true;
        refreshConnectionState();
        startBridge(false);
    }

    @Override protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BACKGROUND);
        LinearLayout root = vertical();
        root.setPadding(dp(18), dp(20), dp(18), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher_foreground);
        logo.setBackground(rounded(COLOR_CARD_ALT, 12));
        logo.setPadding(dp(8), dp(8), dp(8), dp(8));
        header.addView(logo, params(56, 56));
        LinearLayout brand = vertical();
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        brandParams.leftMargin = dp(14);
        header.addView(brand, brandParams);
        brand.addView(text("AI Passport 蓝牙安装器", 23, COLOR_TEXT, true));
        brand.addView(text("多固件上传 · 容量匹配 · 设备切换", 13, COLOR_MUTED, false));
        root.addView(header);

        root.addView(sectionTitle("设备连接"));
        LinearLayout connectionCard = card();
        connectionStatus = text("●  服务未启动", 17, COLOR_MUTED, true);
        connectionCard.addView(connectionStatus);
        connectionDetail = text("首次连接需要在手机和设备上确认配对码。",
                14, COLOR_MUTED, false);
        connectionDetail.setPadding(0, dp(8), 0, 0);
        connectionCard.addView(connectionDetail);
        transferProgress = progressBar();
        LinearLayout.LayoutParams transferParams = matchWrap();
        transferParams.topMargin = dp(10);
        connectionCard.addView(transferProgress, transferParams);
        connectButton = actionButton("连接设备", view -> startBridge(true));
        LinearLayout.LayoutParams connectParams = matchWrap();
        connectParams.topMargin = dp(12);
        connectionCard.addView(connectButton, connectParams);
        root.addView(connectionCard);

        root.addView(sectionTitle("设备固件槽"));
        LinearLayout slotsCard = card();
        capacityStatus = text("连接后读取剩余空间与单槽容量。", 14, COLOR_MUTED, false);
        slotsCard.addView(capacityStatus);
        deviceSlotList = vertical();
        deviceSlotList.setPadding(0, dp(8), 0, 0);
        slotsCard.addView(deviceSlotList);
        TextView slotHint = text("设备上按上/下切换槽位，长按 OK 启动；长按下后按 OK 清空。",
                12, COLOR_WARNING, false);
        slotHint.setPadding(0, dp(10), 0, 0);
        slotsCard.addView(slotHint);
        root.addView(slotsCard);

        root.addView(sectionTitle("手机固件库"));
        LinearLayout localCard = card();
        localCard.addView(text("可一次选择多个本地 BIN。安装前会比较固件大小和每个设备槽的容量。",
                14, COLOR_MUTED, false));
        LinearLayout localActions = horizontal();
        localActions.setPadding(0, dp(12), 0, 0);
        localActions.addView(actionButton("选择多个 BIN", view -> chooseLocalFirmware()),
                weightedButton(false));
        localActions.addView(outlineButton("打开下载目录", view -> openDownloads()),
                weightedButton(true));
        localCard.addView(localActions);
        firmwareList = vertical();
        firmwareList.setPadding(0, dp(10), 0, 0);
        localCard.addView(firmwareList);
        root.addView(localCard);

        root.addView(sectionTitle("FoloToy 官网固件"));
        LinearLayout officialCard = card();
        officialCard.addView(text("官网下载会校验记录大小和 SHA-256，再提取可安装的应用镜像。",
                14, COLOR_MUTED, false));
        refreshButton = actionButton("刷新官网列表", view -> refreshOfficialCatalog(true));
        LinearLayout.LayoutParams refreshParams = matchWrap();
        refreshParams.topMargin = dp(12);
        officialCard.addView(refreshButton, refreshParams);
        officialStatus = text("正在读取缓存…", 13, COLOR_MUTED, false);
        officialStatus.setPadding(0, dp(10), 0, 0);
        officialCard.addView(officialStatus);
        downloadProgress = progressBar();
        downloadProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams downloadParams = matchWrap();
        downloadParams.topMargin = dp(8);
        officialCard.addView(downloadProgress, downloadParams);
        officialList = vertical();
        officialList.setPadding(0, dp(8), 0, 0);
        officialCard.addView(officialList);
        root.addView(officialCard);
        return scroll;
    }

    private void startBridge(boolean interactive) {
        if (interactive) BridgeService.setAutoConnectEnabled(this, true);
        else if (!BridgeService.isAutoConnectEnabled(this)) return;
        if (!hasBluetoothPermissions() || !hasNotificationPermission()) {
            if (interactive) requestRequiredPermissions();
            return;
        }
        if (!isBluetoothEnabled()) {
            if (interactive) {
                try {
                    startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
                } catch (SecurityException error) {
                    requestRequiredPermissions();
                }
            }
            return;
        }
        if (!isLegacyLocationReady()) {
            if (interactive) startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        BridgeService.requestAutomaticStart(this);
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRequiredPermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    private boolean isBluetoothEnabled() {
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        try {
            return adapter != null && adapter.isEnabled();
        } catch (SecurityException ignored) {
            return false;
        }
    }

    private boolean isLegacyLocationReady() {
        if (Build.VERSION.SDK_INT >= 31) return true;
        try {
            return Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE) !=
                    Settings.Secure.LOCATION_MODE_OFF;
        } catch (Settings.SettingNotFoundException ignored) {
            return false;
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS && hasBluetoothPermissions()) {
            startBridge(false);
        } else if (requestCode == REQUEST_STORAGE) {
            OfficialFirmware pending = pendingStorageDownload;
            pendingStorageDownload = null;
            if (pending != null && grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                beginOfficialDownload(pending);
            }
        }
    }

    private void refreshConnectionState() {
        boolean connected = BridgeService.isConnected();
        boolean active = BridgeService.isTransferActive();
        connectionStatus.setText(connected ? "●  已连接" : "●  未连接");
        connectionStatus.setTextColor(connected ? COLOR_PRIMARY : COLOR_MUTED);
        connectionDetail.setText(BridgeService.statusText());
        transferProgress.setVisibility(active ? View.VISIBLE : View.GONE);
        transferProgress.setProgress(BridgeService.transferProgress());
        connectButton.setEnabled(!connected && !active);
        connectButton.setAlpha(connectButton.isEnabled() ? 1f : 0.5f);

        BridgeService.DeviceSlot[] slots = BridgeService.slots();
        if (!connected || !BridgeService.isFirmwareSupported()) {
            capacityStatus.setText("请连接多固件安装器后读取设备空间。最大支持的本地镜像为 " +
                    formatSize(FirmwareRepository.DEVICE_SLOT_SIZE) + "。");
        } else {
            capacityStatus.setText("空槽总容量 " + formatSize(BridgeService.totalFree()) +
                    " · 最大空槽 " + formatSize(BridgeService.largestFreeSlot()) +
                    "。固件必须完整放入一个槽，不能跨槽。 ");
        }
        refreshDeviceSlots(slots, active);
        refreshFirmwareButtons();
    }

    private void refreshDeviceSlots(BridgeService.DeviceSlot[] slots, boolean active) {
        deviceSlotList.removeAllViews();
        if (slots.length == 0) {
            deviceSlotList.addView(text("尚未读取设备槽位。", 13, COLOR_MUTED, false));
            return;
        }
        for (BridgeService.DeviceSlot slot : slots) {
            LinearLayout row = vertical();
            row.setPadding(0, dp(8), 0, dp(8));
            String selected = slot.index == BridgeService.selectedSlot() ? " · 当前选择" : "";
            row.addView(text("槽 " + (slot.index + 1) + selected, 15, COLOR_TEXT, true));
            String content = slot.present ? slot.name + " · " + formatSize(slot.size) : "空槽";
            row.addView(text(content + " / 容量 " + formatSize(slot.capacity),
                    13, slot.present ? COLOR_MUTED : COLOR_PRIMARY, false));
            if (slot.present) {
                LinearLayout actions = horizontal();
                actions.setPadding(0, dp(6), 0, 0);
                Button select = compactButton("设为启动选择", view -> {
                    if (!BridgeService.selectDeviceFirmware(slot.index)) toast("设备当前不可操作");
                });
                Button clear = compactButton("清空", view -> confirmClearSlot(slot));
                select.setEnabled(!active && slot.index != BridgeService.selectedSlot());
                clear.setEnabled(!active);
                actions.addView(select, weightedButton(false));
                actions.addView(clear, weightedButton(true));
                row.addView(actions);
            }
            deviceSlotList.addView(row);
        }
    }

    private void confirmClearSlot(BridgeService.DeviceSlot slot) {
        new AlertDialog.Builder(this)
                .setTitle("清空槽 " + (slot.index + 1) + "？")
                .setMessage("将移除设备上的“" + slot.name + "”，手机固件库中的副本不会删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> {
                    if (!BridgeService.clearDeviceFirmware(slot.index)) toast("设备当前不可操作");
                }).show();
    }

    private void chooseLocalFirmware() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int index = 0; index < clip.getItemCount(); index++) {
                uris.add(clip.getItemAt(index).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        new Thread(() -> importLocalFirmware(uris), "firmware-import").start();
    }

    private void importLocalFirmware(List<Uri> uris) {
        int imported = 0;
        String lastError = "";
        for (Uri uri : uris) {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("无法打开文件");
                firmwareRepository.importLocal(input, displayName(uri));
                imported++;
            } catch (IOException | RuntimeException error) {
                lastError = safeMessage(error);
            }
        }
        int resultCount = imported;
        String errorText = lastError;
        runOnUiThread(() -> {
            refreshFirmwareList();
            String message = "已导入 " + resultCount + " 个固件";
            if (!errorText.isEmpty()) message += "；部分文件失败：" + errorText;
            toast(message);
        });
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (RuntimeException ignored) { }
        return "local-firmware.bin";
    }

    private void refreshFirmwareList() {
        firmwareList.removeAllViews();
        List<FirmwareImage> images = firmwareRepository.list();
        if (images.isEmpty()) {
            firmwareList.addView(text("手机固件库为空。可多选本地 BIN，或从官网列表下载。",
                    13, COLOR_MUTED, false));
            return;
        }
        for (FirmwareImage image : images) firmwareList.addView(firmwareRow(image));
        refreshFirmwareButtons();
    }

    private View firmwareRow(FirmwareImage image) {
        LinearLayout row = vertical();
        row.setTag("firmware-row");
        row.setPadding(0, dp(9), 0, dp(9));
        row.addView(text(image.displayName(), 15, COLOR_TEXT, true));
        row.addView(text(image.version + " · " + formatSize(image.size) +
                " · SHA-256 " + image.sha256.substring(0, 12) + "…",
                12, COLOR_MUTED, false));
        LinearLayout actions = horizontal();
        actions.setPadding(0, dp(6), 0, 0);
        Button install = compactButton("选择槽位安装", view -> chooseInstallSlot(image));
        install.setTag("install-button");
        actions.addView(install, weightedButton(false));
        actions.addView(compactButton("移除", view -> confirmDelete(image)), weightedButton(true));
        row.addView(actions);
        return row;
    }

    private void chooseInstallSlot(FirmwareImage image) {
        if (!BridgeService.isConnected() || !BridgeService.isFirmwareSupported()) {
            toast("请先连接多固件安装器");
            return;
        }
        List<BridgeService.DeviceSlot> candidates = new ArrayList<>();
        for (BridgeService.DeviceSlot slot : BridgeService.slots()) {
            if (slot.available && image.size <= slot.capacity) candidates.add(slot);
        }
        if (candidates.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("没有可容纳的槽位")
                    .setMessage("固件大小 " + formatSize(image.size) +
                            "，设备最大单槽 " + formatSize(maxSlotCapacity()) +
                            "。总剩余空间不能替代单槽容量。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        String[] labels = new String[candidates.size()];
        for (int index = 0; index < candidates.size(); index++) {
            BridgeService.DeviceSlot slot = candidates.get(index);
            labels[index] = "槽 " + (slot.index + 1) + " · " +
                    (slot.present ? "替换 " + slot.name : "空") +
                    " · 容量 " + formatSize(slot.capacity);
        }
        new AlertDialog.Builder(this)
                .setTitle("安装 “" + image.displayName() + "”")
                .setItems(labels, (dialog, which) -> {
                    BridgeService.DeviceSlot slot = candidates.get(which);
                    if (slot.present) confirmReplace(image, slot);
                    else beginInstall(image, slot);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private long maxSlotCapacity() {
        long largest = 0;
        for (BridgeService.DeviceSlot slot : BridgeService.slots()) {
            if (slot.capacity > largest) largest = slot.capacity;
        }
        return largest;
    }

    private void confirmReplace(FirmwareImage image, BridgeService.DeviceSlot slot) {
        new AlertDialog.Builder(this)
                .setTitle("替换槽 " + (slot.index + 1) + "？")
                .setMessage("设备上的“" + slot.name + "”将被“" +
                        image.displayName() + "”替换。")
                .setNegativeButton("取消", null)
                .setPositiveButton("替换并安装", (dialog, which) -> beginInstall(image, slot))
                .show();
    }

    private void beginInstall(FirmwareImage image, BridgeService.DeviceSlot slot) {
        if (image.size > slot.capacity) {
            toast("固件大于槽位容量，已停止安装");
            return;
        }
        if (!BridgeService.installFirmware(image.file, image.displayName(), slot.index)) {
            toast("无法开始安装，请检查连接和槽位状态");
        }
    }

    private void confirmDelete(FirmwareImage image) {
        new AlertDialog.Builder(this)
                .setTitle("移出手机固件库？")
                .setMessage(image.displayName())
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (dialog, which) -> {
                    firmwareRepository.delete(image);
                    refreshFirmwareList();
                    refreshOfficialList();
                }).show();
    }

    private void refreshFirmwareButtons() {
        boolean enabled = BridgeService.isConnected() && BridgeService.isFirmwareSupported() &&
                !BridgeService.isTransferActive();
        for (int index = 0; index < firmwareList.getChildCount(); index++) {
            View install = firmwareList.getChildAt(index).findViewWithTag("install-button");
            if (install != null) {
                install.setEnabled(enabled);
                install.setAlpha(enabled ? 1f : 0.45f);
            }
        }
    }

    private void refreshOfficialCatalog(boolean userInitiated) {
        if (downloadingSlug != null) return;
        refreshButton.setEnabled(false);
        officialStatus.setText("正在从 FoloToy 官网同步固件目录…");
        new Thread(() -> {
            try {
                OfficialFirmwareService.CatalogResult result = officialFirmwareService.refreshCatalog();
                runOnUiThread(() -> applyOfficialCatalog(result, true));
            } catch (IOException error) {
                runOnUiThread(() -> {
                    refreshButton.setEnabled(true);
                    officialStatus.setText("刷新失败：" + safeMessage(error));
                    if (userInitiated) toast("无法刷新官网列表");
                });
            }
        }, "official-catalog").start();
    }

    private void applyOfficialCatalog(OfficialFirmwareService.CatalogResult result, boolean refreshed) {
        officialFirmwares = result.items;
        refreshButton.setEnabled(true);
        officialStatus.setText(result.items.isEmpty() ? "官网当前没有可安装固件。" :
                "可用 " + result.items.size() + " 个；已按最大槽容量筛选" +
                        (refreshed ? "，刚刚更新。" : "。"));
        refreshOfficialList();
        if (result.items.isEmpty() && result.fetchedAt == 0) refreshOfficialCatalog(false);
    }

    private void refreshOfficialList() {
        officialList.removeAllViews();
        if (officialFirmwares.isEmpty()) {
            officialList.addView(text("点击刷新后显示官网固件。", 13, COLOR_MUTED, false));
            return;
        }
        for (OfficialFirmware firmware : officialFirmwares) {
            LinearLayout row = vertical();
            row.setPadding(0, dp(9), 0, dp(9));
            row.addView(text(firmware.displayName(), 15, COLOR_TEXT, true));
            row.addView(text(formatSize(firmware.sourceSize) + " · SHA-256 " +
                    firmware.sourceSha256.substring(0, 12) + "…", 12, COLOR_MUTED, false));
            FirmwareImage local = firmwareRepository.findOfficial(firmware);
            Button action;
            if (firmware.slug.equals(downloadingSlug)) {
                action = compactButton("下载中", view -> { });
                action.setEnabled(false);
            } else if (local != null) {
                action = compactButton("选择槽位安装", view -> chooseInstallSlot(local));
            } else {
                action = compactButton("下载并校验", view -> requestOfficialDownload(firmware));
            }
            LinearLayout.LayoutParams actionParams = matchWrap();
            actionParams.topMargin = dp(6);
            row.addView(action, actionParams);
            officialList.addView(row);
        }
    }

    private void requestOfficialDownload(OfficialFirmware firmware) {
        if (Build.VERSION.SDK_INT <= 28 &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                        PackageManager.PERMISSION_GRANTED) {
            pendingStorageDownload = firmware;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
            return;
        }
        beginOfficialDownload(firmware);
    }

    private void beginOfficialDownload(OfficialFirmware firmware) {
        downloadingSlug = firmware.slug;
        downloadProgress.setVisibility(View.VISIBLE);
        downloadProgress.setProgress(0);
        officialStatus.setText("正在下载、校验并转换 “" + firmware.displayName() + "”…");
        refreshOfficialList();
        new Thread(() -> {
            try {
                OfficialFirmwareService.DownloadResult result = officialFirmwareService.download(
                        firmware, firmwareRepository, percent ->
                                runOnUiThread(() -> downloadProgress.setProgress(percent)));
                runOnUiThread(() -> {
                    downloadingSlug = null;
                    downloadProgress.setVisibility(View.GONE);
                    officialStatus.setText("已保存：" + result.downloadLocation);
                    refreshFirmwareList();
                    refreshOfficialList();
                    if (BridgeService.isConnected()) chooseInstallSlot(result.image);
                });
            } catch (IOException error) {
                runOnUiThread(() -> {
                    downloadingSlug = null;
                    downloadProgress.setVisibility(View.GONE);
                    officialStatus.setText("下载失败：" + safeMessage(error));
                    refreshOfficialList();
                });
            }
        }, "official-download").start();
    }

    private void openDownloads() {
        try {
            startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
        } catch (RuntimeException error) {
            Intent fallback = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.setType("application/octet-stream");
            startActivity(fallback);
        }
    }

    private String formatSize(long bytes) {
        return String.format(Locale.ROOT, "%.2f MiB", bytes / 1048576.0);
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.trim().isEmpty() ? "未知错误" : message;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private LinearLayout card() {
        LinearLayout layout = vertical();
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));
        layout.setBackground(rounded(COLOR_CARD, 8));
        return layout;
    }

    private TextView sectionTitle(String value) {
        TextView title = text(value, 14, COLOR_MUTED, true);
        title.setPadding(0, dp(22), 0, dp(8));
        return title;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(2), 1.05f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button actionButton(String value, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(Color.rgb(7, 32, 28));
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackground(rounded(COLOR_PRIMARY, 7));
        button.setOnClickListener(listener);
        return button;
    }

    private Button outlineButton(String value, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(COLOR_TEXT);
        button.setTextSize(14);
        button.setAllCaps(false);
        GradientDrawable background = rounded(COLOR_CARD_ALT, 7);
        background.setStroke(dp(1), Color.rgb(58, 91, 83));
        button.setBackground(background);
        button.setOnClickListener(listener);
        return button;
    }

    private Button compactButton(String value, View.OnClickListener listener) {
        Button button = outlineButton(value, listener);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(10), dp(7), dp(10), dp(7));
        return button;
    }

    private ProgressBar progressBar() {
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(COLOR_PRIMARY));
        return progress;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightedButton(boolean withLeftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (withLeftMargin) params.leftMargin = dp(8);
        return params;
    }

    private LinearLayout.LayoutParams params(int widthDp, int heightDp) {
        return new LinearLayout.LayoutParams(dp(widthDp), dp(heightDp));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
