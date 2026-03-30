package com.example.uniquecreator.helper;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * SharedPreferences Helper Class
 * Only stores API verification success flag
 */
public class SharedPrefHelper {

    private static final String PREF_NAME = "CopyrightFreeVideoPref";
    private static final String KEY_IS_VERIFIED = "is_verified";
    private static final String DEVICE_ID = "DEVICE_ID";

    private final SharedPreferences pref;
    private final SharedPreferences.Editor editor;

    private static SharedPrefHelper instance;

    private SharedPrefHelper(Context context) {
        pref = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        editor = pref.edit();
    }

    public static synchronized SharedPrefHelper getInstance(Context context) {
        if (instance == null) {
            instance = new SharedPrefHelper(context);
        }
        return instance;
    }

    public boolean isVerified() {
        return pref.getBoolean(KEY_IS_VERIFIED, false);
    }

    public void setVerified(boolean verified) {
        editor.putBoolean(KEY_IS_VERIFIED, verified);
        editor.apply();
    }

    public String getDeviceId() {
        return pref.getString(DEVICE_ID, null);
    }

    public void setDeviceId(String deviceId) {
        editor.putString(DEVICE_ID, deviceId);
        editor.apply();
    }
}