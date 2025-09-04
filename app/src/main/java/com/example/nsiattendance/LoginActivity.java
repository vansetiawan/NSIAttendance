// LoginActivity.java (cuplikan inti)
package com.example.nsiattendance;

import static com.example.nsiattendance.MyConstants.API_URL;

import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.*;
import com.android.volley.toolbox.StringRequest;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class LoginActivity extends AppCompatActivity {

    private EditText edtUsername, edtPassword;
    private Button btnLogin;
    private SessionManager session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = new SessionManager(this);

        setContentView(R.layout.activity_login);
        edtUsername = findViewById(R.id.edtUsername);
        edtPassword = findViewById(R.id.edtPassword);
        btnLogin    = findViewById(R.id.btnLogin);
        btnLogin.setOnClickListener(v -> doLogin());
    }

    private void verifySessionOrShowLogin() {
        String savedUser = session.getUsername();
        String deviceUnique = DeviceUtils.getUniqueCode(this);

        if (!NetworkUtil.isOnline(this)) {
            // Offline: ijinkan masuk pakai sesi lokal (tak ada logout di app)
            goHome();
            return;
        }

        final String url = API_URL + "session"; // endpoint verifikasi sesi
        Log.d("LOGIN", "verify URL=" + url);

        ProgressDialog dlg = ProgressDialog.show(this, null, "Memverifikasi sesi...", true, false);

        StringRequest req = new StringRequest(Request.Method.POST, url, resp -> {
            dlg.dismiss();
            try {
                JSONObject js = new JSONObject(resp);
                if (js.has("data")) {
                    goHome(); // sesi valid
                } else {
                    session.clear();
                    showLoginUiWithMsg("Sesi berakhir. Silakan login lagi.");
                }
            } catch (Exception e) {
                session.clear();
                showLoginUiWithMsg("Sesi tidak valid. Silakan login lagi.");
            }
        }, err -> {
            dlg.dismiss();
            Log.e("LOGIN", "verify error: " + err, err);
            // Jika server unreachable tapi user ingin lanjut offline → boleh masuk
            goHome();
        }) {
            @Override protected Map<String, String> getParams() {
                Map<String, String> m = new HashMap<>();
                m.put("username", savedUser);
                m.put("unique_code", deviceUnique);
                return m;
            }
        };
        req.setRetryPolicy(new DefaultRetryPolicy(10000, 0, 1f));
        VolleySingleton.getInstance(this).add(req);
    }

    private void showLoginUiWithMsg(String msg) {
        setContentView(R.layout.activity_login);
        edtUsername = findViewById(R.id.edtUsername);
        edtPassword = findViewById(R.id.edtPassword);
        btnLogin    = findViewById(R.id.btnLogin);
        btnLogin.setOnClickListener(v -> doLogin());
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void doLogin() {
        String username = edtUsername.getText().toString().trim();
        String password = edtPassword.getText().toString();
        if (username.isEmpty()) { edtUsername.setError("Harus diisi"); edtUsername.requestFocus(); return; }
        if (password.isEmpty()) { edtPassword.setError("Harus diisi"); edtPassword.requestFocus(); return; }

        final String unique = DeviceUtils.getUniqueCode(this);
        final String url = API_URL + "login";
        Log.d("LOGIN", "URL=" + url);

        ProgressDialog dlg = ProgressDialog.show(this, null, "Masuk...", true, false);

        StringRequest req = new StringRequest(Request.Method.POST, url, response -> {
            dlg.dismiss();
            try {
                JSONObject js = new JSONObject(response);
                if (js.has("data")) {
                    JSONObject d = js.getJSONObject("data");
                    session.saveLogin(
                            d.optInt("id"),
                            d.optInt("emp_id"),
                            d.optString("emp_code"),
                            d.optString("area_code"),
                            d.optString("name"),
                            d.optString("username"),
                            d.optInt("status"),
                            d.optString("unique_code")
                    );
                    goHome();
                } else {
                    String msg = js.optJSONObject("error") != null
                            ? js.optJSONObject("error").optString("message", "Gagal login")
                            : "Gagal login";
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Parse error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, error -> {
            dlg.dismiss();
            String msg = "Jaringan/Server error";
            if (error.networkResponse != null && error.networkResponse.data != null) {
                msg = new String(error.networkResponse.data, StandardCharsets.UTF_8);
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        }) {
            @Override protected Map<String, String> getParams() {
                Map<String, String> m = new HashMap<>();
                m.put("username", username);
                m.put("password", password);
                m.put("unique_code", unique);
                return m;
            }
        };
        req.setRetryPolicy(new DefaultRetryPolicy(15000, 0, 1f));
        VolleySingleton.getInstance(this).add(req);
    }

    private void goHome() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
