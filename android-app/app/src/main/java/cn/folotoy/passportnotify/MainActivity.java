package cn.folotoy.passportnotify;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.provider.Telephony;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    static final String PREFS = "allowlist";
    static final String PACKAGES = "packages";
    static final String OTP = "show_otp";
    private static final int REQUEST_SETUP_PERMISSIONS = 1;
    private static final int REQUEST_DOWNLOAD_STORAGE = 2;
    private static final int COLOR_BACKGROUND = Color.rgb(10, 17, 24);
    private static final int COLOR_CARD = Color.rgb(24, 37, 49);
    private static final int COLOR_CARD_ALT = Color.rgb(35, 54, 70);
    private static final int COLOR_PRIMARY = Color.rgb(62, 207, 174);
    private static final int COLOR_BLUE = Color.rgb(74, 126, 255);
    private static final int COLOR_TEXT = Color.rgb(244, 248, 251);
    private static final int COLOR_MUTED = Color.rgb(145, 169, 185);

    private FirmwareRepository firmwareRepository;
    private OfficialFirmwareService officialFirmwareService;
    private TextView setupStatus;
    private Button setupButton;
    private TextView connectionStatus;
    private TextView connectionDetail;
    private TextView slotStatus;
    private TextView selectedAppsText;
    private LinearLayout appList;
    private LinearLayout officialFirmwareList;
    private LinearLayout firmwareList;
    private TextView officialFirmwareStatus;
    private ProgressBar officialFirmwareProgress;
    private Button refreshOfficialButton;
    private ProgressBar transferProgress;
    private Button connectButton;
    private Button testButton;
    private Button clearSlotButton;
    private Button toggleAppsButton;
    private boolean appListExpanded;
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
        refreshSetupState();
        refreshConnectionState();
        refreshFirmwareList();
        OfficialFirmwareService.CatalogResult cached = officialFirmwareService.loadCached();
        applyOfficialCatalog(cached, false);
        if (cached.items.isEmpty()) refreshOfficialCatalog(false);
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
        refreshSetupState();
        refreshConnectionState();
        refreshSelectedApps();
        startBridge(false);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshSetupState();
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

        root.addView(sectionTitle("首次使用"));
        LinearLayout setupCard = card();
        LinearLayout setupHeader = horizontal();
        setupHeader.setGravity(Gravity.CENTER_VERTICAL);
        setupHeader.addView(text("快速启用", 18, COLOR_TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        setupHeader.addView(badge("Android 8+", COLOR_CARD_ALT, COLOR_PRIMARY));
        setupCard.addView(setupHeader);
        TextView setupIntro = text(
                "按顺序完成系统权限、通知使用权、应用选择和设备连接。已完成的步骤会自动跳过。",
                13, COLOR_MUTED, false);
        setupIntro.setPadding(0, dp(9), 0, 0);
        setupCard.addView(setupIntro);
        setupStatus = text("正在检查手机设置…", 14, COLOR_TEXT, false);
        setupStatus.setLineSpacing(dp(3), 1.08f);
        setupStatus.setPadding(0, dp(14), 0, dp(4));
        setupCard.addView(setupStatus);
        setupButton = actionButton("一键开始设置", COLOR_PRIMARY, Color.rgb(7, 32, 28),
                view -> advanceSetup());
        LinearLayout.LayoutParams setupButtonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        setupButtonParams.topMargin = dp(12);
        setupCard.addView(setupButton, setupButtonParams);
        Button appSettings = outlineButton("权限或后台运行有问题？打开系统应用设置",
                view -> openApplicationSettings());
        LinearLayout.LayoutParams appSettingsParams = matchWrap();
        appSettingsParams.topMargin = dp(8);
        setupCard.addView(appSettings, appSettingsParams);
        root.addView(setupCard);

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
        permissionActions.addView(outlineButton("重新检查权限", view -> advanceSetup()),
                weightedButton(false));
        permissionActions.addView(outlineButton("通知使用权", view -> openNotificationAccess()),
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
        toggleAppsButton = compactButton("管理应用", view -> {
            if (appListExpanded) collapseAppList();
            else expandAppList();
        });
        appsHeader.addView(toggleAppsButton);
        notificationCard.addView(appsHeader);
        Button recommendedApps = outlineButton("一键选择已安装的微信 / 飞书 / 短信等常用应用",
                view -> confirmRecommendedApps());
        LinearLayout.LayoutParams recommendedParams = matchWrap();
        recommendedParams.topMargin = dp(8);
        notificationCard.addView(recommendedApps, recommendedParams);
        appList = vertical();
        appList.setVisibility(View.GONE);
        notificationCard.addView(appList);
        root.addView(notificationCard);

        root.addView(sectionTitle("官方固件玩法库"));
        LinearLayout firmwareCard = card();
        firmwareCard.addView(text(
                "不再手动选择本地文件。应用从 FoloToy 官网读取已发布固件，只显示官方且不超过 4.00 MiB 的玩法。",
                14, COLOR_MUTED, false));
        TextView formatHint = text(
                "下载时会先核对官网大小与 SHA-256，再提取可写入 4 MiB 用户槽的应用镜像，并保存到手机 Download/AI-Passport。",
                12, Color.rgb(244, 184, 96), false);
        formatHint.setPadding(0, dp(8), 0, 0);
        firmwareCard.addView(formatHint);
        LinearLayout firmwareActions = horizontal();
        firmwareActions.setPadding(0, dp(14), 0, 0);
        refreshOfficialButton = actionButton("刷新官方列表", COLOR_PRIMARY,
                Color.rgb(7, 32, 28), view -> refreshOfficialCatalog(true));
        firmwareActions.addView(refreshOfficialButton, weightedButton(false));
        firmwareActions.addView(outlineButton("打开下载目录", view -> openDownloads()),
                weightedButton(true));
        firmwareCard.addView(firmwareActions);
        clearSlotButton = outlineButton("清空设备玩法槽", view -> confirmClearSlot());
        LinearLayout.LayoutParams clearParams = matchWrap();
        clearParams.topMargin = dp(8);
        firmwareCard.addView(clearSlotButton, clearParams);
        officialFirmwareStatus = text("正在读取官方固件列表…", 13, COLOR_MUTED, false);
        officialFirmwareStatus.setPadding(0, dp(12), 0, 0);
        firmwareCard.addView(officialFirmwareStatus);
        officialFirmwareProgress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        officialFirmwareProgress.setMax(100);
        officialFirmwareProgress.setProgressTintList(
                android.content.res.ColorStateList.valueOf(COLOR_PRIMARY));
        officialFirmwareProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams officialProgressParams = matchWrap();
        officialProgressParams.topMargin = dp(8);
        firmwareCard.addView(officialFirmwareProgress, officialProgressParams);
        TextView onlineTitle = text("官网可下载", 14, COLOR_TEXT, true);
        onlineTitle.setPadding(0, dp(14), 0, 0);
        firmwareCard.addView(onlineTitle);
        officialFirmwareList = vertical();
        officialFirmwareList.setPadding(0, dp(2), 0, 0);
        firmwareCard.addView(officialFirmwareList);
        TextView downloadedTitle = text("应用内已校验固件", 14, COLOR_TEXT, true);
        downloadedTitle.setPadding(0, dp(16), 0, 0);
        firmwareCard.addView(downloadedTitle);
        firmwareList = vertical();
        firmwareList.setPadding(0, dp(2), 0, 0);
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
                "注意：即使来自官网，玩法若主动确认 OTA 或依赖不同分区表，也可能无法自动返回；异常时用 USB 恢复门户。",
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
            if (interactive) requestRequiredPermissions();
            return;
        }
        if (!isLegacyLocationReady()) {
            if (interactive) startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
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
            if (interactive) requestRequiredPermissions();
            return;
        }
        BridgeService.requestAutomaticStart(this);
    }

    private void advanceSetup() {
        if (!hasBluetoothPermissions() || !hasPostNotificationPermission()) {
            requestRequiredPermissions();
            return;
        }
        if (!isBluetoothEnabled()) {
            try {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (SecurityException error) {
                requestRequiredPermissions();
            }
            return;
        }
        if (!isLegacyLocationReady()) {
            new AlertDialog.Builder(this)
                    .setTitle("需要打开定位开关")
                    .setMessage("Android 8–11 将低功耗蓝牙扫描与定位开关绑定。应用不会读取或上传你的位置。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("去打开", (dialog, which) ->
                            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                    .show();
            return;
        }
        if (!hasNotificationAccess()) {
            new AlertDialog.Builder(this)
                    .setTitle("开启通知使用权")
                    .setMessage("请在下一页打开“AI Passport 门户”。系统要求由用户本人确认，应用无法静默授权。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("继续", (dialog, which) -> openNotificationAccess())
                    .show();
            return;
        }
        if (selectedAppCount() == 0) {
            confirmRecommendedApps();
            return;
        }
        startBridge(true);
        refreshSetupState();
        if (!BridgeService.isConnected()) toast("设置完成，正在自动搜索 AI Passport");
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_SETUP_PERMISSIONS) {
            refreshSetupState();
            if (hasBluetoothPermissions()) startBridge(false);
        } else if (requestCode == REQUEST_DOWNLOAD_STORAGE) {
            OfficialFirmware pending = pendingStorageDownload;
            pendingStorageDownload = null;
            if (pending != null && grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                beginOfficialDownload(pending);
            } else if (pending != null) {
                toast("需要存储权限才能写入系统下载目录");
            }
        }
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasPostNotificationPermission() {
        return Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_SETUP_PERMISSIONS);
        } else if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_SETUP_PERMISSIONS);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_SETUP_PERMISSIONS);
        }
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
        LocationManager manager = getSystemService(LocationManager.class);
        if (manager == null) return false;
        if (Build.VERSION.SDK_INT >= 28) return manager.isLocationEnabled();
        try {
            return Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE) !=
                    Settings.Secure.LOCATION_MODE_OFF;
        } catch (Settings.SettingNotFoundException ignored) {
            return false;
        }
    }

    private boolean hasNotificationAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        if (enabled == null || enabled.trim().isEmpty()) return false;
        ComponentName target = new ComponentName(this, PhoneNotificationListener.class);
        for (String value : enabled.split(":")) {
            ComponentName item = ComponentName.unflattenFromString(value);
            if (target.equals(item)) return true;
        }
        return false;
    }

    private void openNotificationAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (RuntimeException error) {
            openApplicationSettings();
        }
    }

    private void openApplicationSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private int selectedAppCount() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getStringSet(PACKAGES, Collections.emptySet()).size();
    }

    private void refreshSetupState() {
        if (setupStatus == null || setupButton == null) return;
        boolean permissions = hasBluetoothPermissions() && hasPostNotificationPermission();
        boolean bluetooth = isBluetoothEnabled();
        boolean location = isLegacyLocationReady();
        boolean listener = hasNotificationAccess();
        boolean apps = selectedAppCount() > 0;
        boolean connected = BridgeService.isConnected();
        StringBuilder value = new StringBuilder();
        appendSetupLine(value, permissions, Build.VERSION.SDK_INT >= 31 ?
                "附近设备与应用通知权限" : "定位与应用通知权限");
        appendSetupLine(value, bluetooth && location, Build.VERSION.SDK_INT >= 31 ?
                "蓝牙已打开" : "蓝牙和定位开关已打开");
        appendSetupLine(value, listener, "通知使用权");
        appendSetupLine(value, apps, apps ? "已选择 " + selectedAppCount() + " 个通知应用" :
                "选择要转发的通知应用");
        appendSetupLine(value, connected, connected ? "AI Passport 已连接" : "等待连接 AI Passport");
        setupStatus.setText(value.toString());

        boolean ready = permissions && bluetooth && location && listener && apps;
        boolean complete = connected && ready;
        setupButton.setText(complete ? "设置完成 · 设备已连接" :
                ready ? "连接 AI Passport" : "继续完成设置");
        setupButton.setEnabled(!complete);
        setupButton.setAlpha(complete ? 0.6f : 1f);
    }

    private static void appendSetupLine(StringBuilder value, boolean complete, String label) {
        if (value.length() > 0) value.append('\n');
        value.append(complete ? "✓  " : "○  ").append(label);
    }

    private void expandAppList() {
        appListExpanded = true;
        appList.setVisibility(View.VISIBLE);
        if (toggleAppsButton != null) toggleAppsButton.setText("收起");
        if (appList.getChildCount() == 0) showApps();
    }

    private void collapseAppList() {
        appListExpanded = false;
        appList.setVisibility(View.GONE);
        if (toggleAppsButton != null) toggleAppsButton.setText("管理应用");
    }

    private void confirmRecommendedApps() {
        Set<String> recommended = installedRecommendedPackages();
        if (recommended.isEmpty()) {
            expandAppList();
            toast("未识别到常用应用，请手动勾选");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择常用通知应用")
                .setMessage("将勾选本机已安装的微信、飞书/Lark、默认短信、QQ、钉钉、WhatsApp 或 Telegram。你可以随时修改。")
                .setNegativeButton("手动选择", (dialog, which) -> expandAppList())
                .setPositiveButton("一键勾选", (dialog, which) -> {
                    Set<String> selected = new HashSet<>(getSharedPreferences(PREFS, MODE_PRIVATE)
                            .getStringSet(PACKAGES, Collections.emptySet()));
                    selected.addAll(recommended);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putStringSet(PACKAGES, selected).apply();
                    refreshSelectedApps();
                    showApps();
                    startBridge(false);
                    toast("已选择 " + recommended.size() + " 个常用应用");
                })
                .show();
    }

    private Set<String> installedRecommendedPackages() {
        Set<String> installed = new HashSet<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo info : getPackageManager().queryIntentActivities(launcher, 0)) {
            installed.add(info.activityInfo.packageName);
        }
        String sms = Telephony.Sms.getDefaultSmsPackage(this);
        if (sms != null && !sms.trim().isEmpty()) installed.add(sms);
        String[] candidates = {
                "com.tencent.mm", "com.ss.android.lark", "com.larksuite.suite",
                "com.google.android.apps.messaging", "com.samsung.android.messaging",
                "com.android.mms", "com.tencent.mobileqq", "com.alibaba.android.rimet",
                "com.whatsapp", "org.telegram.messenger"
        };
        Set<String> result = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (installed.contains(candidate)) result.add(candidate);
        }
        if (sms != null && installed.contains(sms)) result.add(sms);
        return result;
    }

    private void refreshOfficialCatalog(boolean interactive) {
        if (refreshOfficialButton == null || officialFirmwareProgress == null) return;
        refreshOfficialButton.setEnabled(false);
        refreshOfficialButton.setAlpha(0.55f);
        officialFirmwareStatus.setText("正在从 FoloToy 官网同步固件目录…");
        officialFirmwareProgress.setIndeterminate(true);
        officialFirmwareProgress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                OfficialFirmwareService.CatalogResult result =
                        officialFirmwareService.refreshCatalog();
                runOnUiThread(() -> {
                    officialFirmwareProgress.setVisibility(View.GONE);
                    officialFirmwareProgress.setIndeterminate(false);
                    refreshOfficialButton.setEnabled(true);
                    refreshOfficialButton.setAlpha(1f);
                    applyOfficialCatalog(result, true);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    officialFirmwareProgress.setVisibility(View.GONE);
                    officialFirmwareProgress.setIndeterminate(false);
                    refreshOfficialButton.setEnabled(true);
                    refreshOfficialButton.setAlpha(1f);
                    OfficialFirmwareService.CatalogResult cached =
                            officialFirmwareService.loadCached();
                    applyOfficialCatalog(cached, false);
                    officialFirmwareStatus.setText(cached.items.isEmpty() ?
                            "官网列表同步失败：" + safeMessage(error) :
                            "官网同步失败，继续显示上次缓存：" + safeMessage(error));
                    if (interactive) new AlertDialog.Builder(this)
                            .setTitle("无法刷新官方固件")
                            .setMessage(safeMessage(error))
                            .setPositiveButton("知道了", null)
                            .show();
                });
            }
        }, "official-catalog").start();
    }

    private void applyOfficialCatalog(OfficialFirmwareService.CatalogResult result,
                                      boolean fresh) {
        officialFirmwares = result.items;
        if (officialFirmwareStatus != null) {
            if (result.items.isEmpty()) {
                officialFirmwareStatus.setText("尚未取得官方固件列表，请点击刷新。");
            } else {
                StringBuilder status = new StringBuilder(fresh ? "官网已同步：" : "缓存列表：");
                status.append(result.items.size()).append(" 个可安装");
                if (result.tooLargeCount > 0) {
                    status.append(" · 已隐藏 ").append(result.tooLargeCount)
                            .append(" 个超过 4.00 MiB 的固件");
                }
                if (result.unsupportedCount > 0) {
                    status.append(" · ").append(result.unsupportedCount).append(" 个格式不支持");
                }
                officialFirmwareStatus.setText(status.toString());
            }
        }
        refreshOfficialFirmwareList();
    }

    private void refreshOfficialFirmwareList() {
        if (officialFirmwareList == null) return;
        officialFirmwareList.removeAllViews();
        if (officialFirmwares.isEmpty()) {
            TextView empty = text("点击“刷新官方列表”后显示可下载玩法。",
                    13, COLOR_MUTED, false);
            empty.setPadding(0, dp(10), 0, dp(4));
            officialFirmwareList.addView(empty);
            return;
        }
        for (OfficialFirmware firmware : officialFirmwares) {
            officialFirmwareList.addView(officialFirmwareRow(firmware));
        }
    }

    private View officialFirmwareRow(OfficialFirmware firmware) {
        LinearLayout row = vertical();
        row.setBackground(rounded(COLOR_CARD_ALT, 14));
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.topMargin = dp(8);
        row.setLayoutParams(rowParams);

        LinearLayout titleRow = horizontal();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(text(firmware.displayName(), 16, COLOR_TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(badge("FoloToy 官方", Color.rgb(27, 75, 70), COLOR_PRIMARY));
        row.addView(titleRow);
        String revision = firmware.shareVersion == null || firmware.shareVersion.isEmpty() ?
                "官方发布" : "官方修订 " + firmware.shareVersion;
        TextView meta = text(revision + "  ·  官网包 " + formatSize(firmware.sourceSize),
                13, COLOR_MUTED, false);
        meta.setPadding(0, dp(4), 0, 0);
        row.addView(meta);
        TextView hash = text("官网 SHA-256  " + firmware.sourceSha256.substring(0, 12) + "…",
                12, COLOR_MUTED, false);
        hash.setPadding(0, dp(3), 0, 0);
        row.addView(hash);

        FirmwareImage local = firmwareRepository.findOfficial(firmware);
        Button action;
        if (firmware.slug.equals(downloadingSlug)) {
            action = compactButton("正在下载并校验…", view -> { });
            action.setEnabled(false);
            action.setAlpha(0.55f);
        } else if (local != null) {
            action = compactButton("已下载 · 安装到设备", view -> confirmInstall(local));
            boolean enabled = BridgeService.isConnected() && BridgeService.isFirmwareSupported() &&
                    !BridgeService.isTransferActive();
            action.setEnabled(enabled);
            action.setAlpha(enabled ? 1f : 0.45f);
        } else {
            action = compactButton("下载到手机", view -> requestOfficialDownload(firmware));
        }
        LinearLayout.LayoutParams actionParams = matchWrap();
        actionParams.topMargin = dp(10);
        row.addView(action, actionParams);
        if (local != null) {
            TextView saved = text("已校验并保存到 Download/AI-Passport",
                    12, COLOR_PRIMARY, false);
            saved.setPadding(0, dp(7), 0, 0);
            row.addView(saved);
        }
        return row;
    }

    private void requestOfficialDownload(OfficialFirmware firmware) {
        if (downloadingSlug != null) {
            toast("请等待当前固件下载完成");
            return;
        }
        if (Build.VERSION.SDK_INT <= 28 &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                        PackageManager.PERMISSION_GRANTED) {
            pendingStorageDownload = firmware;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_DOWNLOAD_STORAGE);
            return;
        }
        beginOfficialDownload(firmware);
    }

    private void beginOfficialDownload(OfficialFirmware firmware) {
        downloadingSlug = firmware.slug;
        officialFirmwareStatus.setText("正在从 FoloToy 官网下载 “" +
                firmware.displayName() + "”…");
        officialFirmwareProgress.setIndeterminate(false);
        officialFirmwareProgress.setProgress(0);
        officialFirmwareProgress.setVisibility(View.VISIBLE);
        refreshOfficialFirmwareList();
        new Thread(() -> {
            try {
                OfficialFirmwareService.DownloadResult result = officialFirmwareService.download(
                        firmware, firmwareRepository, percent -> runOnUiThread(() -> {
                            officialFirmwareProgress.setProgress(percent);
                            officialFirmwareStatus.setText("正在下载、校验并转换 “" +
                                    firmware.displayName() + "”  " + percent + "%");
                        }));
                runOnUiThread(() -> {
                    downloadingSlug = null;
                    officialFirmwareProgress.setVisibility(View.GONE);
                    officialFirmwareStatus.setText("已保存：" + result.downloadLocation);
                    refreshFirmwareList();
                    refreshOfficialFirmwareList();
                    if (BridgeService.isConnected() && BridgeService.isFirmwareSupported()) {
                        new AlertDialog.Builder(this)
                                .setTitle("官方下载完成")
                                .setMessage(result.image.displayName() + " 已通过大小、SHA-256 和 ESP32-C3 镜像校验，并保存到：\n" +
                                        result.downloadLocation + "\n\n现在安装到设备玩法槽吗？")
                                .setNegativeButton("稍后", null)
                                .setPositiveButton("安装", (dialog, which) ->
                                        confirmInstall(result.image))
                                .show();
                    } else {
                        toast("官方下载并校验完成，可连接设备后安装");
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    downloadingSlug = null;
                    officialFirmwareProgress.setVisibility(View.GONE);
                    officialFirmwareStatus.setText("下载失败：" + safeMessage(error));
                    refreshFirmwareList();
                    refreshOfficialFirmwareList();
                    new AlertDialog.Builder(this)
                            .setTitle("官方固件下载失败")
                            .setMessage(safeMessage(error))
                            .setPositiveButton("知道了", null)
                            .show();
                });
            }
        }, "official-firmware-download").start();
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
        refreshOfficialFirmwareList();
        refreshSetupState();
    }

    private void refreshFirmwareList() {
        firmwareList.removeAllViews();
        List<FirmwareImage> images = firmwareRepository.list();
        if (images.isEmpty()) {
            TextView empty = text("还没有已校验固件。请从上方官网下载；下载目录会保留一份 user-slot.bin 备份。",
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
        String details = (image.version == null || image.version.trim().isEmpty() ? "版本未知" :
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
        Button remove = compactButton("移出应用", view -> new AlertDialog.Builder(this)
                .setTitle("移出应用固件库？")
                .setMessage(image.displayName() + " 将从应用内移除，不影响已安装到设备的版本；Download/AI-Passport 中的备份文件仍会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    firmwareRepository.delete(image);
                    refreshFirmwareList();
                    refreshOfficialFirmwareList();
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
        refreshSetupState();
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

    private static String safeMessage(Throwable error) {
        String value = error == null ? "未知错误" : error.getMessage();
        return value == null || value.trim().isEmpty() ? "未知错误" : value.trim();
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
}
