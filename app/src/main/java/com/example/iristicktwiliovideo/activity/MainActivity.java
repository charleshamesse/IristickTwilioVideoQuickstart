package com.example.iristicktwiliovideo.activity;

import android.os.Bundle;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import com.example.iristicktwiliovideo.R;

import com.iristick.smartglass.core.Headset;
import com.iristick.smartglass.support.app.IristickApp;

public class MainActivity extends BaseActivity {

    // Tags
    private String TAG = "RTRMA - MainActivity";

    // Db and Iristick
    private Headset headset;

    // UI
    private Button btnJoinSession;
    private EditText sessionCode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        headset = IristickApp.getHeadset();

        btnJoinSession = (Button) findViewById(R.id.btn_join_session);
        sessionCode = (EditText) findViewById(R.id.session_code);

        btnJoinSession.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                joinSession();
            }
        });
    }

    private void joinSession() {
            // Redirecting to video activity
            Intent joinSessionIntent = new Intent(MainActivity.this, SessionActivity.class);
            joinSessionIntent.putExtra("sessionCode", sessionCode.getText().toString());
            startActivity(joinSessionIntent);
    }
}
