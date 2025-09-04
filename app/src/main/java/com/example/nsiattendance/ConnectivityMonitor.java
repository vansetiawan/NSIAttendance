package com.example.nsiattendance;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

public final class ConnectivityMonitor {

    public interface Listener {
        void onAvailable();
        void onLost();
    }

    public static ConnectivityManager.NetworkCallback start(Context ctx, Listener listener) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { listener.onAvailable(); }
            @Override public void onLost(Network network) { listener.onLost(); }
        };
        cm.registerDefaultNetworkCallback(cb);

        // trigger state awal
        if (isOnline(cm)) listener.onAvailable(); else listener.onLost();
        return cb;
    }

    public static void stop(Context ctx, ConnectivityManager.NetworkCallback cb) {
        if (cb == null) return;
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        try { cm.unregisterNetworkCallback(cb); } catch (Exception ignored) {}
    }

    private static boolean isOnline(ConnectivityManager cm) {
        if (Build.VERSION.SDK_INT >= 23) {
            Network nw = cm.getActiveNetwork();
            if (nw == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(nw);
            return nc != null && (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        }
        return false;
    }
}
