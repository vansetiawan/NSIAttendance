package com.nsi.attendance;

import android.content.Context;
import android.content.SharedPreferences;

public class SessionManager {
    private static final String PREF = "session";
    private static final String KEY_LOGGED_IN = "logged_in";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_EMP_ID = "emp_id";
    private static final String KEY_EMP_CODE = "emp_code";
    private static final String KEY_AREA_CODE = "area_code";
    private static final String KEY_NAME = "name";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_STATUS = "status";
    private static final String KEY_UNIQUE = "unique_code";

    private final SharedPreferences sp;

    public SessionManager(Context ctx) { sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE); }

    public void saveLogin(int id, int empID, String emp, String area, String name, String username, int status, String unique) {
        sp.edit()
                .putBoolean(KEY_LOGGED_IN, true)
                .putInt(KEY_USER_ID, id)
                .putInt(KEY_EMP_ID, empID)
                .putString(KEY_EMP_CODE, emp)
                .putString(KEY_AREA_CODE, area)
                .putString(KEY_NAME, name)
                .putString(KEY_USERNAME, username)
                .putInt(KEY_STATUS, status)
                .putString(KEY_UNIQUE, unique)
                .apply();
    }

    public boolean isLoggedIn() { return sp.getBoolean(KEY_LOGGED_IN, false); }
    public int getUserId() { return sp.getInt(KEY_USER_ID, 0); }
    public String getUsername() { return sp.getString(KEY_USERNAME, ""); }
    public String getName() { return sp.getString(KEY_NAME, ""); }
    public String getAreaCode() { return sp.getString(KEY_AREA_CODE, ""); }
    public String getUnique() { return sp.getString(KEY_UNIQUE, ""); }

    public void clear() { sp.edit().clear().apply(); }
}
