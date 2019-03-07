package com.example.iristicktwiliovideo;

import android.app.Application;

import com.iristick.smartglass.support.app.IristickApp;

public class IristickApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        IristickApp.init(this);
    }
}
