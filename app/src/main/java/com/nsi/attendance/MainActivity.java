package com.nsi.attendance;

import static com.nsi.attendance.MyConstants.API_URL;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.Manifest;
import android.os.Build;


import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.android.volley.BuildConfig;
import com.android.volley.DefaultRetryPolicy;
import com.android.volley.NoConnectionError;
import com.android.volley.Request;
import com.android.volley.TimeoutError;
import com.android.volley.toolbox.StringRequest;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class MainActivity extends AppCompatActivity {

    private TextView tvTime, tvDate, tvName, tvHello, tvStatIn, tvStatOut, tvStatTotal, tvVersion;
    private MaterialButton btnAction; // satu tombol untuk IN/OUT
    private View root;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback netCb;
    private Snackbar offlineBar;

    private SessionManager session;
    private String lastGreeting = "";

    // state harian
    private enum DayState { BEFORE_IN, AFTER_IN, DONE }
    private DayState state = DayState.BEFORE_IN;
    private FusedLocationProviderClient flp;     // sudah kamu punya
    private Double lastLat = null, lastLng = null;
    private Integer lastAcc = null;
    private LocationManager lm;
    private LocationListener lmListener;
    private android.os.Handler locTimeoutHandler;
    private static final int REQ_LOC = 77;
    private static final long LOC_TIMEOUT_MS = 10000; // 10 dtk timeout fallback
    private boolean isSubmitting = false;
    private static final String REQ_TAG_CHECK = "req_check";
    private Location lastFix = null;
    private boolean lastFixMock = false;
    private @Nullable String pendingEndpoint = null;

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            TimeSyncManager tsm = new TimeSyncManager(MainActivity.this);
            long nowMs = tsm.nowServerMs();

            Date d = new Date(nowMs);
            SimpleDateFormat tf = new SimpleDateFormat("HH:mm:ss", new Locale("id","ID"));
            SimpleDateFormat df = new SimpleDateFormat("EEEE • d MMMM yyyy", new Locale("id","ID"));
            tf.setTimeZone(TimeZone.getTimeZone("Asia/Jakarta"));
            df.setTimeZone(TimeZone.getTimeZone("Asia/Jakarta"));
            tvTime.setText(tf.format(d));
            tvDate.setText(df.format(d));

            updateGreeting();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        session     = new SessionManager(this);
        root        = findViewById(R.id.root);

        tvTime      = findViewById(R.id.tvTime);
        tvDate      = findViewById(R.id.tvDate);
        tvName      = findViewById(R.id.tvName);
        tvHello     = findViewById(R.id.tvHello);
        tvStatIn    = findViewById(R.id.tvStatIn);
        tvStatOut   = findViewById(R.id.tvStatOut);
        tvStatTotal = findViewById(R.id.tvStatTotal);
        btnAction   = findViewById(R.id.btnCheckIn); // pakai id yg sudah ada
        tvVersion = findViewById(R.id.tvVersion);
        if (tvVersion != null) tvVersion.setText(getVersionLabel());
        flp = LocationServices.getFusedLocationProviderClient(this);
        lm  = (LocationManager) getSystemService(LOCATION_SERVICE);
        locTimeoutHandler = new android.os.Handler(Looper.getMainLooper());


        // set nama
        String rawName = session.getName();
        if (rawName == null || rawName.trim().isEmpty()) rawName = session.getUsername();
        tvName.setText(NameUtils.toProperName(rawName));

        // klik tombol → dinamis
        btnAction.setOnClickListener(v -> onActionButtonClicked());
        findViewById(R.id.btnHistory).setOnClickListener(v -> showHistoryLast30DaysDialog());
        findViewById(R.id.fabHistory).setOnClickListener(v -> showHistoryDialog(null));

    }

    private boolean hasPlayServices() {
        int r = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this);
        return r == ConnectionResult.SUCCESS;
    }


    @Override protected void onStart() {
        super.onStart();
        netCb = ConnectivityMonitor.start(this, new ConnectivityMonitor.Listener() {
            @Override public void onAvailable() { hideOfflineBar(); }
            @Override public void onLost() { showOfflineBar(); }
        });
    }

    @Override protected void onStop() {
        ConnectivityMonitor.stop(this, netCb);
        netCb = null;
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(clockTick);

        // refresh nama (kalau ada update profil)
        String rawName = session.getName();
        if (rawName == null || rawName.trim().isEmpty()) rawName = session.getUsername();
        tvName.setText(NameUtils.toProperName(rawName));

        // ambil status hari ini dari server → set label tombol
        fetchAttendanceStatus();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(clockTick);
        stopLMUpdates();
        super.onPause();
    }

    /* ---------------- UI helpers ---------------- */

    private void updateGreeting() {
        String greet = getGreetingWIBServer();
        if (!greet.equals(lastGreeting)) {
            tvHello.setText(greet);
            lastGreeting = greet;
        }
    }

    @SuppressLint("MissingPermission")
    private void fetchLocationThenSubmitCompat(String endpoint) {
        if (!ensureLocationPermission()) return;

        if (hasPlayServices()) {
            // Coba Fused dulu, tapi siapkan timeout untuk fallback
            CancellationTokenSource cts = new CancellationTokenSource();
            final boolean[] delivered = { false };

            locTimeoutHandler.postDelayed(() -> {
                if (!delivered[0]) {
                    // fallback
                    requestSingleLocationWithLM(endpoint);
                }
            }, 2500); // kasih 2.5 detik dulu ke Fused

            flp.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                    .addOnSuccessListener(loc -> {
                        delivered[0] = true;
                        if (loc != null) {
                            String err = validateLocation(loc);
                            if (err != null) {
                                Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                                finishSubmitting();
                                return;
                            }
                            // set state lokasi terakhir
                            lastFix = loc;
                            lastLat = loc.getLatitude();
                            lastLng = loc.getLongitude();
                            lastAcc = loc.hasAccuracy() ? (int) loc.getAccuracy() : 0;
                            doCheck(endpoint);
                        } else {
                            requestSingleLocationWithLM(endpoint);
                        }
                    })
                    .addOnFailureListener(e -> {
                        delivered[0] = true;
                        requestSingleLocationWithLM(endpoint);
                    });
        } else {
            // Tidak ada Play Services → langsung fallback
            requestSingleLocationWithLM(endpoint);
        }
    }

    @SuppressLint("MissingPermission")
    private void requestSingleLocationWithLM(String endpoint) {
        if (!ensureLocationPermission()) return;

        // 5a. Coba last known location dulu
        Location best = null;
        for (String p : new String[]{ LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER }) {
            if (lm.isProviderEnabled(p)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null) {
                    if (best == null) best = l;
                    else if (l.getTime() > best.getTime()) best = l;
                }
            }
        }
        if (best != null && (System.currentTimeMillis() - best.getTime()) <= 2 * 60_000) {
            String err = validateLocation(best);
            if (err != null) {
                Toast.makeText(MainActivity.this, err, Toast.LENGTH_LONG).show();
                finishSubmitting();
                return;
            }
            lastFix = best;
            lastLat = best.getLatitude();
            lastLng = best.getLongitude();
            lastAcc = best.hasAccuracy() ? (int) best.getAccuracy() : 0;
            doCheck(endpoint);
            return;
        }


        // 5b. Minta update sekali dari provider yang tersedia
        Criteria c = new Criteria();
        c.setAccuracy(Criteria.ACCURACY_FINE);     // prefer GPS
        c.setPowerRequirement(Criteria.POWER_HIGH);
        final String provider = lm.getBestProvider(c, true);

        lmListener = new LocationListener() {
            @Override public void onLocationChanged(Location loc) {
                stopLMUpdates();
                if (loc != null) {
                    String err = validateLocation(loc);
                    if (err != null) {
                        Toast.makeText(MainActivity.this, err, Toast.LENGTH_LONG).show();
                        finishSubmitting();
                        return;
                    }
                    lastFix = loc;
                    lastLat = loc.getLatitude();
                    lastLng = loc.getLongitude();
                    lastAcc = loc.hasAccuracy() ? (int) loc.getAccuracy() : 0;
                    doCheck(endpoint);
                } else {
                    Toast.makeText(MainActivity.this, "Lokasi belum tersedia.", Toast.LENGTH_LONG).show();
                    finishSubmitting();
                }

            }
            @Override public void onStatusChanged(String s, int i, Bundle b) {}
            @Override public void onProviderEnabled(String s) {}
            @Override public void onProviderDisabled(String s) {}
        };

        // request dari GPS dan Network sekaligus (yang datang duluan dipakai)
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, lmListener, Looper.getMainLooper());
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, lmListener, Looper.getMainLooper());
        } catch (SecurityException ignored) {}

        // Timeout: hentikan kalau nggak dapat juga
        locTimeoutHandler.postDelayed(() -> {
            if (lmListener != null) {
                stopLMUpdates();
                Toast.makeText(MainActivity.this, "Gagal mendapatkan lokasi. Coba lagi.", Toast.LENGTH_LONG).show();
                finishSubmitting();
            }
        }, LOC_TIMEOUT_MS);
    }

    private void stopLMUpdates() {
        try { if (lmListener != null) lm.removeUpdates(lmListener); } catch (Exception ignored) {}
        lmListener = null;
    }

    private String getGreetingWIBServer() {
        long nowMs = new TimeSyncManager(this).nowServerMs();
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta"));
        cal.setTimeInMillis(nowMs);
        int h = cal.get(Calendar.HOUR_OF_DAY);
        if (h >= 4 && h <= 10)  return "Selamat pagi, ";
        if (h >= 11 && h <= 14) return "Selamat siang, ";
        if (h >= 15 && h <= 18) return "Selamat sore, ";
        return "Selamat malam, ";
    }

    private void showOfflineBar() {
        if (offlineBar == null) {
            offlineBar = Snackbar.make(root,
                            "Perangkat Anda tidak mempunyai koneksi internet. Aktifkan internet Anda untuk melakukan absensi.",
                            Snackbar.LENGTH_INDEFINITE)
                    .setAction("Pengaturan", v ->
                            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)));
        }
        if (!offlineBar.isShown()) offlineBar.show();
    }

    private void hideOfflineBar() {
        if (offlineBar != null && offlineBar.isShown()) offlineBar.dismiss();
    }

    private void applyState() {
        switch (state) {
            case BEFORE_IN:
                btnAction.setText("Absen Masuk");
                btnAction.setEnabled(true);
                break;
            case AFTER_IN:
                btnAction.setText("Absen Pulang");
                btnAction.setEnabled(true);
                break;
            case DONE:
                btnAction.setText("Selesai");
                btnAction.setEnabled(false);
                break;
        }
    }

    private void onActionButtonClicked() {
        if (!NetworkUtil.isOnline(this)) {
            Snackbar.make(root, "Tidak ada koneksi internet. Aktifkan internet untuk absen.", Snackbar.LENGTH_LONG).show();
            return;
        }
        if (isSubmitting) return;

        String ep = (state == DayState.BEFORE_IN) ? "check-in" : "check-out";

        // Jika belum ada izin → simpan endpoint, minta izin, JANGAN disable tombol.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingEndpoint = ep;
            ActivityCompat.requestPermissions(this,
                    new String[]{ Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION },
                    REQ_LOC);
            return;
        }

        // Sudah ada izin → lanjut proses
        isSubmitting = true;
        btnAction.setEnabled(false);
        btnAction.setText("Memproses…");
        fetchLocationThenSubmitCompat(ep);
    }


    private void finishSubmitting() {
        isSubmitting = false;
        btnAction.setEnabled(true);
        applyState();                             // kembalikan label Check In/Out
    }

    private void fetchAttendanceStatus() {
        String url = API_URL + "attendance-status";
        StringRequest req = new StringRequest(Request.Method.POST, url, resp -> {
            try {
                JSONObject js = new JSONObject(resp);
                if (!js.has("data")) return;
                JSONObject d = js.getJSONObject("data");

                String in  = d.isNull("check_in")  ? null : d.getString("check_in");
                String out = d.isNull("check_out") ? null : d.getString("check_out");

                tvStatIn.setText(in  == null ? "--:--" : hhmm(in));
                tvStatOut.setText(out == null ? "--:--" : hhmm(out));
                tvStatTotal.setText(calcTotal(in, out));

                if (in == null) state = DayState.BEFORE_IN;
                else state = DayState.AFTER_IN;

                applyState();
            } catch (Exception ignore) {}
        }, err -> {}) {
            @Override protected Map<String, String> getParams() {
                Map<String, String> m = new HashMap<>();
                m.put("id", String.valueOf(session.getUserId()));
                m.put("unique_code", session.getUnique());
                return m;
            }
        };
        VolleySingleton.getInstance(this).add(req);
    }

    private void doCheck(String endpoint) {
        String url = API_URL + endpoint;

        VolleySingleton.getInstance(this).cancelAll(REQ_TAG_CHECK);

        StringRequest req = new StringRequest(Request.Method.POST, url, resp -> {
            try {
                JSONObject js = new JSONObject(resp);
                if (js.has("data")) {
                    JSONObject d = js.getJSONObject("data");
                    if ("check-in".equals(endpoint)) {
                        String t = d.optString("check_in","--:--");
                        tvStatIn.setText(hhmm(t));
                        state = DayState.AFTER_IN;
                        Toast.makeText(this, "Check-in berhasil " + hhmm(t), Toast.LENGTH_SHORT).show();
                    } else {
                        String t = d.optString("check_out","--:--");
                        tvStatOut.setText(hhmm(t));
                        state = DayState.AFTER_IN;
                        Toast.makeText(this, "Check-out berhasil " + hhmm(t), Toast.LENGTH_SHORT).show();
                    }
                    tvStatTotal.setText(calcTotal(tvStatIn.getText().toString(), tvStatOut.getText().toString()));
                    applyState();
                } else {
                    JSONObject err = js.optJSONObject("error");
                    String msg = err != null ? err.optString("message","Gagal proses") : "Gagal proses";
                    int retry = err != null ? err.optInt("retry_after_sec", 0) : 0;
                    Toast.makeText(this, msg + (retry>0? (" ("+retry+" dtk)"):""), Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Parse error", Toast.LENGTH_LONG).show();
            } finally {
                finishSubmitting();
            }
        }, err -> {
            String msg = "Jaringan/Server error";
            int retry = 0;
            try {
                if (err != null && err.networkResponse != null && err.networkResponse.data != null) {
                    String body = new String(err.networkResponse.data, java.nio.charset.StandardCharsets.UTF_8);
                    JSONObject j = new JSONObject(body);
                    JSONObject e = j.optJSONObject("error");
                    if (e != null) {
                        msg = e.optString("message", msg);
                        retry = e.optInt("retry_after_sec", 0);
                    }
                }
            } catch (Exception ignore) {}
            Toast.makeText(this, msg + (retry>0 ? " ("+retry+" dtk)" : ""), Toast.LENGTH_LONG).show();
            finishSubmitting();
        }) {
            @Override protected Map<String, String> getParams() {
                String requestId = java.util.UUID.randomUUID().toString();
                Map<String, String> m = new HashMap<>();
                m.put("request_id", requestId);
                m.put("id", String.valueOf(session.getUserId()));
                m.put("unique_code", session.getUnique());
                m.put("area_code", session.getAreaCode());
                m.put("is_mock", lastFixMock ? "1" : "0");
                if (lastLat != null && lastLng != null) {
                    m.put("lat", String.valueOf(lastLat));
                    m.put("lng", String.valueOf(lastLng));
                    if (lastAcc != null) m.put("acc", String.valueOf(lastAcc));
                }
                return m;
            }
        };

        req.setTag(REQ_TAG_CHECK);
        req.setShouldCache(false);
        req.setRetryPolicy(new DefaultRetryPolicy(15000, 0, 1f));

        VolleySingleton.getInstance(this).add(req);
    }

    private String hhmm(String s) {
        if (s == null) return "--:--";
        if (s.length() >= 16 && s.charAt(4) == '-') return s.substring(11,16);
        if (s.length() >= 5) return s.substring(0,5);
        return s;
    }

    private String calcTotal(String inStr, String outStr) {
        try {
            if (inStr == null || outStr == null) return "--:--";
            String in  = inStr.length() > 5  ? inStr.substring(11,19) : inStr + ":00";
            String out = outStr.length() > 5 ? outStr.substring(11,19) : outStr + ":00";
            SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", new Locale("id","ID"));
            f.setTimeZone(TimeZone.getTimeZone("Asia/Jakarta"));
            long tIn  = f.parse(in).getTime();
            long tOut = f.parse(out).getTime();
            long diff = Math.max(0, tOut - tIn);
            long h = diff / 3_600_000L;
            long m = (diff / 60_000L) % 60L;
            return String.format(Locale.US, "%02d:%02d", h, m);
        } catch (Exception e) {
            return "--:--";
        }
    }

    private void showVolleyError(com.android.volley.VolleyError err) {
        String msg = "Jaringan/Server error";
        if (err instanceof TimeoutError) {
            msg = "Timeout menghubungi server";
        } else if (err instanceof NoConnectionError) {
            msg = "Tidak ada koneksi ke server";
        } else if (err != null && err.networkResponse != null) {
            int sc = err.networkResponse.statusCode;
            String body = "";
            try {
                body = new String(err.networkResponse.data, StandardCharsets.UTF_8);
                Log.e("VOLLEY", "HTTP " + sc + " • " + body);
                // jika server mengembalikan JSON {"error":{"message": "..."}}
                JSONObject j = new JSONObject(body);
                if (j.optJSONObject("error") != null) {
                    msg = j.optJSONObject("error").optString("message", msg);
                } else if (j.has("message")) {
                    msg = j.optString("message", msg);
                }
            } catch (Exception ignore) {
            }
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private boolean ensureLocationPermission() {
        boolean fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQ_LOC);
            return false;
        }
        return true;
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOC) {
            boolean granted = true;
            if (grantResults == null || grantResults.length == 0) {
                granted = false;
            } else {
                for (int g : grantResults) {
                    if (g != PackageManager.PERMISSION_GRANTED) { granted = false; break; }
                }
            }

            if (granted) {
                // auto-continue
                String ep = (pendingEndpoint != null) ? pendingEndpoint
                        : (state == DayState.BEFORE_IN ? "check-in" : "check-out");
                pendingEndpoint = null;

                isSubmitting = true;
                btnAction.setEnabled(false);
                btnAction.setText("Memproses…");
                fetchLocationThenSubmitCompat(ep);
            } else {
                // reset UI
                isSubmitting = false;
                applyState();
                Toast.makeText(this, "Izin lokasi diperlukan untuk absen.", Toast.LENGTH_LONG).show();
            }
        }
    }


    private void showTodayHistoryDialog() {
        String url = API_URL + "attendance-history";

        StringRequest req = new StringRequest(Request.Method.POST, url, resp -> {
            try {
                JSONObject js = new JSONObject(resp);
                JSONObject d = js.getJSONObject("data");
                String date = d.getString("date");

                JSONArray items = d.getJSONArray("items");
                StringBuilder sb = new StringBuilder();
                sb.append("Tanggal: ").append(date).append("\n\n");
                for (int i=0; i<items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    String t = it.getString("time");
                    String type = it.getString("type");
                    String area = it.optString("area", "-");
                    String src  = it.optString("source","-");
                    sb.append(type).append("  ").append(t)
                            .append("  (").append(area).append(", ").append(src).append(")\n");
                }

                JSONObject sum = d.getJSONObject("summary");
                sb.append("\nRingkasan:\n");
                sb.append("Masuk pertama : ").append(sum.isNull("first_in") ? "-" : sum.getString("first_in")).append("\n");
                sb.append("Pulang terakhir: ").append(sum.isNull("last_out") ? "-" : sum.getString("last_out")).append("\n");
                sb.append("Total          : ").append(sum.getString("total")).append("\n");

                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Riwayat Hari Ini")
                        .setMessage(sb.toString())
                        .setPositiveButton("Tutup", null)
                        .show();

            } catch (Exception e) {
                Toast.makeText(this, "Parse error", Toast.LENGTH_LONG).show();
            }
        }, err -> {
            String msg = "Jaringan/Server error";
            try {
                if (err != null && err.networkResponse != null && err.networkResponse.data != null) {
                    String body = new String(err.networkResponse.data, java.nio.charset.StandardCharsets.UTF_8);
                    JSONObject j = new JSONObject(body);
                    JSONObject e = j.optJSONObject("error");
                    if (e != null) msg = e.optString("message", msg);
                }
            } catch (Exception ignore) {}
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        }) {
            @Override protected Map<String, String> getParams() {
                Map<String, String> m = new HashMap<>();
                m.put("id", String.valueOf(session.getUserId()));
                m.put("unique_code", session.getUnique());
                m.put("date", new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .format(new java.util.Date())); // atau kirim tanggal yang dipilih user
                // kalau mau ambil jejak geofence:
                // m.put("include_geo", "1");
                return m;
            }
        };
        req.setShouldCache(false);
        VolleySingleton.getInstance(this).add(req);
    }

    // ===== Model item riwayat =====
    static class HistoryItem {
        int transactionId;
        String time;   // "HH:mm:ss"
        String type;   // "IN" / "OUT"
        String area;
        String source; // "wdms" / "mobile"
    }

    // ===== Adapter sederhana untuk ListView =====
    class HistoryItemAdapter extends android.widget.ArrayAdapter<HistoryItem> {
        LayoutInflater inflater;
        HistoryItemAdapter(Context ctx, List<HistoryItem> data) {
            super(ctx, 0, data);
            inflater = LayoutInflater.from(ctx);
        }
        @Override public View getView(int pos, View cv, ViewGroup parent) {
            if (cv == null) cv = inflater.inflate(R.layout.item_history, parent, false);
            HistoryItem it = getItem(pos);
            TextView tvTime = cv.findViewById(R.id.tvTime);
            TextView tvType = cv.findViewById(R.id.tvType);
            TextView tvMeta = cv.findViewById(R.id.tvMeta);

            tvTime.setText(it.time);
            tvType.setText(it.type);

            // Warna beda untuk IN/OUT (optional)
            tvType.setTextColor("IN".equals(it.type) ? 0xFF2E7D32 : 0xFFC62828);

            tvMeta.setText((it.area == null || it.area.isEmpty() ? "-" : it.area)
                    + " • " + (it.source == null ? "-" : it.source));
            return cv;
        }
    }

    // ===== Tampilkan dialog riwayat hari yang dipilih (atau hari ini jika null) =====
    private void showHistoryDialog(@Nullable String dateYmd) {
        String date = (dateYmd == null)
                ? new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date())
                : dateYmd;

        View view = getLayoutInflater().inflate(R.layout.dialog_history, null);
        TextView tvDate = view.findViewById(R.id.tvHistDate);
        TextView tvSum  = view.findViewById(R.id.tvHistSummary);
        TextView tvEmpty= view.findViewById(R.id.tvEmpty);
        ListView lv     = view.findViewById(R.id.lvHistory);
        View progress   = view.findViewById(R.id.progress);

        tvDate.setText("Tanggal: " + date);

        List<HistoryItem> data = new ArrayList<>();
        HistoryItemAdapter adapter = new HistoryItemAdapter(this, data);
        lv.setAdapter(adapter);
        lv.setEmptyView(tvEmpty);

        androidx.appcompat.app.AlertDialog dlg = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Riwayat Absen")
                .setView(view)
                .setPositiveButton("Tutup", null)
                .create();
        dlg.show();

        // ---- panggil API ----
        String url = API_URL + "attendance-history";
        progress.setVisibility(View.VISIBLE);

        StringRequest req = new StringRequest(Request.Method.POST, url, resp -> {
            progress.setVisibility(View.GONE);
            try {
                JSONObject js = new JSONObject(resp);
                if (!js.has("data")) {
                    tvSum.setText("Gagal memuat data.");
                    return;
                }
                JSONObject d = js.getJSONObject("data");
                JSONArray items = d.getJSONArray("items");

                data.clear();
                for (int i=0;i<items.length();i++){
                    JSONObject it = items.getJSONObject(i);
                    HistoryItem hi = new HistoryItem();
                    hi.transactionId = it.optInt("transaction_id");
                    hi.time   = it.optString("time","--:--");
                    hi.type   = it.optString("type","?");
                    hi.area   = it.optString("area","");
                    hi.source = it.optString("source","");
                    data.add(hi);
                }
                adapter.notifyDataSetChanged();

                JSONObject sum = d.getJSONObject("summary");
                String firstIn = sum.isNull("first_in") ? "-" : sum.getString("first_in");
                String lastOut = sum.isNull("last_out") ? "-" : sum.getString("last_out");
                String total   = sum.optString("total","--:--");
                int inCount    = sum.optInt("in_count",0);
                int outCount   = sum.optInt("out_count",0);

                tvSum.setText(
                        "Masuk pertama : " + firstIn + "\n" +
                                "Pulang terakhir: " + lastOut + "\n" +
                                "IN/OUT         : " + inCount + "/" + outCount + "\n" +
                                "Total          : " + total
                );

            } catch (Exception e) {
                tvSum.setText("Parse error");
            }
        }, err -> {
            progress.setVisibility(View.GONE);
            String msg = "Jaringan/Server error";
            try {
                if (err != null && err.networkResponse != null && err.networkResponse.data != null) {
                    String body = new String(err.networkResponse.data, java.nio.charset.StandardCharsets.UTF_8);
                    JSONObject j = new JSONObject(body);
                    JSONObject e = j.optJSONObject("error");
                    if (e != null) msg = e.optString("message", msg);
                }
            } catch (Exception ignore) {}
            tvSum.setText(msg);
        }) {
            @Override protected Map<String, String> getParams() {
                Map<String, String> m = new HashMap<>();
                m.put("id", String.valueOf(session.getUserId()));
                m.put("unique_code", session.getUnique());
                m.put("date", date);
                // m.put("include_geo","1"); // kalau mau kirim jejak geofence
                return m;
            }
        };
        req.setShouldCache(false);
        VolleySingleton.getInstance(this).add(req);
    }

    // ===== Model ringkasan hari =====
    static class DayItem {
        String date;     // YYYY-MM-DD
        String firstIn;  // "HH:mm" or null
        String lastOut;  // "HH:mm" or null
        String total;    // "HH:MM" or "--:--"
        int inCount;
        int outCount;
    }

    // ===== Adapter ListView =====
    class DayItemAdapter extends android.widget.ArrayAdapter<DayItem> {
        LayoutInflater inflater;
        DayItemAdapter(Context ctx, List<DayItem> data) {
            super(ctx, 0, data);
            inflater = LayoutInflater.from(ctx);
        }
        @Override public View getView(int pos, View cv, ViewGroup parent) {
            if (cv == null) cv = inflater.inflate(R.layout.item_day_row, parent, false);
            DayItem it = getItem(pos);
            TextView tvDate = cv.findViewById(R.id.tvDayDate);
            TextView tvMeta = cv.findViewById(R.id.tvDayMeta);

            tvDate.setText(it.date);
            String fi = (it.firstIn == null ? "-" : it.firstIn);
            String lo = (it.lastOut == null ? "-" : it.lastOut);
            tvMeta.setText("IN " + fi + " • OUT " + lo + " • Total " + it.total + " • " + it.inCount + "/" + it.outCount);
            return cv;
        }
    }

    // ===== Dialog: daftar 30 hari terakhir (paginated) =====
    private void showHistoryLast30DaysDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_history_days, null);
        TextView tvNotice = view.findViewById(R.id.tvNotice);
        ListView lv       = view.findViewById(R.id.lvDays);
        TextView tvEmpty  = view.findViewById(R.id.tvEmptyDays);
        Button btnMore    = view.findViewById(R.id.btnMore);

        List<DayItem> data = new ArrayList<>();
        DayItemAdapter adapter = new DayItemAdapter(this, data);
        lv.setAdapter(adapter);
        lv.setEmptyView(tvEmpty);

        final String[] nextCursor = { null }; // mutable holder
        final boolean[] loading = { false };

        androidx.appcompat.app.AlertDialog dlg = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Riwayat (30 hari terakhir)")
                .setView(view)
                .setPositiveButton("Tutup", null)
                .create();
        dlg.show();

        // loader
        Runnable loadPage = new Runnable() {
            @Override public void run() {
                if (loading[0]) return;
                loading[0] = true;
                btnMore.setEnabled(false);

                String url = API_URL + "attendance-history-days";
                StringRequest req = new StringRequest(Request.Method.POST, url, resp -> {
                    loading[0] = false;
                    btnMore.setEnabled(true);
                    try {
                        JSONObject js = new JSONObject(resp);
                        JSONObject d  = js.getJSONObject("data");

                        tvNotice.setText(d.optString("notice","Menampilkan 30 hari terakhir"));

                        JSONArray arr = d.getJSONArray("days");
                        for (int i=0;i<arr.length();i++) {
                            JSONObject o = arr.getJSONObject(i);
                            DayItem di = new DayItem();
                            di.date     = o.getString("date");
                            di.firstIn  = o.isNull("first_in") ? null : o.getString("first_in");
                            di.lastOut  = o.isNull("last_out") ? null : o.getString("last_out");
                            di.total    = o.optString("total","--:--");
                            di.inCount  = o.optInt("in_count",0);
                            di.outCount = o.optInt("out_count",0);
                            data.add(di);
                        }
                        adapter.notifyDataSetChanged();

                        String nc = d.isNull("next_cursor") ? null : d.getString("next_cursor");
                        nextCursor[0] = nc;
                        btnMore.setVisibility(nc == null ? View.GONE : View.VISIBLE);

                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Parse error", Toast.LENGTH_LONG).show();
                    }
                }, err -> {
                    loading[0] = false;
                    btnMore.setEnabled(true);
                    String msg = "Jaringan/Server error";
                    try {
                        if (err != null && err.networkResponse != null && err.networkResponse.data != null) {
                            String body = new String(err.networkResponse.data, java.nio.charset.StandardCharsets.UTF_8);
                            JSONObject j = new JSONObject(body);
                            JSONObject e = j.optJSONObject("error");
                            if (e != null) msg = e.optString("message", msg);
                        }
                    } catch (Exception ignore) {}
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                }) {
                    @Override protected Map<String, String> getParams() {
                        Map<String, String> m = new HashMap<>();
                        m.put("id", String.valueOf(session.getUserId()));
                        m.put("unique_code", session.getUnique());
                        m.put("limit", "10"); // ganti sesuai selera (maks 30 di server)
                        if (nextCursor[0] != null) m.put("cursor", nextCursor[0]);
                        return m;
                    }
                };
                req.setShouldCache(false);
                VolleySingleton.getInstance(MainActivity.this).add(req);
            }
        };

        // First load
        loadPage.run();

        // Load more
        btnMore.setOnClickListener(v -> loadPage.run());

        // Klik item hari → buka detail harian (endpoint attendance-history yang sudah ada)
        lv.setOnItemClickListener((parent, view1, position, id) -> {
            DayItem di = data.get(position);
            showHistoryDialog(di.date); // fungsi yang sebelumnya sudah kita buat
        });
    }

    /** Deteksi lokasi palsu + kualitas dasar. Return null jika lolos, atau pesan error jika ditolak. */
    private @Nullable String validateLocation(Location loc) {
        if (loc == null) return "Lokasi belum tersedia.";
        // 1) mock flag
        boolean mocked = false;
        if (Build.VERSION.SDK_INT >= 31) mocked = loc.isMock();
        mocked = mocked || loc.isFromMockProvider();
        lastFixMock = mocked;

        // 2) akurasi (tolak kalau >100 m)
        float acc = loc.hasAccuracy() ? loc.getAccuracy() : Float.MAX_VALUE;
        if (acc > 100f) return "Akurasi lokasi buruk (>100m). Pindah ke area terbuka lalu coba lagi.";

        // 3) sanity check loncatan/kecepatan bila ada titik sebelumnya
        if (lastFix != null) {
            long dtMs = Math.max(1, loc.getTime() - lastFix.getTime());
            double distM = haversine(lastFix.getLatitude(), lastFix.getLongitude(),
                    loc.getLatitude(), loc.getLongitude());
            double kmh = (distM / (dtMs / 1000.0)) * 3.6;
            if (kmh > 150.0 || (distM > 1000.0 && dtMs <= 60_000)) {
                return "Pergerakan tidak wajar terdeteksi. Coba lagi tanpa mock GPS.";
            }
        }
        return mocked ? "Deteksi lokasi palsu (mock). Matikan aplikasi Fake GPS." : null;
    }

    /** Haversine meter */
    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon/2)*Math.sin(dLon/2);
        return 2 * R * Math.asin(Math.sqrt(a));
    }

    private String getVersionLabel() {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.PackageInfo p;

            if (android.os.Build.VERSION.SDK_INT >= 33) {
                p = pm.getPackageInfo(
                        getPackageName(),
                        android.content.pm.PackageManager.PackageInfoFlags.of(0)
                );
            } else {
                p = pm.getPackageInfo(getPackageName(), 0); // <— HAPUS "flags: 0"
            }

            String name = (p.versionName != null) ? p.versionName : "-";

            return "v" + name;
        } catch (Exception e) {
            return "v- • " + BuildConfig.BUILD_TYPE;
        }
    }
}
