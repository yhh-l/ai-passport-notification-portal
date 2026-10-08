package cn.folotoy.passportnotify;

import android.app.Notification;
import android.content.pm.PackageManager;
import android.provider.Telephony;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class PhoneNotificationListener extends NotificationListenerService {
    private static final Pattern CODE = Pattern.compile("(?<![0-9])[0-9]{4,8}(?![0-9])");
    private final Map<String, String> lastForwarded = new ConcurrentHashMap<>();

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        Set<String> allowed = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getStringSet(MainActivity.PACKAGES, Collections.emptySet());
        if (!allowed.contains(pkg)) return;

        Notification n = sbn.getNotification();
        if (n == null || n.extras == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0
                || (n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
        String title = clean(n.extras.getCharSequence(Notification.EXTRA_TITLE));
        CharSequence bodyRaw = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (bodyRaw == null) bodyRaw = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        String body = clean(bodyRaw);
        if (title.isEmpty() && body.isEmpty()) return;

        String defaultSms = Telephony.Sms.getDefaultSmsPackage(this);
        boolean sms = pkg.equals(defaultSms) || pkg.equals("com.google.android.apps.messaging")
                || pkg.equals("com.samsung.android.messaging") || pkg.equals("com.android.mms");
        boolean showOtp = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getBoolean(MainActivity.OTP, false);
        String app = appLabel(pkg);
        String heading = title.isEmpty() || title.equals(app) ? app : app + " · " + title;
        String text;
        if (sms && !showOtp) {
            text = heading + "\n新短信（内容已隐藏）";
        } else {
            text = heading + (body.isEmpty() || body.equals(title) ? "" : "\n" + body);
            // Preserve context while hiding only likely verification codes. The
            // previous behavior replaced the entire notification text.
            if (!showOtp) text = CODE.matcher(text).replaceAll("****");
        }

        String fingerprint = (sms ? "1\n" : "2\n") + text;
        if (fingerprint.equals(lastForwarded.get(sbn.getKey()))) return;
        // Nothing leaves the phone unless the foreground bridge is connected.
        if (BridgeService.publish(sms ? 1 : 2, text)) {
            lastForwarded.put(sbn.getKey(), fingerprint);
        }
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        lastForwarded.remove(sbn.getKey());
    }

    private String appLabel(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return pkg;
        }
    }

    private static String clean(CharSequence value) {
        if (value == null) return "";
        return value.toString().replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").trim();
    }
}
