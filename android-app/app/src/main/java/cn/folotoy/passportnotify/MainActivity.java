package cn.folotoy.passportnotify;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    static final String PREFS = "allowlist";
    static final String PACKAGES = "packages";
    static final String OTP = "show_otp";
    private static final int REQUEST_IMPORT_FIRMWARE = 72;
    private static final int COLOR_BACKGROUND = Color.rgb(10, 17, 24);
    private static final int COLOR_CARD = Color.rgb(24, 37, 49);
    private static final int COLOR_CARD_ALT = Color.rgb(35, 54, 70);
    private static final int COLOR_PRIMARY = Color.rgb(62, 207, 174);
    private static final int COLOR_BLUE = Color.rgb(74, 126, 255);
    private static final int COLOR_TEXT = Color.rgb(244, 248, 251);
    private static final int COLOR_MUTED = Color.rgb(145, 169, 185);

    private FirmwareRepository firmwareRepository;
    private TextView connectionStatus;
    private TextView connectionDetail;
    private TextView slotStatus;
    private TextView selectedAppsText;
    private LinearLayout appList;
    private LinearLayout firmwareList;
    private ProgressBar transferProgress;
    private Button connectButton;
    private Button testButton;
    private Button clearSlotButton;
    private boolean appListExpanded;
    private boolean receiverRegistered;

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
        setContentView(buildContent());
        refreshConnectionState();
        refreshFirmwareList();
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
        refreshSelectedApps();
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
        logo.setBackground(rounded(COLOR_CARD_ALT, 18));
        logo.setPadding(dp(8), dp(8), dp(8), dp(8));
        header.addView(logo, params(58, 58));
        LinearLayout brand = vertical();
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        brandParams.leftMargin = dp(14);
        header.addView(brand, brandParams);
        brand.addView(text("AI Passport 门户", 25, COLOR_TEXT, true));
        brand.addView(text("通知桥接 · 固件管理", 13, COLOR_MUTED, false));
        root.addView(header);

        TextView privacy = text("所有通知与固件均在手机和设备之间通过加密蓝牙传输，不经过云端。",
                13, COLOR_MUTED, false);
        LinearLayout.LayoutParams privacyParams = matchWrap();
        privacyParams.topMargin = dp(12);
        root.addView(privacy, privacyParams);

        root.addView(sectionTitle("设备连接"));
        LinearLayout deviceCard = card();
        LinearLayout statusRow = horizontal();
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        connectionStatus = text("●  服务未启动", 17, COLOR_MUTED, true);
        statusRow.addView(connectionStatus, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView secure = badge("本机安全连接", COLOR_CARD_ALT, COLOR_PRIMARY);
        statusRow.addView(secure);
        deviceCard.addView(statusRow);
        connectionDetail = text("应用会在启动、开机和蓝牙恢复后自动连接。", 14, COLOR_MUTED, false);
        connectionDetail.setPadding(0, dp(9), 0, 0);
        deviceCard.addView(connectionDetail);
        slotStatus = text("设备玩法槽：尚未读取", 14, COLOR_TEXT, false);
        slotStatus.setPadding(0, dp(12), 0, 0);
        deviceCard.addView(slotStatus);
        transferProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        transferProgress.setMax(100);
        transferProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(COLOR_PRIMARY));
        LinearLayout.LayoutParams progressParams = matchWrap();
        progressParams.topMargin = dp(10);
        deviceCard.addView(transferProgress, progressParams);

        LinearLayout connectionActions = horizontal();
        connectionActions.setPadding(0, dp(14), 0, 0);
        connectButton = actionButton("立即重连", COLOR_PRIMARY, Color.rgb(7, 32, 28),
                view -> startBridge(true));
        testButton = actionButton("发送测试", COLOR_BLUE, Color.WHITE,
                view -> {
                    if (!BridgeService.publish(2, "通知桥测试\n手机与 AI Passport 连接正常")) {
                        toast("设备尚未连接，或正在安装固件");
                    }
                });
        connectionActions.addView(connectButton, weightedButton(false));
        connectionActions.addView(testButton, weightedButton(true));
        deviceCard.addView(connectionActions);

        LinearLayout permissionActions = horizontal();
        permissionActions.setPadding(0, dp(8), 0, 0);
        permissionActions.addView(outlineButton("授权蓝牙", view -> requestBluetoothPermissions()),
                weightedButton(false));
        permissionActions.addView(outlineButton("通知使用权", view ->
                        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))),
                weightedButton(true));
        deviceCard.addView(permissionActions);
        Button stop = outlineButton("暂停自动连接", view -> {
            BridgeService.setAutoConnectEnabled(this, false);
            Intent intent = new Intent(this, BridgeService.class).setAction(BridgeService.STOP);
            startService(intent);
            toast("已暂停；点击“立即重连”可恢复自动连接");
        });
        LinearLayout.LayoutParams stopParams = matchWrap();
        stopParams.topMargin = dp(8);
        deviceCard.addView(stop, stopParams);
        root.addView(deviceCard);

        root.addView(sectionTitle("通知转发"));
        LinearLayout notificationCard = card();
        TextView notificationIntro = text(
                "只转发你勾选的应用。设备最多保留最近 32 条；短按 OK 只切换应用，不会删除消息。",
                14, COLOR_MUTED, false);
        notificationCard.addView(notificationIntro);
        Switch otp = new Switch(this);
        otp.setText("显示短信正文和验证码");
        otp.setTextColor(COLOR_TEXT);
        otp.setTextSize(15);
        otp.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(OTP, false));
        otp.setPadding(0, dp(10), 0, dp(6));
        otp.setOnCheckedChangeListener((button, checked) ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(OTP, checked).apply());
        notificationCard.addView(otp);
        TextView otpHint = text("关闭时会隐藏短信正文，并遮盖普通通知中的 4–8 位独立数字。",
                12, COLOR_MUTED, false);
        notificationCard.addView(otpHint);

        LinearLayout appsHeader = horizontal();
        appsHeader.setGravity(Gravity.CENTER_VERTICAL);
        appsHeader.setPadding(0, dp(14), 0, 0);
        selectedAppsText = text("已选择 0 个应用", 15, COLOR_TEXT, true);
        appsHeader.addView(selectedAppsText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button toggleApps = compactButton("管理应用", view -> {
            appListExpanded = !appListExpanded;
            appList.setVisibility(appListExpanded ? View.VISIBLE : View.GONE);
            ((Button)view).setText(appListExpanded ? "收起" : "管理应用");
            if (appListExpanded && appList.getChildCount() == 0) showApps();
        });
        appsHeader.addView(toggleApps);
        notificationCard.addView(appsHeader);
        appList = vertical();
        appList.setVisibility(View.GONE);
        notificationCard.addView(appList);
        root.addView(notificationCard);

        root.addView(sectionTitle("固件玩法库"));
        LinearLayout firmwareCard = card();
        firmwareCard.addView(text(
                "手机可保存多个玩法；AI Passport 始终保留当前通知门户，并提供 1 个可替换玩法槽。",
                14, COLOR_MUTED, false));
        TextView formatHint = text(
                "仅接收 ESP32-C3 应用 .bin（≤4.00 MiB），仍需确认兼容 AI Passport 硬件；不要选择 full.bin。",
                12, Color.rgb(244, 184, 96), false);
        formatHint.setPadding(0, dp(8), 0, 0);
        firmwareCard.addView(formatHint);
        LinearLayout firmwareActions = horizontal();
        firmwareActions.setPadding(0, dp(14), 0, 0);
        firmwareActions.addView(actionButton("＋ 导入固件", COLOR_PRIMARY,
                Color.rgb(7, 32, 28), view -> chooseFirmware()), weightedButton(false));
        clearSlotButton = outlineButton("清空设备槽", view -> confirmClearSlot());
        firmwareActions.addView(clearSlotButton, weightedButton(true));
        firmwareCard.addView(firmwareActions);
        firmwareList = vertical();
        firmwareList.setPadding(0, dp(10), 0, 0);
        firmwareCard.addView(firmwareList);
        root.addView(firmwareCard);

        root.addView(sectionTitle("设备按键"));
        LinearLayout helpCard = card();
        helpCard.addView(keyRow("双击 OK", "进入或退出玩法门户"));
        helpCard.addView(divider());
        helpCard.addView(keyRow("上 / 下", "在门户中选择常驻门户或自定义玩法"));
        helpCard.addView(divider());
        helpCard.addView(keyRow("长按 OK 3 秒", "启动选中的自定义玩法"));
        helpCard.addView(divider());
        helpCard.addView(keyRow("重新开机", "兼容玩法会自动回到常驻门户"));
        helpCard.addView(divider());
        helpCard.addView(keyRow("消息页 OK", "在全部 / 微信 / 飞书 / 短信等应用间切换"));
        helpCard.addView(divider());
        helpCard.addView(keyRow("消息页长按上 + OK", "仅清空当前消息所属应用"));
        TextView recovery = text(
                "注意：第三方固件若主动确认 OTA 或依赖不同分区表，可能无法自动返回，只能用 USB 恢复门户。",
                12, Color.rgb(244, 184, 96), false);
        recovery.setPadding(0, dp(12), 0, 0);
        helpCard.addView(recovery);
        root.addView(helpCard);
        return scroll;
    }

    private void startBridge(boolean interactive) {
        if (interactive) BridgeService.setAutoConnectEnabled(this, true);
        else if (!BridgeService.isAutoConnectEnabled(this)) return;
        if (!hasBluetoothPermissions()) {
            if (interactive) requestBluetoothPermissions();
            return;
        }
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        try {
            if (adapter == null || !adapter.isEnabled()) {
                if (interactive) startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
                return;
            }
        } catch (SecurityException ignored) {
            if (interactive) requestBluetoothPermissions();
            return;
        }
        BridgeService.requestAutomaticStart(this);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && hasBluetoothPermissions()) startBridge(false);
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT}, 1);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    private void chooseFirmware() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/octet-stream", "application/x-binary", "*/*"});
        startActivityForResult(intent, REQUEST_IMPORT_FIRMWARE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_FIRMWARE || resultCode != RESULT_OK ||
                data == null || data.getData() == null) return;
        Uri uri = data.getData();
        toast("正在校验并导入固件…");
        new Thread(() -> {
            try {
                FirmwareImage image = firmwareRepository.importFrom(uri);
                runOnUiThread(() -> {
                    refreshFirmwareList();
                    toast("已导入 " + image.displayName());
                });
            } catch (Exception error) {
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("无法导入固件")
                        .setMessage(error.getMessage())
                        .setPositiveButton("知道了", null)
                        .show());
            }
        }, "firmware-import").start();
    }

    private void confirmClearSlot() {
        if (!BridgeService.isConnected() || !BridgeService.isFirmwareSupported()) {
            toast("请先连接已升级门户固件的 AI Passport");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空设备玩法槽？")
                .setMessage("只移除设备上的自定义玩法，不会删除手机固件库，也不会影响通知门户。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> {
                    if (!BridgeService.clearDeviceFirmware()) toast("设备当前不可操作");
                }).show();
    }

    private void confirmInstall(FirmwareImage image) {
        if (!BridgeService.isConnected()) {
            toast("请先连接 AI Passport");
            return;
        }
        if (!BridgeService.isFirmwareSupported()) {
            toast("设备还不是门户固件，请先升级设备端");
            return;
        }
        if (BridgeService.isTransferActive()) {
            toast("已有固件正在安装");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("安装到设备玩法槽？")
                .setMessage(image.displayName() + "\n" + formatSize(image.size) +
                        "\n\n这会替换设备槽里的旧玩法。传输期间请保持设备供电和手机靠近。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始安装", (dialog, which) -> {
                    if (!BridgeService.installFirmware(image.file, image.displayName())) {
                        toast("无法开始安装，请检查连接状态");
                    }
                }).show();
    }

    private void refreshConnectionState() {
        if (connectionStatus == null) return;
        boolean connected = BridgeService.isConnected();
        boolean active = BridgeService.isTransferActive();
        connectionStatus.setText((connected ? "●  已连接" : "●  未连接"));
        connectionStatus.setTextColor(connected ? COLOR_PRIMARY : COLOR_MUTED);
        connectionDetail.setText(BridgeService.statusText());
        if (active) {
            transferProgress.setVisibility(View.VISIBLE);
            transferProgress.setProgress(BridgeService.transferProgress());
        } else {
            transferProgress.setVisibility(View.GONE);
        }
        if (!connected) {
            slotStatus.setText("设备玩法槽：等待连接后读取");
        } else if (!BridgeService.isFirmwareSupported()) {
            slotStatus.setText("设备玩法槽：当前固件不支持，请先升级门户");
        } else if (BridgeService.isSlotPresent()) {
            slotStatus.setText(getString(R.string.device_slot_name, BridgeService.slotName()));
        } else {
            slotStatus.setText("设备玩法槽：空");
        }
        connectButton.setText(connected ? "设备已连接" : "立即重连");
        connectButton.setEnabled(!connected && !active);
        connectButton.setAlpha(connectButton.isEnabled() ? 1f : 0.55f);
        testButton.setEnabled(connected && !active);
        testButton.setAlpha(testButton.isEnabled() ? 1f : 0.45f);
        clearSlotButton.setEnabled(connected && BridgeService.isFirmwareSupported() &&
                BridgeService.isSlotPresent() && !active);
        clearSlotButton.setAlpha(clearSlotButton.isEnabled() ? 1f : 0.45f);
        refreshFirmwareButtons();
    }

    private void refreshFirmwareList() {
        firmwareList.removeAllViews();
        List<FirmwareImage> images = firmwareRepository.list();
        if (images.isEmpty()) {
            TextView empty = text("还没有导入固件。导入后可离线保存在手机中，随时替换设备玩法槽。",
                    13, COLOR_MUTED, false);
            empty.setPadding(0, dp(10), 0, dp(4));
            firmwareList.addView(empty);
            return;
        }
        for (FirmwareImage image : images) firmwareList.addView(firmwareRow(image));
        refreshFirmwareButtons();
    }

    private View firmwareRow(FirmwareImage image) {
        LinearLayout row = vertical();
        row.setTag("firmware-row");
        row.setBackground(rounded(COLOR_CARD_ALT, 14));
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.topMargin = dp(8);
        row.setLayoutParams(rowParams);

        row.addView(text(image.displayName(), 16, COLOR_TEXT, true));
        String details = (image.version == null || image.version.isBlank() ? "版本未知" :
                "版本 " + image.version) + "  ·  " + formatSize(image.size);
        TextView meta = text(details, 13, COLOR_MUTED, false);
        meta.setPadding(0, dp(3), 0, 0);
        row.addView(meta);
        TextView hash = text("SHA-256  " + image.sha256.substring(0, 12) + "…",
                12, COLOR_MUTED, false);
        hash.setPadding(0, dp(3), 0, 0);
        row.addView(hash);

        LinearLayout actions = horizontal();
        actions.setPadding(0, dp(10), 0, 0);
        Button install = compactButton("安装到设备", view -> confirmInstall(image));
        install.setTag("install-button");
        actions.addView(install, new LinearLayout.LayoutParams(0, dp(42), 1f));
        Button remove = compactButton("从手机删除", view -> new AlertDialog.Builder(this)
                .setTitle("删除本地固件？")
                .setMessage(image.displayName() + " 将从手机固件库移除，不影响已安装到设备的版本。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    firmwareRepository.delete(image);
                    refreshFirmwareList();
                }).show());
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        removeParams.leftMargin = dp(8);
        actions.addView(remove, removeParams);
        row.addView(actions);
        return row;
    }

    private void refreshFirmwareButtons() {
        if (firmwareList == null) return;
        boolean enabled = BridgeService.isConnected() && BridgeService.isFirmwareSupported() &&
                !BridgeService.isTransferActive();
        for (int index = 0; index < firmwareList.getChildCount(); index++) {
            View row = firmwareList.getChildAt(index);
            if (!(row instanceof ViewGroup)) continue;
            View button = findTagged((ViewGroup)row, "install-button");
            if (button != null) {
                button.setEnabled(enabled);
                button.setAlpha(enabled ? 1f : 0.45f);
            }
        }
    }

    private View findTagged(ViewGroup root, String tag) {
        for (int index = 0; index < root.getChildCount(); index++) {
            View child = root.getChildAt(index);
            if (tag.equals(child.getTag())) return child;
            if (child instanceof ViewGroup) {
                View nested = findTagged((ViewGroup)child, tag);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private void showApps() {
        appList.removeAllViews();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = getPackageManager().queryIntentActivities(launcher, 0);
        apps.sort((first, second) -> first.loadLabel(getPackageManager()).toString()
                .compareToIgnoreCase(second.loadLabel(getPackageManager()).toString()));
        Set<String> allowed = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getStringSet(PACKAGES, Collections.emptySet());
        List<String> seen = new ArrayList<>();
        for (ResolveInfo info : apps) {
            String packageName = info.activityInfo.packageName;
            if (seen.contains(packageName) || packageName.equals(getPackageName())) continue;
            seen.add(packageName);
            LinearLayout row = horizontal();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(5), 0, dp(5));
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(info.loadIcon(getPackageManager()));
            row.addView(icon, params(36, 36));
            TextView title = text(info.loadLabel(getPackageManager()).toString(), 15, COLOR_TEXT, false);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            titleParams.leftMargin = dp(10);
            row.addView(title, titleParams);
            CheckBox check = new CheckBox(this);
            check.setChecked(allowed.contains(packageName));
            check.setOnCheckedChangeListener((button, checked) -> {
                Set<String> copy = new HashSet<>(getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getStringSet(PACKAGES, Collections.emptySet()));
                if (checked) copy.add(packageName);
                else copy.remove(packageName);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(PACKAGES, copy).apply();
                refreshSelectedApps();
            });
            row.addView(check);
            appList.addView(row);
        }
    }

    private void refreshSelectedApps() {
        if (selectedAppsText == null) return;
        int count = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getStringSet(PACKAGES, Collections.emptySet()).size();
        selectedAppsText.setText(getString(R.string.selected_app_count, count));
    }

    private LinearLayout card() {
        LinearLayout card = vertical();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(rounded(COLOR_CARD, 18));
        return card;
    }

    private TextView sectionTitle(String value) {
        TextView title = text(value, 14, COLOR_MUTED, true);
        title.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(24);
        params.bottomMargin = dp(8);
        title.setLayoutParams(params);
        return title;
    }

    private TextView keyRow(String key, String value) {
        TextView text = text(key + "    " + value, 14, COLOR_TEXT, false);
        text.setPadding(0, dp(8), 0, dp(8));
        return text;
    }

    private View divider() {
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(48, 68, 83));
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return divider;
    }

    private Button actionButton(String value, int background, int foreground,
                                View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(14);
        button.setTextColor(foreground);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(rounded(background, 12));
        button.setOnClickListener(listener);
        return button;
    }

    private Button outlineButton(String value, View.OnClickListener listener) {
        Button button = actionButton(value, COLOR_CARD_ALT, COLOR_TEXT, listener);
        return button;
    }

    private Button compactButton(String value, View.OnClickListener listener) {
        Button button = actionButton(value, COLOR_CARD_ALT, COLOR_TEXT, listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        button.setLayoutParams(params);
        return button;
    }

    private TextView badge(String value, int background, int foreground) {
        TextView badge = text(value, 11, foreground, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(9), dp(5), dp(9), dp(5));
        badge.setBackground(rounded(background, 20));
        return badge;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setLineSpacing(0, 1.12f);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
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

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams weightedButton(boolean withLeftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        if (withLeftMargin) params.leftMargin = dp(8);
        return params;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams params(int widthDp, int heightDp) {
        return new LinearLayout.LayoutParams(dp(widthDp), dp(heightDp));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String formatSize(long bytes) {
        return String.format(Locale.US, "%.2f MiB", bytes / 1048576.0);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
}
