package com.huangguo.mobile;

import android.app.Application;

import com.parse.Parse;

public final class HuangGuoApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Parse.initialize(new Parse.Configuration.Builder(this)
                .applicationId(getString(R.string.back4app_app_id))
                .clientKey(getString(R.string.back4app_client_key))
                .server("https://parseapi.back4app.com/")
                .build());
    }
}
