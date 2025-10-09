package com.nsi.attendance;

import static com.nsi.attendance.MyConstants.API_URL;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.toolbox.StringRequest;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class SplashActivity extends AppCompatActivity {

    private SessionManager session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        session = new SessionManager(this);

        // Belum login? → ke Login
        if (!session.isLoggedIn()) { goLogin(); return; }

        // Offline? → izinkan masuk (jangan logout)
        if (!NetworkUtil.isOnline(this)) { goHome(); return; }

        final String url = API_URL + "session";
        final String username = session.getUsername();
        final String unique   = DeviceUtils.getUniqueCode(this);

        Log.d("SPLASH", "Verify URL=" + url + " user=" + username + " unique=" + unique);

        StringRequest req = new StringRequest(Request.Method.POST, url, resp -> {
            try {
                String s = resp == null ? "" : resp.trim();
                // Server salah kirim HTML/empty? → anggap sementara OK (jangan logout)
                if (s.isEmpty() || s.charAt(0) == '<') { goHome(); return; }

                JSONObject js = new JSONObject(s);
                if (js.has("data")) {
                    // Sesi valid → opsional sinkron waktu, lalu masuk
                    new TimeSyncManager(this).sync(this::goHome);
                } else {
                    // Ada error JSON? cek kodenya
                    JSONObject err = js.optJSONObject("error");
                    int code = err != null ? err.optInt("code", 0) : 0;
                    if (code == 401 || code == 403 || code == 409) {
                        // Memang invalid / dipakai device lain → logout
                        session.clear();
                        goLogin();
                    } else {
                        // Error lain (5xx, 4xx selain auth) → tetap ijinkan masuk
                        goHome();
                    }
                }
            } catch (Exception e) {
                // Parse error → jangan logout
                Log.e("SPLASH", "parse fail", e);
                goHome();
            }
        }, err -> {
            // Jaringan/timeout/5xx → jangan logout
            Log.e("SPLASH", "verify error: " + err);
            goHome();
        }) {
            @Override protected Map<String, String> getParams() {
                Map<String, String> m = new HashMap<>();
                m.put("username", username);
                m.put("unique_code", unique);
                return m;
            }
        };
        req.setRetryPolicy(new DefaultRetryPolicy(10000, 0, 1f));
        req.setShouldCache(false);
        VolleySingleton.getInstance(this).add(req);
    }

    private void goHome()  { startActivity(new Intent(this, MainActivity.class)); finish(); }
    private void goLogin() { startActivity(new Intent(this, LoginActivity.class)); finish(); }
}
