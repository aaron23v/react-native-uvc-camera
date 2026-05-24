/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.cameraview;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.media.CamcorderProfile;
import android.util.Log;
import android.view.Surface;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.facebook.react.uimanager.ThemedReactContext;
import com.serenegiant.usb.DeviceFilter;
import com.serenegiant.usbcameracommon.UVCCameraHandler;
import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.widget.UVCCameraTextureView;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;

@SuppressWarnings("MissingPermission")
@TargetApi(21)
public class CameraUvc extends CameraViewImpl {

    private static final String TAG = "CameraUvc";

    /**
     * set true if you want to record movie using MediaSurfaceEncoder
     * (writing frame data into Surface camera from MediaCodec
     *  by almost same way as USBCameratest2)
     * set false if you want to record movie using MediaVideoEncoder
     */
    private static final boolean USE_SURFACE_ENCODER = false;
    /**
     * preview resolution(width)
     * if your camera does not support specific resolution and mode,
     * {@link UVCCamera#setPreviewSize(int, int, int)} throw exception
     */
    public static final int PREVIEW_WIDTH = 1920;
    /**
     * preview resolution(height)
     * if your camera does not support specific resolution and mode,
     * {@link UVCCamera#setPreviewSize(int, int, int)} throw exception
     */
    public static final int PREVIEW_HEIGHT = 1080;
    /**
     * preview mode
     * if your camera does not support specific resolution and mode,
     * {@link UVCCamera#setPreviewSize(int, int, int)} throw exception
     * 0:YUYV, other:MJPEG
     */
    private static final int PREVIEW_MODE = 0;
    /**
     * for accessing USB
     */
    private USBMonitor mUSBMonitor;

    private boolean mIsNew = true;
    private UsbControlBlock mCtrlBlock;

    /**
     * Handler to execute camera related methods sequentially on private thread
     */
    private UVCCameraHandler mCameraHandler;
    /**
     * for camera preview display
     */
    private UVCCameraTextureView mUVCCameraView;

    // --- Slip tracking integration ---
    private com.serenegiant.usb.IFrameCallback mTrackingFrameCallback;
    private boolean mTrackingPending = false;

    private ThemedReactContext mContext;

    private final UVCCameraHandler.CameraCallback mCameraDeviceCallback
            = new UVCCameraHandler.CameraCallback() {
        @Override
        public void onOpen(){
            Log.d("AMPA", "callback onOpen: camera opened, refreshing surface");
            // getSurfaceTexture() is destructive (releases + recreates the SurfaceTexture
            // and its GL texture on every call), so resolve it once and reuse.
            SurfaceTexture st = (mUVCCameraView != null) ? mUVCCameraView.getSurfaceTexture() : null;
            if (st != null) {
                mPreviewSurface = new Surface(st);
                Log.d("AMPA", "callback onOpen: recreated preview surface");
            } else {
                Log.d("AMPA", "callback onOpen: could not recreate surface, view=" + (mUVCCameraView != null) + " texture=false");
            }
            mCallback.onCameraOpened();
            startCaptureSession();
            if (mTrackingPending && mTrackingFrameCallback != null && mCameraHandler != null) {
                mTrackingPending = false;
                try {
                    mCameraHandler.setExternalFrameCallback(mTrackingFrameCallback, com.serenegiant.usb.UVCCamera.PIXEL_FORMAT_NV21);
                } catch (final Throwable t) {
                    android.util.Log.w("AMPA", "onOpen: deferred tracking-frame registration failed", t);
                }
            }
        }
        @Override
        public void onClose(){
            Log.d("AMPA", "callback onClose: camera closed");
            mCallback.onCameraClosed();
        }
        @Override
        public void onStartPreview(){
            Log.d("AMPA", "callback onStartPreview: preview started");
            // some USB camera will not return onPictureTaken and onVideoRecorded by uncomment some of bellow, test them by your own
            // updateAutoFocus();
            // updateFlash();
            // updateFocusDepth();
            // updateWhiteBalance();
            // updateZoom();
        }
        @Override
        public void onStopPreview(){}
        @Override
        public void onStartRecording(){}
        @Override
        public void onStopRecording(){}
        @Override
        public void onPictureTaken(Bitmap bitmap){
            Matrix matrix = new Matrix();
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();

            if (mDisplayOrientation % 180 == 90) {
                matrix.postScale((float) height / (float) width, (float) width / (float) height);
                matrix.postRotate(mDisplayOrientation - 180);
            }
            Bitmap bmp = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);

            // if (mImageFormat == ImageFormat.JPEG) {8
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 100, baos);
                ByteBuffer buffer =  ByteBuffer.wrap(baos.toByteArray());

