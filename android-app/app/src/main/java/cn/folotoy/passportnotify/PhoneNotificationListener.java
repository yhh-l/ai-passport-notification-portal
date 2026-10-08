package cn.folotoy.passportnotify;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.provider.Telephony;
import android.service.notification.StatusBarNotification;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Pattern;

public class PhoneNotificationListener extends NotificationListenerService {
    private static final Pattern CODE = Pattern.compile("(?<![0-9])[0-9]{4,8}(?![0-9])");
    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        Set<String> allowed = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getStringSet(MainActivity.PACKAGES, Collections.emptySet());
        if (!allowed.contains(pkg)) return;
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        String title = clean(n.extras.getCharSequence(Notification.EXTRA_TITLE));
        CharSequence bodyRaw = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (bodyRaw == null) bodyRaw = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        String body = clean(bodyRaw);
        if (title.isEmpty() && body.isEmpty()) return;
        String defaultSms = Telephony.Sms.getDefaultSmsPackage(this);
        boolean sms = pkg.equals(defaultSms) || pkg.equals("com.google.android.apps.messaging")
                || pkg.equals("com.samsung.android.messaging") || pkg.equals("com.android.mms");
        boolean showOtp = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getBoolean(MainActivity.OTP, false);
        String text = sms && !showOtp ? "短信 · 新消息（内容已隐藏）" : title + (body.isEmpty() ? "" : "\n" + body);
        if (!showOtp && CODE.matcher(text).find()) text = "新消息（数字内容已隐藏）";
        // Nothing leaves the phone unless the foreground bridge is explicitly running.
        BridgeService.publish(sms ? 1 : 2, text);
    }
    private static String clean(CharSequence value) {
        if (value == null) return "";
        return value.toString().replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").trim();
    }
}
