package com.example.iristicktwiliovideo.activity;

import android.content.Context;
import android.support.v7.app.AppCompatActivity;

import com.iristick.smartglass.support.app.IristickApp;


public class BaseActivity extends AppCompatActivity {
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(IristickApp.wrapContext(newBase));
    }

}