                mCallback.onPictureTaken(baos.toByteArray());
                mCallback.onFramePreview(bmp, bmp.getWidth(), bmp.getHeight(), 0);
            // } else { // TODO: convert bmp to ImageFormat.YUV_420_888 ?
            //     mCallback.onFramePreview(bmp, bmp.getWidth(), bmp.getHeight(), mDisplayOrientation);
            // }
        }
        @Override
        public void onVideoRecorded(String path){
            mCallback.onVideoRecorded(path);
        }
        @Override
        public void onError(final Exception e){
//            Toast.makeText(mContext.getCurrentActivity(), "errorException: " + e, Toast.LENGTH_SHORT).show();
            Log.e(TAG, "errorException: ", e);
        }

        @Override
        public void onPreviewFrame(Bitmap bitmap, int mWidth, int mHeight, int orientation) {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            Matrix matrix = new Matrix();
            if (mDisplayOrientation % 180 == 90) {
                matrix.postScale((float) height / (float) width, (float) width / (float) height);
                matrix.postRotate(mDisplayOrientation - 180);
            }
            Bitmap bmp = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
            mCallback.onFramePreview(bmp, bmp.getWidth(), bmp.getHeight(), 90);
        }
    };

    private int mImageFormat;

    private boolean mIsRecording;

    private final SizeMap mPreviewSizes = new SizeMap();

    private int mFacing;

    private AspectRatio mAspectRatio = Constants.DEFAULT_ASPECT_RATIO;

    private AspectRatio mInitialRatio;

    private boolean mAutoFocus;

    private int mFlash;

    private int mDisplayOrientation;

    private float mFocusDepth;

    private float mZoom;

    private int mWhiteBalance;

    private boolean mIsScanning;

    private Surface mPreviewSurface;

    CameraUvc(Callback callback, PreviewImpl preview, Context context) {
        super(callback, preview);
        Log.d("AMPA", "CameraUvc: constructor called");
        mUVCCameraView = (UVCCameraTextureView) preview.getView();
        mUVCCameraView.setAspectRatio(PREVIEW_WIDTH / (float)PREVIEW_HEIGHT);

        mContext = (ThemedReactContext) context;

        mUSBMonitor = new USBMonitor(mContext.getCurrentActivity(), mOnDeviceConnectListener);
        mCameraHandler = UVCCameraHandler.createHandler(mContext.getCurrentActivity(), mUVCCameraView,
          USE_SURFACE_ENCODER ? 0 : 1, PREVIEW_WIDTH, PREVIEW_HEIGHT, PREVIEW_MODE);

        mCameraHandler.addCallback(mCameraDeviceCallback);

        mImageFormat = mIsScanning ? ImageFormat.YUV_420_888 : ImageFormat.JPEG;
        mPreview.setCallback(new PreviewImpl.Callback() {
            @Override
            public void onSurfaceChanged(Surface  surface) {
                Log.d("AMPA", "onSurfaceChanged: surface=" + (surface != null ? "exists" : "null") + " isCameraOpened=" + isCameraOpened());
                if (!isCameraOpened() || surface == null) {
                    Log.d("AMPA", "onSurfaceChanged: SKIPPED");
                    return;
                }
                Log.d("AMPA", "onSurfaceChanged: starting preview");
                mCameraHandler.startPreview(surface);
            }

            @Override
            public void onSurfaceDestroyed() {
                Log.d("AMPA", "DESTROYING SURFACE");
                mUSBMonitor.unregister();
                if(mCtrlBlock != null) {
                    mCtrlBlock.close();
                }
//                stop();
            }
        });
    }

    private UsbDevice getFirstCameraDevice() {
        List<DeviceFilter> filter = DeviceFilter.getDeviceFilters(mContext.getCurrentActivity(), com.serenegiant.uvccamera.R.xml.device_filter);
        List<UsbDevice> cameraList =  mUSBMonitor.getDeviceList(filter.get(0));
        Log.d("AMPA", "getFirstCameraDevice: found " + cameraList.size() + " camera(s)");
        if(cameraList.isEmpty()) {
            return null;
        }
        UsbDevice firstCameraDevice = cameraList.get(0);
        Log.d("AMPA", "getFirstCameraDevice: " + firstCameraDevice.getDeviceName() + " class=" + firstCameraDevice.getDeviceClass());
        return firstCameraDevice;
    }

    private  void requestCameraPermission(UsbDevice device) {
        if(mUSBMonitor != null && device != null) {
            mUSBMonitor.requestPermission(device);
        }
    }

    private final OnDeviceConnectListener mOnDeviceConnectListener = new OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {
            // Gate on mCtrlBlock (cleared synchronously by onDisconnect/onDettach),
            // not isOpened() — the latter reflects mUVCCamera state which only flips
            // false after MSG_PREVIEW_STOP+MSG_CLOSE drain (~5s). A fast firmware
            // hub-reset that re-enumerates the camera in <5s would be silently
            // dropped by the old gate, leaving the camera attached but unopened.
            final boolean openedFlag = mCameraHandler.isOpened();
            final boolean hasCtrlBlock = mCtrlBlock != null;
            Log.d("AMPA", "onAttach: device=" + device.getDeviceName()
                + " isOpened=" + openedFlag + " hasCtrlBlock=" + hasCtrlBlock);
            if (!hasCtrlBlock) {
                UsbDevice camera = getFirstCameraDevice();
                Log.d("AMPA", "onAttach: requesting permission for camera="
                    + (camera != null ? camera.getDeviceName() : "null")
                    + " (closeInFlight=" + openedFlag + ")");
                requestCameraPermission(camera);
            } else {
                Log.d("AMPA", "onAttach: skipped (mCtrlBlock already set)");
            }
        }

        @Override
        public void onConnect(final UsbDevice device, final UsbControlBlock ctrlBlock, final boolean createNew) {
            Log.d("AMPA", "onConnect: device=" + device.getDeviceName()
                + " class=" + device.getDeviceClass()
                + " hasCtrlBlock=" + (mCtrlBlock != null)
                + " isOpened=" + mCameraHandler.isOpened()
                + " createNew=" + createNew);
            // Skip if we already have a connection — prevents double-connect from
            // queueing two MSG_OPENs (second one destroys the first's preview)
            if (mCtrlBlock != null) {
                Log.d("AMPA", "onConnect: already connected, skipping duplicate");
                return;
            }
            mCtrlBlock = ctrlBlock;
            // Note: open() is queued behind any in-flight MSG_PREVIEW_STOP/MSG_CLOSE
            // on the camera handler thread. handleOpen calls handleClose first, so
            // re-opening while a close is still draining is safe and serialized.
            Log.d("AMPA", "onConnect: enqueueing mCameraHandler.open()");
            mCameraHandler.open(mCtrlBlock);
        }

        @Override
        public void onDisconnect(final UsbDevice device, final UsbControlBlock ctrlBlock) {
            Log.d("AMPA", "onDisconnect: device=" + device.getDeviceName()
                + " isPreviewing=" + (mCameraHandler != null && mCameraHandler.isPreviewing()));
            if (mCameraHandler != null) {
                // stopPreview() blocks the caller on mSync.wait() until the native
                // uvc_stop_streaming drain completes (~5s). Run off the main thread
                // so React Navigation exit animations don't stutter.
                final long t0 = android.os.SystemClock.elapsedRealtime();
                final UsbDevice closingDevice = device;
                Thread closer = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Log.d("AMPA", "UvcCamera-Close: thread started for "
                            + (closingDevice != null ? closingDevice.getDeviceName() : "null"));
                        if (mCameraHandler != null) {
                            if (mCameraHandler.isPreviewing()) {
                                stopCaptureSession();
                            }
                            // Identity-aware close: MSG_CLOSE will be skipped on the
                            // camera handler thread if a faster MSG_OPEN has already
                            // replaced mUVCCamera with a camera for a different device.
                            mCameraHandler.close(closingDevice);
                        }
                        Log.d("AMPA", "UvcCamera-Close: thread finished after "
                            + (android.os.SystemClock.elapsedRealtime() - t0) + "ms");
                    }
                }, "UvcCamera-Close");
                closer.start();
            }
            // Only invalidate mCtrlBlock if it was for the device that just died.
            // Without this guard, an onDisconnect fired synchronously from inside
            // UVCCamera.close() (clone close on the camera handler thread) would
            // null out a *newer* ctrlBlock set by a recent onConnect for a different
            // device — wiping the current connection state mid-recovery.
            if (mCtrlBlock != null) {
                final UsbDevice held = mCtrlBlock.getDevice();
                if (held == null || held.equals(device)) {
                    Log.d("AMPA", "onDisconnect: clearing mCtrlBlock for " + device.getDeviceName());
                    mCtrlBlock = null;
                } else {
                    Log.d("AMPA", "onDisconnect: keeping mCtrlBlock — held="
                        + held.getDeviceName() + " disconnecting=" + device.getDeviceName());
                }
            }
        }

        @Override
        public void onDettach(final UsbDevice device) {
            Log.d("AMPA", "onDettach: device=" + device.getDeviceName()
                + " hadCtrlBlock=" + (mCtrlBlock != null));
            // Same identity guard as onDisconnect — only clear if the dettach is for
            // the device whose ctrlBlock we currently hold.
            if (mCtrlBlock != null) {
                final UsbDevice held = mCtrlBlock.getDevice();
                if (held == null || held.equals(device)) {
                    mCtrlBlock = null;
                }
            }
        }

        @Override
        public void onCancel(final UsbDevice device) {
            Log.d("AMPA", "onCancel: device=" + device.getDeviceName());
        }
    };


    @Override
    boolean start() {
        // if (!chooseCameraIdByFacing()) {
        //     mAspectRatio = mInitialRatio;
        //     return false;
        // }
        // setAspectRatio(mInitialRatio);
        // mInitialRatio = null;

        if (mIsNew) {
            Log.d("AMPA", "start: registering USBMonitor (first time)");
            mUSBMonitor.register();
            mIsNew = false;
        }
        if (mCtrlBlock != null) {
            Log.d("AMPA", "start: opening camera with existing ctrlBlock");
            mCameraHandler.open(mCtrlBlock);
        } else {
            Log.d("AMPA", "start: no ctrlBlock yet");
        }
        // if (mUVCCameraView != null)
        //     mUVCCameraView.onResume();

        return true;
    }

    @Override
    void registerUsbMonitor() {
        if(mUSBMonitor == null) {
            return;
        }
        if(!mUSBMonitor.isRegistered())  {
            mUSBMonitor.register();
        }
    }

    @Override
    void stop() {
        if (mCameraHandler != null) {
            try {
                mCameraHandler.setExternalFrameCallback(null, 0);
            } catch (final Throwable ignored) {
            }
        }
        mTrackingPending = false;
        if (mCameraHandler != null) {
            if (mIsRecording) {
                mCameraHandler.stopRecording();
                mIsRecording = false;
            }
            // mCameraHandler.close() blocks the caller on mSync.wait() until native
            // uvc_stop_streaming drains (~5s worst case). On RN view-drop / onHostPause
            // this runs on the UI thread and freezes React Navigation exit animations.
            final com.serenegiant.usbcameracommon.UVCCameraHandler handler = mCameraHandler;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    handler.close();
                }
            }, "UvcCamera-Stop").start();
        }
        // if (mUVCCameraView != null)
        //     mUVCCameraView.onPause();
    }

    @Override
    void destroy() {
        if (mCameraHandler != null) {
            mCameraHandler.release();
            mCameraHandler = null;
        }
        if (mUSBMonitor != null) {
            mUSBMonitor.destroy();
            mUSBMonitor = null;
        }
        mUVCCameraView = null;
    }

    @Override
    boolean isCameraOpened() {
        if(mCameraHandler == null) {
            return false;
        }
        return mCameraHandler.isOpened();
    }

    @Override
    void setFacing(int facing) {
        if (mFacing == facing) {
            return;
        }
        mFacing = facing;
        if (isCameraOpened()) {
            stop();
            start();
        }
    }

    @Override
    int getFacing() {
        return mFacing;
    }

    @Override
    Set<AspectRatio> getSupportedAspectRatios() {
        return mPreviewSizes.ratios();
    }

    @Override
    boolean setAspectRatio(AspectRatio ratio) {
        if (ratio != null && mPreviewSizes.isEmpty()) {
            mInitialRatio = ratio;
            return false;
        }
        if (ratio == null || ratio.equals(mAspectRatio) ||
                !mPreviewSizes.ratios().contains(ratio)) {
            // TODO: Better error handling
            return false;
        }
        mAspectRatio = ratio;
        if (mCameraHandler.isPreviewing()) {
            stopCaptureSession();
            startCaptureSession();
        }
        return true;
    }

    @Override
    AspectRatio getAspectRatio() {
        return mAspectRatio;
    }

    @Override
    void setAutoFocus(boolean autoFocus) {
        if (mAutoFocus == autoFocus) {
            return;
        }
        mAutoFocus = autoFocus;
        if (isCameraOpened()) {
            updateAutoFocus();
            startCaptureSession();
        }
    }

    @Override
    boolean getAutoFocus() {
        return mAutoFocus;
    }

    @Override
    void setFlash(int flash) {
        if (mFlash == flash) {
            return;
        }
        int saved = mFlash;
        mFlash = flash;
        if (isCameraOpened()) {
            updateFlash();
            startCaptureSession();
        }
    }

    @Override
    int getFlash() {
        return mFlash;
    }

    @Override
    void takePicture() {
        captureStillPicture();
    }

    @Override
    boolean record(String path, int maxDuration, int maxFileSize, boolean recordAudio, CamcorderProfile profile) {
        if (!mIsRecording) {
            mCameraHandler.startRecording();
            mIsRecording = true;
            return true;
        }
        return false;
    }

    @Override
    void stopRecording() {
        if (mIsRecording) {
            stopMediaRecorder();

            if (mCameraHandler.isPreviewing()) {
                stopCaptureSession();
            }
            startCaptureSession();
        }
    }

    @Override
    public void setFocusDepth(float value) {
        if (mFocusDepth == value) {
            return;
        }
        float saved = mFocusDepth;
        mFocusDepth = value;
        if (mCameraHandler.isPreviewing()) {
            updateFocusDepth();
            startCaptureSession();
        }
    }

    @Override
    float getFocusDepth() {
        return mFocusDepth;
    }

    @Override
    public void setZoom(float zoom) {
        if (mZoom == zoom) {
            return;
        }
        float saved = mZoom;
        mZoom = zoom;
        if (mCameraHandler.isPreviewing()) {
            updateZoom();
            startCaptureSession();
        }
    }

    @Override
    float getZoom() {
        return mZoom;
    }

    @Override
    public void setWhiteBalance(int whiteBalance) {
        if (mWhiteBalance == whiteBalance) {
            return;
        }
        int saved = mWhiteBalance;
        mWhiteBalance = whiteBalance;
        if (mCameraHandler.isPreviewing()) {
            updateWhiteBalance();
            startCaptureSession();
       }
    }

    @Override
    public int getWhiteBalance() {
        return mWhiteBalance;
    }

    @Override
    void setScanning(boolean isScanning) {
        if (mIsScanning == isScanning) {
            return;
        }
        mIsScanning = isScanning;
        if (!mIsScanning) {
            mImageFormat = ImageFormat.JPEG;
        } else {
            mImageFormat = ImageFormat.YUV_420_888;
        }
        if (mCameraHandler.isPreviewing()) {
            stopCaptureSession();
        }
        startCaptureSession();
    }

    @Override
    boolean getScanning() {
        return mIsScanning;
    }

    @Override
    void setDisplayOrientation(int displayOrientation) {
        mDisplayOrientation = displayOrientation;
        mPreview.setDisplayOrientation(mDisplayOrientation);
    }

    /**
     * <p>Starts a capture session for camera preview.</p>
     */
    void startCaptureSession() {
        if (!isCameraOpened()) {
            Log.d("AMPA", "startCaptureSession: SKIPPED - camera not opened");
            return;
        }
        Surface surface = getPreviewSurface();
        Log.d("AMPA", "startCaptureSession: starting preview, surface=" + (surface != null ? "exists" : "null"));
        mCameraHandler.startPreview(surface);
    }

    void stopCaptureSession() {
        Log.d("AMPA", "stopCaptureSession: stopping preview");
        mCameraHandler.stopPreview();
    }

    public Surface getPreviewSurface() {
        if (mPreviewSurface != null) {
            return mPreviewSurface;
        }
        return mPreview.getSurface();
    }

    @Override
    public void setPreviewTexture(SurfaceTexture surfaceTexture) {
        if (surfaceTexture != null) {
            Surface previewSurface = new Surface(surfaceTexture);
            mPreviewSurface = previewSurface;
        } else {
            mPreviewSurface = null;
        }

        // it may be called from another thread, so make sure we're in main looper
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (mCameraHandler.isPreviewing()) {
                    stopCaptureSession();
                }
                startCaptureSession();
            }
        });
    }

    @Override
    public Size getPreviewSize() {
        return new Size(mUVCCameraView.getWidth(), mUVCCameraView.getHeight());
    }

    /**
     * Updates the internal state of auto-focus to {@link #mAutoFocus}.
     */
    void updateAutoFocus() {
        if (mAutoFocus) {
            mAutoFocus = mCameraHandler.getAutoFocus();
        }
        mCameraHandler.setAutoFocus(mAutoFocus);
    }

    /**
     * Updates the internal state of flash to {@link #mFlash}.
     */
    void updateFlash() {
        switch (mFlash) {
            case Constants.FLASH_OFF:
                break;
            case Constants.FLASH_ON:
                break;
            case Constants.FLASH_TORCH:
                break;
            case Constants.FLASH_AUTO:
                break;
            case Constants.FLASH_RED_EYE:
                break;
        }
    }

    /**
     * Updates the internal state of focus depth to {@link #mFocusDepth}.
     */
    void updateFocusDepth() {
        if (mAutoFocus) {
          return;
        }
        mCameraHandler.setValue(UVCCamera.CTRL_FOCUS_REL, (int) (mFocusDepth * 100));
    }

    /**
     * Updates the internal state of zoom to {@link #mZoom}.
     */
    void updateZoom() {
        mCameraHandler.setValue(UVCCamera.CTRL_ZOOM_REL, (int) (mZoom * 100));
    }

    /**
     * Updates the internal state of white balance to {@link #mWhiteBalance}.
     */
    void updateWhiteBalance() {
        mCameraHandler.setAutoWhiteBlance(true);
    }

    /**
     * Captures a still picture.
     */
    void captureStillPicture() {
        if (mCameraHandler.isOpened()) {
            mCameraHandler.captureStill();
        }
    }

    private void stopMediaRecorder() {
        mIsRecording = false;
        mCameraHandler.stopRecording();
    }

    /**
     * Install or replace the frame callback used by the slip tracker.
     * If the camera isn't open yet, the registration is deferred until {@code onOpen}.
     */
    public void enableTrackingFrames(com.serenegiant.usb.IFrameCallback callback) {
        mTrackingFrameCallback = callback;
        if (mCameraHandler != null && mCameraHandler.isOpened()) {
            try {
                mCameraHandler.setExternalFrameCallback(callback, com.serenegiant.usb.UVCCamera.PIXEL_FORMAT_NV21);
            } catch (final IllegalArgumentException e) {
                android.util.Log.w("AMPA", "enableTrackingFrames: device does not support NV21 frames", e);
            } catch (final Throwable t) {
                android.util.Log.w("AMPA", "enableTrackingFrames: failed", t);
            }
        } else {
            mTrackingPending = true;
        }
    }

    public void disableTrackingFrames() {
        mTrackingFrameCallback = null;
        mTrackingPending = false;
        if (mCameraHandler != null) {
            try {
                mCameraHandler.setExternalFrameCallback(null, 0);
            } catch (final Throwable t) {
                android.util.Log.w("AMPA", "disableTrackingFrames: failed", t);
            }
        }
    }

    /** Actual negotiated preview width. Falls back to {@link #PREVIEW_WIDTH} if unknown. */
    public int getActualPreviewWidth() {
        if (mCameraHandler != null) {
            int w = mCameraHandler.getActualWidth();
            if (w > 0) return w;
        }
        return PREVIEW_WIDTH;
    }

    /** Actual negotiated preview height. Falls back to {@link #PREVIEW_HEIGHT} if unknown. */
    public int getActualPreviewHeight() {
        if (mCameraHandler != null) {
            int h = mCameraHandler.getActualHeight();
            if (h > 0) return h;
        }
        return PREVIEW_HEIGHT;
    }
}
