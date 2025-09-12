package com.nsi.attendance;

import android.content.Context;
import android.location.Location;
import android.os.Build;
import android.provider.Settings;

public final class LocationGuard {

    private LocationGuard() {}

    /** True kalau location berasal dari mock provider / disimulasikan. */
    public static boolean isLocationMocked(Location loc) {
        if (loc == null) return true;
        boolean mocked = false;
        if (Build.VERSION.SDK_INT >= 31) { // Android 12+
            mocked = loc.isMock();
        }
        // isFromMockProvider ada sejak API 18
        mocked = mocked || loc.isFromMockProvider();
        return mocked;
    }

    /** Developer options aktif? (bukan berarti mock, tapi indikator risiko) */
    public static boolean isDevOptionsOn(Context ctx) {
        try {
            return Settings.Global.getInt(
                    ctx.getContentResolver(),
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Akurasi terlalu buruk? (misal > 100m kita tolak) */
    public static boolean isAccuracyBad(Location loc, float maxMeter) {
        return loc == null || !loc.hasAccuracy() || loc.getAccuracy() > maxMeter;
    }

    /** Kecepatan antar dua titik tidak masuk akal (mis. > 150 km/jam) */
    public static boolean isSpeedSuspicious(Location prev, Location curr, float kmhLimit) {
        if (prev == null || curr == null) return false;
        long dtMs = Math.max(1, curr.getTime() - prev.getTime());
        double distM = haversineMeters(prev.getLatitude(), prev.getLongitude(),
                curr.getLatitude(), curr.getLongitude());
        double mps = distM / (dtMs / 1000.0);
        double kmh = mps * 3.6;
        return kmh > kmhLimit;
    }

    /** Loncatan jarak terlalu besar dalam waktu singkat (mis. > 1 km dalam < 60 dtk) */
    public static boolean isJumpSuspicious(Location prev, Location curr, double meterJump, long secsWindow) {
        if (prev == null || curr == null) return false;
        double distM = haversineMeters(prev.getLatitude(), prev.getLongitude(),
                curr.getLatitude(), curr.getLongitude());
        long dt = Math.max(1, (curr.getTime() - prev.getTime()) / 1000L);
        return distM > meterJump && dt <= secsWindow;
    }

    /** Haversine meter. */
    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2) +
                Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon/2)*Math.sin(dLon/2);
        return 2 * R * Math.asin(Math.sqrt(a));
    }
}
