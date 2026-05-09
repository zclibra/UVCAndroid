package com.hlrn.uvcpad;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends android.app.Activity {
    private static final int REQUEST_CAMERA_PERMISSION = 10;
    private static final String ACTION_USB_PERMISSION = "com.hlrn.uvcpad.USB_PERMISSION";

    private final Size captureSize = new Size(1280, 720);
    private final List<File> recordings = new ArrayList<>();

    private UsbManager usbManager;
    private CameraManager cameraManager;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaRecorder mediaRecorder;
    private String currentCameraId;

    private TextureView previewView;
    private VideoView playbackView;
    private TextView statusView;
    private Button recordButton;
    private ListView recordingListView;
    private ArrayAdapter<String> recordingAdapter;

    private boolean isRecording;
    private boolean receiverRegistered;
    private File currentRecordingFile;

    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openExternalCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            configurePreviewTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            closeCamera();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (ACTION_USB_PERMISSION.equals(action)) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    setStatus("USB permission granted: " + deviceName(device));
                    restartCamera();
                } else {
                    setStatus("USB permission denied: " + deviceName(device));
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                setStatus("USB camera attached: " + deviceName(device));
                requestUsbPermissionIfNeeded(device);
                restartCamera();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                setStatus("USB camera detached: " + deviceName(device));
                closeCamera();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(USB_SERVICE);
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);

        buildPadUi();
        registerUsbReceiver();
        startCameraThread();
        requestCameraPermissionIfNeeded();
        scanUsbDevices();
        refreshRecordings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startCameraThread();
        if (previewView.isAvailable()) {
            openExternalCamera();
        } else {
            previewView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        if (isRecording) {
            stopRecording();
        }
        closeCamera();
        stopCameraThread();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(usbReceiver);
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setStatus("Camera permission granted");
                openExternalCamera();
            } else {
                setStatus("Camera permission is required for live preview");
            }
        }
    }

    private void buildPadUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.rgb(15, 23, 42));

        FrameLayout previewContainer = new FrameLayout(this);
        previewContainer.setBackgroundColor(Color.BLACK);
        previewView = new TextureView(this);
        previewContainer.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        root.addView(previewContainer, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        panel.setBackgroundColor(Color.rgb(248, 250, 252));
        root.addView(panel, new LinearLayout.LayoutParams(dp(380), ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = label("UVC Pad Camera", 22, Color.rgb(15, 23, 42));
        panel.addView(title, fullWidth(dp(36)));

        statusView = label("Initializing", 14, Color.rgb(51, 65, 85));
        panel.addView(statusView, fullWidth(dp(52)));

        recordButton = commandButton("Start Recording");
        recordButton.setOnClickListener(v -> {
            if (isRecording) {
                stopRecording();
            } else {
                startRecording();
            }
        });
        panel.addView(recordButton, fullWidth(dp(48)));

        Button reconnectButton = commandButton("Reconnect Camera");
        reconnectButton.setOnClickListener(v -> restartCamera());
        panel.addView(reconnectButton, fullWidth(dp(48)));

        TextView playbackTitle = label("Recordings", 16, Color.rgb(15, 23, 42));
        playbackTitle.setPadding(0, dp(18), 0, dp(8));
        panel.addView(playbackTitle, fullWidth(dp(52)));

        recordingAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        recordingListView = new ListView(this);
        recordingListView.setAdapter(recordingAdapter);
        recordingListView.setOnItemClickListener((parent, view, position, id) -> playRecording(recordings.get(position)));
        panel.addView(recordingListView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        playbackView = new VideoView(this);
        playbackView.setVisibility(View.GONE);
        playbackView.setOnCompletionListener(mp -> playbackView.setVisibility(View.GONE));
        previewContainer.addView(playbackView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        setContentView(root);
    }

    private TextView label(String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private Button commandButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout.LayoutParams fullWidth(int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height
        );
        params.setMargins(0, 0, 0, dp(10));
        return params;
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void startCameraThread() {
        if (cameraThread != null) {
            return;
        }
        cameraThread = new HandlerThread("uvc-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread == null) {
            return;
        }
        cameraThread.quitSafely();
        try {
            cameraThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cameraThread = null;
        cameraHandler = null;
    }

    private void requestCameraPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    private boolean hasCameraPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void scanUsbDevices() {
        Map<String, UsbDevice> devices = usbManager.getDeviceList();
        if (devices.isEmpty()) {
            setStatus("No USB camera detected");
            return;
        }
        for (UsbDevice device : devices.values()) {
            if (isUvcDevice(device)) {
                requestUsbPermissionIfNeeded(device);
                setStatus("UVC device found: " + deviceName(device));
                return;
            }
        }
        setStatus("USB devices found, but no UVC camera class device");
    }

    private void requestUsbPermissionIfNeeded(UsbDevice device) {
        if (device == null || usbManager.hasPermission(device)) {
            return;
        }
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent intent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), flags);
        usbManager.requestPermission(device, intent);
    }

    private boolean isUvcDevice(UsbDevice device) {
        if (device == null) {
            return false;
        }
        if (device.getDeviceClass() == 14) {
            return true;
        }
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            if (usbInterface.getInterfaceClass() == 14) {
                return true;
            }
        }
        return false;
    }

    private void openExternalCamera() {
        if (!hasCameraPermission() || !previewView.isAvailable()) {
            return;
        }
        try {
            String cameraId = findExternalCameraId();
            if (cameraId == null) {
                setStatus("No Camera2 external camera. Native UVC engine may be required.");
                return;
            }
            closeCamera();
            currentCameraId = cameraId;
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    setStatus("Live preview opened");
                    createPreviewSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    setStatus("Camera disconnected");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    setStatus("Camera error: " + error);
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            setStatus("Open camera failed: " + e.getMessage());
        }
    }

    private String findExternalCameraId() throws CameraAccessException {
        String fallback = null;
        for (String cameraId : cameraManager.getCameraIdList()) {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                return cameraId;
            }
            if (fallback == null && (facing == null || facing != CameraCharacteristics.LENS_FACING_FRONT)) {
                fallback = cameraId;
            }
        }
        return fallback;
    }

    private void createPreviewSession() {
        if (cameraDevice == null || !previewView.isAvailable()) {
            return;
        }
        try {
            SurfaceTexture texture = previewView.getSurfaceTexture();
            if (texture == null) {
                return;
            }
            texture.setDefaultBufferSize(captureSize.getWidth(), captureSize.getHeight());
            configurePreviewTransform(previewView.getWidth(), previewView.getHeight());
            Surface previewSurface = new Surface(texture);
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);
            closeSession();
            cameraDevice.createCaptureSession(Arrays.asList(previewSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        captureSession.setRepeatingRequest(builder.build(), null, cameraHandler);
                    } catch (CameraAccessException e) {
                        setStatus("Preview failed: " + e.getMessage());
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    setStatus("Preview configuration failed");
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            setStatus("Create preview failed: " + e.getMessage());
        }
    }

    private void startRecording() {
        if (cameraDevice == null || isRecording) {
            setStatus("Camera is not ready");
            return;
        }
        try {
            prepareRecorder();
            SurfaceTexture texture = previewView.getSurfaceTexture();
            if (texture == null) {
                setStatus("Preview surface is not ready");
                return;
            }
            texture.setDefaultBufferSize(captureSize.getWidth(), captureSize.getHeight());
            configurePreviewTransform(previewView.getWidth(), previewView.getHeight());
            Surface previewSurface = new Surface(texture);
            Surface recordingSurface = mediaRecorder.getSurface();
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(previewSurface);
            builder.addTarget(recordingSurface);
            closeSession();
            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, recordingSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        captureSession.setRepeatingRequest(builder.build(), null, cameraHandler);
                        mediaRecorder.start();
                        isRecording = true;
                        runOnUiThread(() -> recordButton.setText("Stop Recording"));
                        setStatus("Recording: " + currentRecordingFile.getName());
                    } catch (CameraAccessException | IllegalStateException e) {
                        setStatus("Start recording failed: " + e.getMessage());
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    setStatus("Recording configuration failed");
                }
            }, cameraHandler);
        } catch (CameraAccessException | IOException e) {
            releaseRecorder();
            setStatus("Prepare recording failed: " + e.getMessage());
        }
    }

    private void stopRecording() {
        if (!isRecording || mediaRecorder == null) {
            return;
        }
        try {
            mediaRecorder.stop();
            setStatus("Saved: " + currentRecordingFile.getName());
        } catch (RuntimeException e) {
            if (currentRecordingFile != null) {
                currentRecordingFile.delete();
            }
            setStatus("Recording discarded: " + e.getMessage());
        } finally {
            isRecording = false;
            runOnUiThread(() -> recordButton.setText("Start Recording"));
            releaseRecorder();
            createPreviewSession();
            refreshRecordings();
        }
    }

    private void prepareRecorder() throws IOException {
        currentRecordingFile = new File(recordingDirectory(), timestampName() + ".mp4");
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setOutputFile(currentRecordingFile.getAbsolutePath());
        mediaRecorder.setVideoEncodingBitRate(8_000_000);
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setVideoSize(captureSize.getWidth(), captureSize.getHeight());
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setOrientationHint(getVideoOrientationHint());
        mediaRecorder.prepare();
    }

    private void configurePreviewTransform(int viewWidth, int viewHeight) {
        if (viewWidth == 0 || viewHeight == 0) {
            return;
        }
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, captureSize.getHeight(), captureSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();

        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / captureSize.getHeight(),
                    (float) viewWidth / captureSize.getWidth()
            );
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180, centerX, centerY);
        }
        previewView.setTransform(matrix);
    }

    private int getVideoOrientationHint() {
        if (currentCameraId == null) {
            return 0;
        }
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(currentCameraId);
            Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            int deviceDegrees = displayRotationToDegrees(getWindowManager().getDefaultDisplay().getRotation());
            int sensorDegrees = sensorOrientation == null ? 0 : sensorOrientation;
            if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                return (sensorDegrees + deviceDegrees) % 360;
            }
            return (sensorDegrees - deviceDegrees + 360) % 360;
        } catch (CameraAccessException e) {
            return 0;
        }
    }

    private int displayRotationToDegrees(int rotation) {
        switch (rotation) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_0:
            default:
                return 0;
        }
    }

    private File recordingDirectory() {
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "recordings");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private String timestampName() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private void playRecording(File file) {
        if (isRecording) {
            stopRecording();
        }
        playbackView.setVisibility(View.VISIBLE);
        playbackView.setVideoPath(file.getAbsolutePath());
        playbackView.start();
        setStatus("Playing: " + file.getName());
    }

    private void refreshRecordings() {
        recordings.clear();
        File[] files = recordingDirectory().listFiles((dir, name) -> name.endsWith(".mp4"));
        if (files != null) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            recordings.addAll(Arrays.asList(files));
        }
        runOnUiThread(() -> {
            recordingAdapter.clear();
            for (File file : recordings) {
                recordingAdapter.add(file.getName());
            }
            recordingAdapter.notifyDataSetChanged();
        });
    }

    private void restartCamera() {
        closeCamera();
        if (previewView.isAvailable()) {
            openExternalCamera();
        }
    }

    private void closeSession() {
        if (captureSession == null) {
            return;
        }
        captureSession.close();
        captureSession = null;
    }

    private void closeCamera() {
        closeSession();
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        currentCameraId = null;
        releaseRecorder();
    }

    private void releaseRecorder() {
        if (mediaRecorder == null) {
            return;
        }
        mediaRecorder.reset();
        mediaRecorder.release();
        mediaRecorder = null;
    }

    private void setStatus(String text) {
        runOnUiThread(() -> statusView.setText(text));
    }

    private String deviceName(UsbDevice device) {
        if (device == null) {
            return "unknown";
        }
        return "vid=" + device.getVendorId() + " pid=" + device.getProductId();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
