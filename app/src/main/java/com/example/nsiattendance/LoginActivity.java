package com.example.nsiattendance;

import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.toolbox.StringRequest;
import com.example.nsiattendance.MainActivity;
import com.example.nsiattendance.R;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class LoginActivity extends AppCompatActivity {

    private EditText edtUsername, edtPassword;
    private Button btnLogin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        edtUsername = findViewById(R.id.edtUsername);
        edtPassword = findViewById(R.id.edtPassword);
        btnLogin    = findViewById(R.id.btnLogin);

        btnLogin.setOnClickListener(v -> doLogin());
    }

    private void doLogin() {
        String username = edtUsername.getText().toString().trim();
        String password = edtPassword.getText().toString();
        if (username.isEmpty()) { edtUsername.setError("Harus diisi"); edtUsername.requestFocus(); return; }
        if (password.isEmpty()) { edtPassword.setError("Harus diisi"); edtPassword.requestFocus(); return; }

        final String unique = DeviceUtils.getUniqueCode(this);
        final String url = MyConstants.API_URL + "login";

        ProgressDialog dlg = ProgressDialog.show(this, null, "Masuk...", true, false);

        StringRequest req = new StringRequest(Request.Method.POST, url,
                response -> {
                    dlg.dismiss();
                    try {
                        JSONObject js = new JSONObject(response);
                        if (js.has("data")) {
                            JSONObject d = js.getJSONObject("data");
                            // ----- fields menyesuaikan AppUsers -----
                            int id           = d.optInt("id");
                            String empCode   = d.optString("emp_code");
                            String areaCode  = d.optString("area_code");
                            String uName     = d.optString("username");
                            int status       = d.optInt("status");
                            String uCode     = d.optString("unique_code");

                            // simpan session
                            SharedPreferences sp = getSharedPreferences("session", MODE_PRIVATE);
                            sp.edit()
                                    .putInt("user_id", id)
                                    .putString("emp_code", empCode)
                                    .putString("area_code", areaCode)
                                    .putString("username", uName)
                                    .putInt("status", status)
                                    .putString("unique_code", uCode)
                                    .apply();

                            // lanjut ke Home
                            startActivity(new Intent(this, MainActivity.class));
                            finish();
                        } else if (js.has("error")) {
                            String msg = js.getJSONObject("error").optString("message", "Gagal login");
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this, "Respon tidak dikenal", Toast.LENGTH_LONG).show();
                        }
                    } catch (Exception e) {
                        Toast.makeText(this, "Parse error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                },
                error -> {
                    dlg.dismiss();
                    String msg = "Jaringan/Server error";
                    if (error != null) {
                        Log.e("LOGIN", "Volley error = " + error.toString(), error);
                        if (error.getCause() != null) {
                            Log.e("LOGIN", "Cause = " + error.getCause().toString());
                        }
                        if (error.networkResponse != null && error.networkResponse.data != null) {
                            msg = new String(error.networkResponse.data, StandardCharsets.UTF_8);
                            Log.e("LOGIN", "Body = " + msg);
                        }
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                }
        ) {
            @Override protected Map<String, String> getParams() {
                Map<String, String> m = new HashMap<>();
                m.put("username", username);      // ⇐ sesuai field tabel
                m.put("password", password);      // ⇐ kirim plain; server cek bcrypt
                m.put("unique_code", unique);     // ⇐ bind device (VARCHAR(20))
                return m;
            }
            @Override public Map<String, String> getHeaders() {
                Map<String, String> h = new HashMap<>();
                h.put("Accept", "application/json");
                return h;
            }
        };
        req.setRetryPolicy(new DefaultRetryPolicy(15000, 0, 1f));
        VolleySingleton.getInstance(this).add(req);
    }

}
