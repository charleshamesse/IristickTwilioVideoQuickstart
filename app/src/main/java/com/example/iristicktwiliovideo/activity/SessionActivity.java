package com.example.iristicktwiliovideo.activity;


import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import com.example.iristicktwiliovideo.BuildConfig;
import com.example.iristicktwiliovideo.R;
import com.example.iristicktwiliovideo.util.IristickCapturer;
import com.example.iristicktwiliovideo.util.RemoteParticipantListener;
import com.iristick.smartglass.core.Headset;
import com.iristick.smartglass.support.app.IristickApp;
import com.koushikdutta.ion.Ion;
import com.twilio.video.AudioCodec;
import com.twilio.video.Camera2Capturer;
import com.twilio.video.CameraCapturer;
import com.twilio.video.ConnectOptions;
import com.twilio.video.EncodingParameters;
import com.twilio.video.LocalAudioTrack;
import com.twilio.video.LocalParticipant;
import com.twilio.video.LocalVideoTrack;
import com.twilio.video.OpusCodec;
import com.twilio.video.RemoteParticipant;
import com.twilio.video.RemoteVideoTrackPublication;
import com.twilio.video.Room;
import com.twilio.video.TwilioException;
import com.twilio.video.Video;
import com.twilio.video.VideoCapturer;
import com.twilio.video.VideoCodec;
import com.twilio.video.VideoTrack;
import com.twilio.video.VideoView;
import com.twilio.video.Vp8Codec;

import org.json.JSONObject;

import java.util.Collections;
import java.util.UUID;

public class SessionActivity extends BaseActivity {
    // Debug
    private final Boolean USE_SOUND = false; // Just to avoid feedback noise while testing

    // Tags
    private static final int CAMERA_MIC_PERMISSION_REQUEST_CODE = 1;
    private static final String TAG = "SessionActivityInfo";
    private static final String LOCAL_AUDIO_TRACK_NAME = "mic";
    private static final String LOCAL_VIDEO_TRACK_NAME = "camera";

    // Twilio
    private static final String ACCESS_TOKEN_SERVER = BuildConfig.TWILIO_ACCESS_TOKEN_SERVER;
    private String accessToken;
    private Room room;
    private LocalParticipant localParticipant;
    private AudioCodec audioCodec;
    private VideoCodec videoCodec;
    private EncodingParameters encodingParameters;
    private RemoteParticipantListener remoteParticipantListener;
    private LocalAudioTrack localAudioTrack;
    private LocalVideoTrack localVideoTrack;
    private AudioManager audioManager;
    private int previousAudioMode;
    private boolean previousMicrophoneMute;
    private boolean disconnectedFromOnDestroy;

    // Iristick
    private Headset headset;
    private VideoCapturer capturer;

    // Session
    private String sessionCode;

    // UI
    private VideoView primaryVideoView;
    private VideoView thumbnailVideoView;
    private String remoteParticipantIdentity;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get session info
        Intent intent = getIntent();
        sessionCode = intent.getStringExtra("sessionCode");
        Log.i(TAG, sessionCode);

        setContentView(R.layout.activity_session);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        View decorView = getWindow().getDecorView();
        // Hide both the navigation bar and the status bar.
        int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN;
        decorView.setSystemUiVisibility(uiOptions);

        // Set up UI elements
        primaryVideoView = findViewById(R.id.primary_video_view);
        thumbnailVideoView = findViewById(R.id.thumbnail_video_view);

        // Set up video
        audioCodec = new OpusCodec();
        videoCodec =  new Vp8Codec(false);
        setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
        audioManager.setSpeakerphoneOn(true);

        // Iristick headset
        headset = IristickApp.getHeadset();

        // Set up Twilio call listeners
        remoteParticipantListener = new RemoteParticipantListener(this);

