package com.example.nsiattendance;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

public class MainActivity extends AppCompatActivity {
    private TextView tvTime, tvDate;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat tf = new SimpleDateFormat("hh:mm a", new Locale("id","ID"));
    private final SimpleDateFormat df = new SimpleDateFormat("EEEE • d MMMM yyyy", new Locale("id","ID"));

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            Calendar now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta"));
            tvTime.setText(tf.format(now.getTime()));
            tvDate.setText(df.format(now.getTime()));
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvTime = findViewById(R.id.tvTime);
        tvDate = findViewById(R.id.tvDate);
    }

    @Override protected void onResume() { super.onResume(); handler.post(clockTick); }
    @Override protected void onPause()  { handler.removeCallbacks(clockTick); super.onPause(); }
}
