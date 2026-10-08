package cn.folotoy.passportnotify;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    static final String PREFS = "allowlist";
    static final String PACKAGES = "packages";
    static final String OTP = "show_otp";
    private LinearLayout list;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 24, 28, 24);
        scroll.addView(root);
        setContentView(scroll);
        TextView intro = new TextView(this);
        intro.setText("AI Passport · 随身消息\n\n1. 在设备上打开固件（等待 PassportNotify）\n2. 授权蓝牙和通知使用权\n3. 勾选要显示通知的应用，点击连接\n4. 手机输入设备屏幕显示的六位配对码\n\n消息仅通过本机蓝牙发送，不上传服务器；通知会留在设备中，短按 OK 后才移除。");
        intro.setTextSize(17);
        root.addView(intro);
        button(root, "授予蓝牙权限", v -> requestBluetoothPermissions());
        button(root, "打开通知使用权设置", v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        button(root, "启动并连接 PassportNotify", v -> {
            if (Build.VERSION.SDK_INT >= 31 && (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED)) {
                requestBluetoothPermissions();
                return;
            }
            if (Build.VERSION.SDK_INT < 31 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestBluetoothPermissions();
                return;
            }
            BluetoothManager manager = getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            try {
                if (adapter == null || !adapter.isEnabled()) {
                    startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
                    return;
                }
            } catch (SecurityException ignored) { requestBluetoothPermissions(); return; }
            Intent intent = new Intent(this, BridgeService.class).setAction(BridgeService.START);
            startForegroundService(intent);
        });
        button(root, "发送测试消息到设备", v -> BridgeService.publish(2, "通知桥测试\n手机通知传输正常"));
        button(root, "停止连接", v -> stopService(new Intent(this, BridgeService.class)));
        CheckBox otp = new CheckBox(this);
        otp.setText("在设备屏幕显示短信内容 / 验证码（默认关闭）");
        otp.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(OTP, false));
        otp.setOnCheckedChangeListener((b, checked) -> getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(OTP, checked).apply());
        root.addView(otp);
        TextView apps = new TextView(this);
        apps.setText("\n仅转发勾选应用的通知（默认为空）：");
        apps.setTextSize(17);
        root.addView(apps);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        showApps();
    }
    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS}, 1);
        else if (Build.VERSION.SDK_INT >= 31) requestPermissions(new String[]{
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, 1);
        else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
    }
    private void button(LinearLayout root, String text, View.OnClickListener action) {
        Button button = new Button(this); button.setText(text); button.setOnClickListener(action); root.addView(button);
    }
    private void showApps() {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = getPackageManager().queryIntentActivities(launcher, 0);
        Collections.sort(apps, (a,b) -> a.loadLabel(getPackageManager()).toString().compareToIgnoreCase(b.loadLabel(getPackageManager()).toString()));
        Set<String> allowed = getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(PACKAGES, Collections.emptySet());
        List<String> seen = new ArrayList<>();
        for (ResolveInfo info : apps) {
            String pkg = info.activityInfo.packageName;
            if (seen.contains(pkg) || pkg.equals(getPackageName())) continue;
            seen.add(pkg);
            CheckBox box = new CheckBox(this);
            box.setText(getString(R.string.app_entry, info.loadLabel(getPackageManager()), pkg));
            box.setChecked(allowed.contains(pkg));
            box.setOnCheckedChangeListener((b, checked) -> {
                Set<String> copy = new java.util.HashSet<>(getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(PACKAGES, Collections.emptySet()));
                if (checked) copy.add(pkg); else copy.remove(pkg);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(PACKAGES, copy).apply();
            });
            list.addView(box);
        }
    }
}
