package com.example.nsiattendance;

import android.content.Context;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.Volley;

public class VolleySingleton {
    private static volatile VolleySingleton instance;
    private final Context appCtx;
    private RequestQueue requestQueue;

    private VolleySingleton(Context context) {
        this.appCtx = context.getApplicationContext();
    }

    public static VolleySingleton getInstance(Context context) {
        if (instance == null) {
            synchronized (VolleySingleton.class) {
                if (instance == null) {
                    instance = new VolleySingleton(context);
                }
            }
        }
        return instance;
    }

    public RequestQueue getRequestQueue() {
        if (requestQueue == null) {
            // gunakan context aplikasi agar tidak leak Activity
            requestQueue = Volley.newRequestQueue(appCtx);
        }
        return requestQueue;
    }

    public <T> void add(Request<T> req) {
        getRequestQueue().add(req);
    }

    public void cancelAll(Object tag) {
        getRequestQueue().cancelAll(tag);
    }
}
