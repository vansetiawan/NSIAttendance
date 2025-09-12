package com.nsi.attendance;

import android.content.Context;
import android.provider.Settings;
import android.os.Build;

import java.security.MessageDigest;

public final class DeviceUtils {
    private DeviceUtils() {}

    /** Hasil 20 karakter (hex) agar pas dengan VARCHAR(20) unique_code */
    public static String getUniqueCode(Context ctx) {
        String androidId = Settings.Secure.getString(
                ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        String src = (androidId == null ? "" : androidId)
                + "|" + Build.MANUFACTURER + "|" + Build.MODEL;
        return md5(src).substring(0, 20).toUpperCase();
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "00000000000000000000";
        }
    }
}
