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

        if (!session.isLoggedIn()) {
            goLogin();
            return;
        }

        if (!NetworkUtil.isOnline(this)) {
            goHome();
            return;
        }

        String url = API_URL + "session";
        final String username = session.getUsername();
        final String unique   = DeviceUtils.getUniqueCode(this);

        Log.d("SPLASH", "Verify URL=" + url + " user=" + username + " unique=" + unique);

        StringRequest req = new StringRequest(Request.Method.POST, url, resp -> {
            try {
                JSONObject js = new JSONObject(resp);
                if (js.has("data")) {
                    TimeSyncManager tsm = new TimeSyncManager(this);
                    tsm.sync(this::goHome);
                } else {
                    session.clear();
                    goLogin();
                }
            } catch (Exception e) {
                session.clear();
                goLogin();
            }
        }, err -> {
            Log.e("SPLASH", "verify error: " + err);
            goHome();
        }) {
            @Override
            protected Map<String, String> getParams() {
                Map<String, String> m = new HashMap<>();
                m.put("username", username);
                m.put("unique_code", unique);
                return m;
            }
        };
        req.setRetryPolicy(new DefaultRetryPolicy(10000, 0, 1f));
        VolleySingleton.getInstance(this).add(req);
    }

    private void goHome()  {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
    private void goLogin() { startActivity(new Intent(this, com.nsi.attendance.LoginActivity.class)); finish(); }
}
