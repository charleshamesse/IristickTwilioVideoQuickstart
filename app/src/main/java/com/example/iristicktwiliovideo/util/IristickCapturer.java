package com.example.iristicktwiliovideo.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Surface;

import com.iristick.smartglass.core.Headset;
import com.iristick.smartglass.core.camera.CameraCharacteristics;
import com.iristick.smartglass.core.camera.CameraDevice;
import com.iristick.smartglass.core.camera.CaptureRequest;
import com.iristick.smartglass.core.camera.CaptureSession;
import com.twilio.video.VideoCapturer;
import com.twilio.video.VideoDimensions;
import com.twilio.video.VideoFormat;
import com.twilio.video.VideoPixelFormat;
import com.twilio.video.VideoFrame;


import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class IristickCapturer implements VideoCapturer {

    // Tags
    private final String TAG = "IristickTwilioCapturer";

    // Iristick
    private final String[] cameraNames;
    private final Headset headset;
    private String cameraId;
    private CameraDevice cameraDevice;
    private CaptureSession captureSession;
    private int width;
    private int height;
    private int frameRate;

    // Capture
    private final Object stateLock = new Object();
    private boolean sessionOpening;
    private boolean sessionStopping;
    private boolean firstFrameObserved;
    private int failureCount;
    private Handler cameraThreadHandler;
    private ImageReader imageReader;
    private VideoCapturer.Listener videoCapturerListener;


    public IristickCapturer(
            @NonNull String cameraId,
            @NonNull Headset headset) {
        this.cameraId = cameraId;
        this.headset = headset;
        this.cameraNames = headset.getCameraIdList();

        HandlerThread thread = new HandlerThread("IristickCapturerThread");
        thread.start();
        cameraThreadHandler = new Handler(thread.getLooper());
    }

    /**
     * Starts capturing frames at the specified format. Frames will be provided to the given
     * listener upon availability.
     *
     * <p><b>Note</b>: This method is not meant to be invoked directly.
     *
     * @param captureFormat the format in which to capture frames.
     * @param videoCapturerListener consumer of available frames.
     */
    @Override
    public void startCapture(
            @NonNull VideoFormat captureFormat,
            @NonNull VideoCapturer.Listener videoCapturerListener) {

        synchronized (stateLock) {
            this.videoCapturerListener = videoCapturerListener;
            if (sessionOpening || captureSession != null) {
                Log.w(TAG, "Capture already started");
                return;
            }

            openCamera(true);
        }
    }

    private void openCamera(boolean resetFailures) {
        Log.i(TAG, "openCamera");

        synchronized (stateLock) {
            if (resetFailures)
                failureCount = 0;

            closeCamera();
            sessionOpening = true;
            Log.i(TAG, "sessionOpening");
            cameraThreadHandler.post(() -> {
                synchronized (stateLock) {
                    final String name = cameraNames[0];
                    Log.i(TAG, "cameraThreadHandler: " + name);
                    try {
                        headset.openCamera(name, cameraListener, cameraThreadHandler);
                    } catch (IllegalArgumentException e) {
                        Log.i(TAG, "Error opening camera: " + e.toString());
                    }
                }
            });
        }
    }

    private void closeCamera() {
        synchronized (stateLock) {
            Log.i(TAG, "closeCamera");
            final CameraDevice _cameraDevice = cameraDevice;
            cameraThreadHandler.post(() -> {
                try {
                    if (_cameraDevice != null)
                        _cameraDevice.close();
                } catch (IllegalStateException e) {
                    Log.i(TAG, "Error closing camera: " + e.toString());
                }
            });
            captureSession = null;
            cameraDevice = null;
        }
    }

    /**
     * Stops all frames being captured.
     *
     * <p><b>Note</b>: This method is not meant to be invoked directly.
     */
    @Override
    public void stopCapture() {
        Log.i(TAG, "stopCapture");

        synchronized (stateLock) {
            sessionStopping = true;
            while (sessionOpening) {
                Log.i(TAG, "stopCapture: Waiting for session to open");
                try {
                    stateLock.wait();
                } catch (InterruptedException e) {
                    Log.i(TAG, "stopCapture: Interrupted while waiting for session to open");
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            if (captureSession != null) {
                closeCamera();
            } else {
                Log.i(TAG, "stopCapture: No session open");
            }
            sessionStopping = false;
        }

        Log.i(TAG, "stopCapture: Done");
    }

    /**
     * Returns a list of all supported video formats. This list is based on what is specified by
     * {@link android.hardware.camera2.CameraCharacteristics}, so can vary based on a device's
     * camera capabilities.
     *
     * <p><b>Note</b>: This method can be invoked for informational purposes, but is primarily used
     * internally.
     *
     * @return all supported video formats.
     */
    @Override
    public synchronized List<VideoFormat> getSupportedFormats() {
        CameraCharacteristics.StreamConfigurationMap streamConfigurationMap = this.headset.getCameraCharacteristics(cameraNames[0])
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        Point[] sizes = streamConfigurationMap.getSizes(CaptureRequest.FORMAT_JPEG);

        // Convert to Twilio VideoFormat
        List<VideoFormat> videoFormats = new ArrayList<>();
        for (Point size : sizes) {
            int currentFrameRate = (int) Math.floor(1000000000L / streamConfigurationMap.getMinFrameDuration(size));
            VideoDimensions videoDimensions = new VideoDimensions(size.x, size.y);
            VideoFormat videoFormat = new VideoFormat(videoDimensions, currentFrameRate, VideoPixelFormat.RGBA_8888);
            videoFormats.add(videoFormat);
        }

        // Set up capture format
        width = sizes[sizes.length - 1].x;
        height = sizes[sizes.length - 1].y;
        frameRate = (int) Math.floor(1000000000L / streamConfigurationMap.getMinFrameDuration(sizes[sizes.length - 1]));

        // Set up ImageReader using these sizes, used as sink for video frames
        imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2);
        imageReader.setOnImageAvailableListener(imageReaderListener, cameraThreadHandler);

        return videoFormats;
    }

    /** Indicates that the capturer is not a screen cast. */
    @Override
    public boolean isScreencast() {
        return false;
    }

    private void checkIsOnCameraThread() {
        if(Thread.currentThread() != cameraThreadHandler.getLooper().getThread()) {
            Log.e(TAG, "Check is on camera thread failed.");
            throw new RuntimeException("Not on camera thread.");
        }
    }

    // Iristick CameraDevice listener
    private final CameraDevice.Listener cameraListener = new CameraDevice.Listener() {
        @Override
        public void onOpened(CameraDevice device) {
            checkIsOnCameraThread();
            synchronized (stateLock) {
                cameraDevice = device;
                Log.i(TAG, "CameraDevice onOpened");

                // Create the capture session
                captureSession = null;
                List<Surface> outputs = new ArrayList<>();
                outputs.add(imageReader.getSurface());
                cameraDevice.createCaptureSession(outputs, captureSessionListener, cameraThreadHandler);
            }
        }

        @Override
        public void onClosed(CameraDevice device) {}

        @Override
        public void onDisconnected(CameraDevice device) {
            checkIsOnCameraThread();
            synchronized (stateLock) {
                if (cameraDevice == device || cameraDevice == null)
                    Log.i(TAG, "Disconnected");
                else
                    Log.w(TAG, "onDisconnected from another CameraDevice");
            }
        }

        @Override
        public void onError(CameraDevice device, int error) {
            checkIsOnCameraThread();
            synchronized (stateLock) {
                if (cameraDevice == device || cameraDevice == null)
                    Log.i(TAG, "Camera device error");
                else
                    Log.w(TAG, "onError from another CameraDevice");
            }
        }
    };

    private final ImageReader.OnImageAvailableListener imageReaderListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            try (final Image image = reader.acquireLatestImage()) {
                if (image == null) {
                    return;
                }

                ByteBuffer _buffer = image.getPlanes()[0].getBuffer();
                byte[] _bytes = new byte[_buffer.capacity()];
                _buffer.get(_bytes);
                Bitmap viewBitmap = BitmapFactory.decodeByteArray(_bytes, 0, _bytes.length, null);

                // Extract the frame from the bitmap
                int bytes = viewBitmap.getByteCount();
                ByteBuffer buffer = ByteBuffer.allocate(bytes);
                viewBitmap.copyPixelsToBuffer(buffer);

                byte[] array = buffer.array();
                final long captureTimeNs =
                        TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime());

                // Create video frame
                VideoDimensions dimensions = new VideoDimensions(width, height);
                VideoFrame videoFrame = new VideoFrame(array,
                        dimensions, VideoFrame.RotationAngle.ROTATION_0, captureTimeNs);

                videoCapturerListener.onFrameCaptured(videoFrame);
            }
        }
    };

    private final CaptureSession.Listener captureSessionListener = new CaptureSession.Listener() {
        @Override
        public void onConfigured(CaptureSession session) {
            checkIsOnCameraThread();
            synchronized (stateLock) {
                Log.i(TAG, "CaptureSession configured");
                captureSession = session;

                // Set up ImageReader listener and notify VideoCapturer listener
                imageReader.setOnImageAvailableListener(imageReaderListener, cameraThreadHandler);
                videoCapturerListener.onCapturerStarted(true);

                // Manage state
                sessionOpening = false;
                firstFrameObserved = false;
                stateLock.notifyAll();

                // Apply last parameters
                applyParametersInternal();
            }
        }

        @Override
        public void onConfigureFailed(CaptureSession session) {
            Log.i(TAG, "CaptureSession configuration error");
        }

        @Override
        public void onClosed(CaptureSession session) {
            if (captureSession == session)
                captureSession = null;
        }

        @Override
        public void onActive(CaptureSession session) {
        }

        @Override
        public void onCaptureQueueEmpty(CaptureSession session) {
        }

        @Override
        public void onReady(CaptureSession session) {
        }
    };

    private void applyParametersInternal() {
        checkIsOnCameraThread();
        synchronized (stateLock) {
            Log.i(TAG, "applyParametersInternal");
            if (sessionOpening || sessionStopping || captureSession == null)
                return;

            // Build CaptureRequest parameters
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(imageReader.getSurface());
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, 1000000000L / frameRate);

            // Set up request
            builder.set(CaptureRequest.SCALER_ZOOM, (float)(1 << Math.max(0, 1 - 1)));
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            builder.set(CaptureRequest.LASER_MODE, CaptureRequest.LASER_MODE_OFF);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            captureSession.setRepeatingRequest(builder.build(), null, null);
        }
    }

}
