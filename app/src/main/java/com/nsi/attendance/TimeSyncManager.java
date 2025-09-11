package com.nsi.attendance;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.toolbox.StringRequest;

import org.json.JSONObject;

public class TimeSyncManager {
    private static final String TAG = "TimeSync";
    private static final String PREF = "time_sync";
    private static final String KEY_OFFSET = "offset_ms";
    private static final String KEY_LAST_SYNC = "last_sync_ms";
    private static final String KEY_BASE_SERVER_AT_SYNC = "base_server_at_sync";
    private static final String KEY_BASE_ELAPSED_AT_SYNC = "base_elapsed_at_sync";

    private final SharedPreferences sp;
    private final Context ctx;

    public interface Callback { void onDone(); }

    public TimeSyncManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.sp  = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** Sinkron offset dgn NTP-like (kompensasi RTT) */
    public void sync(Callback cb) {
        final long t0 = System.currentTimeMillis();
        String url = MyConstants.API_URL + "time";
        StringRequest req = new StringRequest(Request.Method.GET, url, resp -> {
            try {
                final long t1 = System.currentTimeMillis();
                long rtt = Math.max(0, t1 - t0);
                JSONObject js = new JSONObject(resp).getJSONObject("data");
                long serverEpoch = js.getLong("epoch_ms");      // waktu server saat server mengirim respons

                // Perkiraan waktu server saat respons sampai di klien:
                long serverAtArrival = serverEpoch + rtt / 2L;
                long offset = serverAtArrival - t1;             // serverNow - System.currentTimeMillis()

                // Simpan juga basis monotonic supaya tahan jika user mengubah jam HP:
                long baseServerAtSync = serverAtArrival;
                long baseElapsedAtSync = SystemClock.elapsedRealtime();

                sp.edit()
                        .putLong(KEY_OFFSET, offset)
                        .putLong(KEY_LAST_SYNC, t1)
                        .putLong(KEY_BASE_SERVER_AT_SYNC, baseServerAtSync)
                        .putLong(KEY_BASE_ELAPSED_AT_SYNC, baseElapsedAtSync)
                        .apply();

                Log.d(TAG, "Sync ok, rtt=" + rtt + "ms, offset=" + offset + "ms");
            } catch (Exception e) {
                Log.e(TAG, "Parse time error", e);
            }
            if (cb != null) cb.onDone();
        }, err -> {
            Log.e(TAG, "Sync error: " + err);
            if (cb != null) cb.onDone();
        });

        req.setRetryPolicy(new DefaultRetryPolicy(6000, 0, 1f));
        VolleySingleton.getInstance(ctx).add(req);
    }

    /** Waktu server berbasis monotonic (tahan perubahan jam HP) */
    public long nowServerMs() {
        long baseServer = sp.getLong(KEY_BASE_SERVER_AT_SYNC, System.currentTimeMillis());
        long baseElapsed = sp.getLong(KEY_BASE_ELAPSED_AT_SYNC, SystemClock.elapsedRealtime());
        return baseServer + (SystemClock.elapsedRealtime() - baseElapsed);
    }

    /** Jika belum pernah sync, pakai offset sederhana (fallback) */
    public long nowServerMsFallback() {
        long offset = sp.getLong(KEY_OFFSET, 0);
        return System.currentTimeMillis() + offset;
    }

    public long lastSyncMs() { return sp.getLong(KEY_LAST_SYNC, 0); }
}