        // Make sure permissions are granted and if so, create tracks
        if (!checkPermissionForCameraAndMicrophone()) {
            requestPermissionForCameraAndMicrophone();
        } else {
            startSession();
        }

    }

    // Other lifecycle methods
    @Override
    protected  void onResume() {
        super.onResume();

        audioCodec = new OpusCodec();
        videoCodec =  new Vp8Codec(false);

        final EncodingParameters newEncodingParameters = new EncodingParameters(0, 0);

        // If the local video track was released when the app was put in the background, recreate
        if (localVideoTrack == null && checkPermissionForCameraAndMicrophone()) {

            createAudioAndVideoTracks();

            // If connected to a Room then share the local video track
            if (localParticipant != null) {
                localParticipant.publishTrack(localVideoTrack);

                // Update encoding parameters if they have changed
                if (!newEncodingParameters.equals(encodingParameters)) {
                    localParticipant.setEncodingParameters(newEncodingParameters);
                }
            }
        }

        // Update encoding parameters
        encodingParameters = newEncodingParameters;
    }

    @Override
    protected void onPause() {
        if (localVideoTrack != null) {
            if (localParticipant != null) {
                localParticipant.unpublishTrack(localVideoTrack);
            }
            localVideoTrack.release();
            localVideoTrack = null;
        }
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == CAMERA_MIC_PERMISSION_REQUEST_CODE) {
            boolean cameraAndMicPermissionGranted = true;

            for (int grantResult : grantResults) {
                cameraAndMicPermissionGranted &= grantResult == PackageManager.PERMISSION_GRANTED;
            }

            if (cameraAndMicPermissionGranted) {
                startSession();
            } else {
                Toast.makeText(this,
                        "Camera and Microphone permissions are needed.",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        // Disconnect from room before leaving Activity to ensure memory allocated to Room is freed
        if (room != null && room.getState() != Room.State.DISCONNECTED) {
            room.disconnect();
            disconnectedFromOnDestroy = true;
        }

        // Release local audio and video tracks ensuring memory allocated to audio or video is freed
        if (localAudioTrack != null) {
            localAudioTrack.release();
            localAudioTrack = null;
        }
        if (localVideoTrack != null) {
            localVideoTrack.release();
            localVideoTrack = null;
        }
        super.onDestroy();
    }

    private void startSession() {
        createAudioAndVideoTracks();
        getTokenAndConnect();
    }

    public String getSessionCode() {
        return sessionCode;
    }

    // Permission management methods
    private boolean checkPermissionForCameraAndMicrophone(){
        int resultCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
        int resultMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        return resultCamera == PackageManager.PERMISSION_GRANTED &&
                resultMic == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissionForCameraAndMicrophone(){
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA) ||
                ActivityCompat.shouldShowRequestPermissionRationale(this,
                        Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(this,
                    "Need permissions",
                    Toast.LENGTH_SHORT).show();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    CAMERA_MIC_PERMISSION_REQUEST_CODE);
        }
    }

    // Tracks
    private void createAudioAndVideoTracks() {
        // Share your microphone
        localAudioTrack = LocalAudioTrack.create(this, true, LOCAL_AUDIO_TRACK_NAME);

        // Don't start if headset is not connected
        if(headset == null) {
            Toast.makeText(this, "Headset not connected. Using phone camera..", Toast.LENGTH_SHORT).show();
            capturer = new Camera2Capturer(this, "0", new Camera2Capturer.Listener() {
                        @Override
                        public void onFirstFrameAvailable() {
                            Log.i(TAG, "onFirstFrameAvailable");
                        }

                        @Override
                        public void onCameraSwitched(String newCameraId) {
                            Log.i(TAG, "onCameraSwitched: newCameraId = " + newCameraId);
                        }

                        @Override
                        public void onError(Camera2Capturer.Exception camera2CapturerException) {
                            Log.e(TAG, camera2CapturerException.getMessage());
                        }
            });

        }
        else {
            capturer = new IristickCapturer("0", headset);
        }

        primaryVideoView.setMirror(true);
        localVideoTrack = LocalVideoTrack.create(this,
                true,
                capturer,
                LOCAL_VIDEO_TRACK_NAME);
        localVideoTrack.addRenderer(primaryVideoView);

    }

    private void configureAudio(boolean enable) {
        if (enable) {
            previousAudioMode = audioManager.getMode();

            // Request audio focus before making any device switch
            requestAudioFocus();

            // Use MODE_IN_COMMUNICATION as the default audio mode.
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

            // Always disable microphone mute during a WebRTC call.
            previousMicrophoneMute = audioManager.isMicrophoneMute();
            audioManager.setMicrophoneMute(false);
        } else {
            audioManager.setMode(previousAudioMode);
            audioManager.abandonAudioFocus(null);
            audioManager.setMicrophoneMute(previousMicrophoneMute);
        }
    }


    private void requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes playbackAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            AudioFocusRequest focusRequest =
                    new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(playbackAttributes)
                            .setAcceptsDelayedFocusGain(true)
                            .setOnAudioFocusChangeListener(
                                    i -> { })
                            .build();
            audioManager.requestAudioFocus(focusRequest);
        } else {
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        }
    }

    // Connection
    private void getTokenAndConnect() {
        // Implement your logic for fetching token from server here
        Toast.makeText(this, "Fetching token..", Toast.LENGTH_SHORT).show();
        String identity =  UUID.randomUUID().toString(); // This would be the username
        Ion.with(this)
                .load(ACCESS_TOKEN_SERVER + "/token/" + identity)
                .asString()
                .setCallback((err, res) -> {
                    if (err == null) {
                        try {
                            JSONObject response = new JSONObject(res);
                            SessionActivity.this.accessToken = response.getString("token");
                            Toast.makeText(SessionActivity.this,
                                    "Token received. Connecting..", Toast.LENGTH_SHORT)
                                    .show();
                            connectToRoom();
                        }
                        catch(Exception e) {
                            Toast.makeText(SessionActivity.this,
                                    "Connection error" + e.toString(), Toast.LENGTH_SHORT)
                                    .show();
                            Log.i(TAG, e.toString());
                        }
                    } else {
                        Toast.makeText(SessionActivity.this,
                                "Could not retrieve access token", Toast.LENGTH_SHORT)
                                .show();
                    }
                });

    }

    private void connectToRoom() {
        configureAudio(USE_SOUND);
        ConnectOptions.Builder connectOptionsBuilder = new ConnectOptions.Builder(SessionActivity.this.accessToken)
                .roomName(sessionCode);

        if (localAudioTrack != null && USE_SOUND) {
            connectOptionsBuilder.audioTracks(Collections.singletonList(localAudioTrack));
        }
        if (localVideoTrack != null) {
            connectOptionsBuilder.videoTracks(Collections.singletonList(localVideoTrack));
        }

        // Set codecs and encoding params
        connectOptionsBuilder.preferAudioCodecs(Collections.singletonList(audioCodec));
        connectOptionsBuilder.preferVideoCodecs(Collections.singletonList(videoCodec));
        connectOptionsBuilder.encodingParameters(encodingParameters);

        try {
            room = Video.connect(this, connectOptionsBuilder.build(), roomListener());
        } catch (Exception e) {
            Toast.makeText(this, e.toString(), Toast.LENGTH_SHORT).show();
        }

        // If needed, show disconnect button here
    }

    // Room events listener
    private Room.Listener roomListener() {
        return new Room.Listener() {
            @Override
            public void onConnected(Room room) {
                localParticipant = room.getLocalParticipant();
                Toast.makeText(SessionActivity.this, "Connected to " + room.getName(), Toast.LENGTH_SHORT).show();
                setTitle(room.getName());

                for (RemoteParticipant remoteParticipant : room.getRemoteParticipants()) {
                    addRemoteParticipant(remoteParticipant);
                    break;
                }
            }

            @Override
            public void onConnectFailure(Room room, TwilioException e) {
                Toast.makeText(SessionActivity.this, "Error: " + e.toString(), Toast.LENGTH_SHORT).show();
                configureAudio(false);
            }

            @Override
            public void onDisconnected(Room room, TwilioException e) {
                localParticipant = null;
                Toast.makeText(SessionActivity.this, "Disconnected from " + room.getName(), Toast.LENGTH_SHORT).show();
                SessionActivity.this.room = null;
                // Only reinitialize the UI if disconnect was not called from onDestroy()
                if (!disconnectedFromOnDestroy) {
                    configureAudio(false);
                    moveLocalVideoToPrimaryView();
                }
            }

            @Override
            public void onParticipantConnected(Room room, RemoteParticipant remoteParticipant) {
                addRemoteParticipant(remoteParticipant);
            }

            @Override
            public void onParticipantDisconnected(Room room, RemoteParticipant remoteParticipant) {
                removeRemoteParticipant(remoteParticipant);
            }

            @Override
            public void onRecordingStarted(Room room) {
                Log.d(TAG, "onRecordingStarted");
            }

            @Override
            public void onRecordingStopped(Room room) {
                Log.d(TAG, "onRecordingStopped");
            }
        };
    }


    /*
     * Called when remote participant joins the room
     */
    private void addRemoteParticipant(RemoteParticipant remoteParticipant) {
        remoteParticipantIdentity = remoteParticipant.getIdentity();
        Toast.makeText(SessionActivity.this, remoteParticipantIdentity + " joined", Toast.LENGTH_SHORT).show();

        // Add remote participant renderer
        if (remoteParticipant.getRemoteVideoTracks().size() > 0) {
            RemoteVideoTrackPublication remoteVideoTrackPublication =
                    remoteParticipant.getRemoteVideoTracks().get(0);

            // Only render video tracks that are subscribed to
            if (remoteVideoTrackPublication.isTrackSubscribed()) {
                addRemoteParticipantVideo(remoteVideoTrackPublication.getRemoteVideoTrack());
            }
        }

        // Start listening for participant events
        remoteParticipant.setListener(remoteParticipantListener);
    }



    /*
     * Set primary view as renderer for participant video track
     */
    public void addRemoteParticipantVideo(VideoTrack videoTrack) {
        moveLocalVideoToThumbnailView();
        primaryVideoView.setMirror(false);
        videoTrack.addRenderer(primaryVideoView);
    }

    private void moveLocalVideoToThumbnailView() {
        if (thumbnailVideoView.getVisibility() == View.GONE) {
            thumbnailVideoView.setVisibility(View.VISIBLE);
            localVideoTrack.removeRenderer(primaryVideoView);
            localVideoTrack.addRenderer(thumbnailVideoView);
            thumbnailVideoView.setMirror(false);
        }
    }

    /*
     * Called when remote participant leaves the room
     */
    private void removeRemoteParticipant(RemoteParticipant remoteParticipant) {
        Toast.makeText(SessionActivity.this, remoteParticipantIdentity + " left", Toast.LENGTH_SHORT).show();
        if (!remoteParticipant.getIdentity().equals(remoteParticipantIdentity)) {
            return;
        }

        /*
         * Remove remote participant renderer
         */
        if (!remoteParticipant.getRemoteVideoTracks().isEmpty()) {
            RemoteVideoTrackPublication remoteVideoTrackPublication =
                    remoteParticipant.getRemoteVideoTracks().get(0);

            // Remove video only if subscribed to participant track
            if (remoteVideoTrackPublication.isTrackSubscribed()) {
                removeParticipantVideo(remoteVideoTrackPublication.getRemoteVideoTrack());
            }
        }
        moveLocalVideoToPrimaryView();
    }

    public void removeParticipantVideo(VideoTrack videoTrack) {
        videoTrack.removeRenderer(primaryVideoView);
    }

    private void moveLocalVideoToPrimaryView() {
        if (thumbnailVideoView.getVisibility() == View.VISIBLE) {
            thumbnailVideoView.setVisibility(View.GONE);
            if (localVideoTrack != null) {
                localVideoTrack.removeRenderer(thumbnailVideoView);
                localVideoTrack.addRenderer(primaryVideoView);
            }
        }
    }
}
