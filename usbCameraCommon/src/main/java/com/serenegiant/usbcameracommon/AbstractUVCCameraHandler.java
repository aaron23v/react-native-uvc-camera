/*
 *  UVCCamera
 *  library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2017 saki t_saki@serenegiant.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *  All files in the folder are under this Apache License, Version 2.0.
 *  Files in the libjpeg-turbo, libusb, libuvc, rapidjson folder
 *  may have a different license, see the respective files.
 */

package com.serenegiant.usbcameracommon;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.usb.UsbDevice;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.media.SoundPool;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.widget.Toast;

import com.serenegiant.encoder.MediaAudioEncoder;
import com.serenegiant.encoder.MediaEncoder;
import com.serenegiant.encoder.MediaMuxerWrapper;
import com.serenegiant.encoder.MediaSurfaceEncoder;
import com.serenegiant.encoder.MediaVideoBufferEncoder;
import com.serenegiant.encoder.MediaVideoEncoder;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.widget.CameraViewInterface;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

abstract class AbstractUVCCameraHandler extends Handler {
	private static final boolean DEBUG = true;	// TODO set false on release
	private static final String TAG = "AbsUVCCameraHandler";

	private static final int FRAME_DELAY = 500;

	public interface CameraCallback {
		public void onOpen();
		public void onClose();
		public void onStartPreview();
		public void onStopPreview();
		public void onStartRecording();
		public void onStopRecording();
		public void onPictureTaken(Bitmap bitmap);
		public void onVideoRecorded(String path);
		public void onError(final Exception e);
		public void onPreviewFrame(Bitmap data, int width, int height, int orientation);
	}

	private static final int MSG_OPEN = 0;
	private static final int MSG_CLOSE = 1;
	private static final int MSG_PREVIEW_START = 2;
	private static final int MSG_PREVIEW_STOP = 3;
	private static final int MSG_CAPTURE_STILL = 4;
	private static final int MSG_CAPTURE_START = 5;
	private static final int MSG_CAPTURE_STOP = 6;
	private static final int MSG_MEDIA_UPDATE = 7;
	private static final int MSG_RELEASE = 9;
	private static final int MSG_PREVIEW_RETRY = 10;

	private final WeakReference<AbstractUVCCameraHandler.CameraThread> mWeakThread;
	private volatile boolean mReleased;

	protected AbstractUVCCameraHandler(final CameraThread thread) {
		mWeakThread = new WeakReference<CameraThread>(thread);
	}

	public int getWidth() {
		final CameraThread thread = mWeakThread.get();
		return thread != null ? thread.getWidth() : 0;
	}

	public int getHeight() {
		final CameraThread thread = mWeakThread.get();
		return thread != null ? thread.getHeight() : 0;
	}

	/** Actual negotiated preview width (may differ from {@link #getWidth()} if the camera substituted a nearest size). */
	public int getActualWidth() {
		final CameraThread thread = mWeakThread.get();
		return thread != null ? thread.getActualWidth() : 0;
	}

	/** Actual negotiated preview height (may differ from {@link #getHeight()} if the camera substituted a nearest size). */
	public int getActualHeight() {
		final CameraThread thread = mWeakThread.get();
		return thread != null ? thread.getActualHeight() : 0;
	}

	public boolean isOpened() {
		final CameraThread thread = mWeakThread.get();
		return thread != null && thread.isCameraOpened();
	}

	public boolean isPreviewing() {
		final CameraThread thread = mWeakThread.get();
		return thread != null && thread.isPreviewing();
	}

	public boolean isRecording() {
		final CameraThread thread = mWeakThread.get();
		return thread != null && thread.isRecording();
	}

	public boolean isEqual(final UsbDevice device) {
		final CameraThread thread = mWeakThread.get();
		return (thread != null) && thread.isEqual(device);
	}

	protected boolean isCameraThread() {
		final CameraThread thread = mWeakThread.get();
		return thread != null && (thread.getId() == Thread.currentThread().getId());
	}

	protected boolean isReleased() {
		final CameraThread thread = mWeakThread.get();
		return mReleased || (thread == null);
	}

	protected void checkReleased() {
		if (isReleased()) {
			throw new IllegalStateException("already released");
		}
	}

	public void open(final USBMonitor.UsbControlBlock ctrlBlock) {
		checkReleased();
		sendMessage(obtainMessage(MSG_OPEN, ctrlBlock));
	}

	public void close() {
		close(null);
	}

	/**
	 * Identity-aware close. The expectedDevice is delivered with MSG_CLOSE so that
	 * handleClose can verify the currently open camera still matches the device the
	 * caller meant to close. Under fast hub-reset cascades the message queue can
	 * stack a stale close behind a fresh open for a different device — without the
	 * identity check, MSG_CLOSE would destroy the freshly-opened camera. Pass null
	 * for unconditional close (e.g. user-initiated stop / release).
	 */
	public void close(final UsbDevice expectedDevice) {
		if (DEBUG) Log.v(TAG, "close: expectedDevice=" + (expectedDevice != null ? expectedDevice.getDeviceName() : "null"));
		if (isOpened()) {
			// The identity check must also gate stopPreview(), not just MSG_CLOSE:
			// stopPreview() does removeMessages(MSG_PREVIEW_START), so a stale close
			// thread (old device's onDisconnect racing a fast replug) would delete the
			// NEW camera's queued preview-start — camera open, onCameraReady fired,
			// black screen, and the retry watchdog never arms because it is only
			// scheduled from inside handleStartPreview.
			if (expectedDevice != null) {
				final CameraThread thread = mWeakThread.get();
				final UsbDevice current = thread != null ? thread.getCurrentDevice() : null;
				if (current != null && !expectedDevice.equals(current)) {
					AmpaLog.d("AMPA", "close: SKIPPED stale close — current="
						+ current.getDeviceName() + " expected=" + expectedDevice.getDeviceName());
					return;
				}
			}
			stopPreview();
			sendMessage(obtainMessage(MSG_CLOSE, expectedDevice));
		}
		if (DEBUG) Log.v(TAG, "close:finished");
	}

	public void resize(final int width, final int height) {
		checkReleased();
		throw new UnsupportedOperationException("does not support now");
	}

	protected void startPreview(final Object surface) {
		checkReleased();
		if (!((surface instanceof SurfaceHolder) || (surface instanceof Surface) || (surface instanceof SurfaceTexture))) {
			throw new IllegalArgumentException("surface should be one of SurfaceHolder, Surface or SurfaceTexture");
		}
		sendMessage(obtainMessage(MSG_PREVIEW_START, surface));
	}

	public void stopPreview() {
		if (DEBUG) Log.v(TAG, "stopPreview:");
		removeMessages(MSG_PREVIEW_START);
		stopRecording();
		if (isPreviewing()) {
			final CameraThread thread = mWeakThread.get();
			if (thread == null) return;
			synchronized (thread.mSync) {
				sendEmptyMessage(MSG_PREVIEW_STOP);
				if (!isCameraThread()) {
					// wait for actually preview stopped to avoid releasing Surface/SurfaceTexture
					// while preview is still running.
					// therefore this method will take a time to execute
					try {
						thread.mSync.wait();
					} catch (final InterruptedException e) {
					}
				}
			}
		}
		if (DEBUG) Log.v(TAG, "stopPreview:finished");
	}

	/**
	 * Installs (or clears) a frame callback consumed by code outside this handler
	 * (e.g. slip tracking). When recording starts, the encoder's own frame callback
	 * temporarily replaces it; the original is re-applied automatically when
	 * recording ends. Pass {@code null} to clear.
	 */
	public void setExternalFrameCallback(final IFrameCallback callback, final int pixelFormat) {
		final CameraThread thread = mWeakThread.get();
		if (thread == null) return;
		post(new Runnable() {
			@Override
			public void run() {
				thread.applyExternalFrameCallback(callback, pixelFormat);
			}
		});
	}

	protected void captureStill() {
		checkReleased();
		sendEmptyMessage(MSG_CAPTURE_STILL);
	}

	protected void captureStill(final String path) {
		checkReleased();
		sendMessage(obtainMessage(MSG_CAPTURE_STILL, path));
	}

	public void startRecording() {
		checkReleased();
		sendEmptyMessage(MSG_CAPTURE_START);
	}

	public void stopRecording() {
		sendEmptyMessage(MSG_CAPTURE_STOP);
	}

	public void release() {
		mReleased = true;
//		close();
		sendEmptyMessage(MSG_RELEASE);
	}

	public void addCallback(final CameraCallback callback) {
		checkReleased();
		if (!mReleased && (callback != null)) {
			final CameraThread thread = mWeakThread.get();
			if (thread != null) {
				thread.mCallbacks.add(callback);
			}
		}
	}

	public void removeCallback(final CameraCallback callback) {
		if (callback != null) {
			final CameraThread thread = mWeakThread.get();
			if (thread != null) {
				thread.mCallbacks.remove(callback);
			}
		}
	}

	protected void updateMedia(final String path) {
		sendMessage(obtainMessage(MSG_MEDIA_UPDATE, path));
	}

	public boolean getAutoFocus() {
		checkReleased();
		final CameraThread thread = mWeakThread.get();
		final UVCCamera camera = thread != null ? thread.mUVCCamera : null;
		if (camera != null) {
			return camera.getAutoFocus();
		}
		throw new IllegalStateException();
	}

	public void setAutoFocus(final boolean value) {
		checkReleased();
		final CameraThread thread = mWeakThread.get();
		final UVCCamera camera = thread != null ? thread.mUVCCamera : null;
		if (camera != null) {
			camera.setAutoFocus(value);
		}
		throw new IllegalStateException();
	}

	public boolean getAutoWhiteBlance() {
		checkReleased();
		final CameraThread thread = mWeakThread.get();
		final UVCCamera camera = thread != null ? thread.mUVCCamera : null;
		if (camera != null) {
			return camera.getAutoWhiteBlance();
		}
		throw new IllegalStateException();
	}

	public void setAutoWhiteBlance(final boolean value) {
		checkReleased();
		final CameraThread thread = mWeakThread.get();
		final UVCCamera camera = thread != null ? thread.mUVCCamera : null;
		if (camera != null) {
			camera.setAutoWhiteBlance(value);
		}
		throw new IllegalStateException();
	}

	public boolean checkSupportFlag(final long flag) {
		checkReleased();
		final CameraThread thread = mWeakThread.get();
		return thread != null && thread.mUVCCamera != null && thread.mUVCCamera.checkSupportFlag(flag);
	}

	public int getValue(final int flag) {
		checkReleased();
		final CameraThread thread = mWeakThread.get();
		final UVCCamera camera = thread != null ? thread.mUVCCamera : null;
		if (camera != null) {
			switch (flag) {
			case UVCCamera.CTRL_FOCUS_REL:
				return camera.getFocus();
			case UVCCamera.CTRL_ZOOM_REL:
				return camera.getZoom();
			case UVCCamera.PU_BRIGHTNESS:
				return camera.getBrightness();
			case UVCCamera.PU_CONTRAST:
				return camera.getContrast();
			}
		}
		throw new IllegalStateException();
	}

	public int setValue(final int flag, final int value) {
		checkReleased();
		final CameraThread thread = mWeakThread.get();
		final UVCCamera camera = thread != null ? thread.mUVCCamera : null;
		if (camera != null) {
			switch (flag) {
			case UVCCamera.CTRL_FOCUS_REL:
				camera.setFocus(value);
				return camera.getFocus();
			case UVCCamera.CTRL_ZOOM_REL:
				camera.setZoom(value);
				return camera.getZoom();
			case UVCCamera.PU_BRIGHTNESS:
				camera.setBrightness(value);
				return camera.getBrightness();
			case UVCCamera.PU_CONTRAST:
				camera.setContrast(value);
				return camera.getContrast();
			}
		}
		throw new IllegalStateException();
	}

	public int resetValue(final int flag) {
		checkReleased();
		final CameraThread thread = mWeakThread.get();
		final UVCCamera camera = thread != null ? thread.mUVCCamera : null;
		if (camera != null) {
			if (flag == UVCCamera.PU_BRIGHTNESS) {
				camera.resetBrightness();
				return camera.getBrightness();
			} else if (flag == UVCCamera.PU_CONTRAST) {
				camera.resetContrast();
				return camera.getContrast();
			}
		}
		throw new IllegalStateException();
	}

	@Override
	public void handleMessage(final Message msg) {
		final CameraThread thread = mWeakThread.get();
		if (thread == null) return;
		switch (msg.what) {
		case MSG_OPEN:
			thread.handleOpen((USBMonitor.UsbControlBlock)msg.obj);
			break;
		case MSG_CLOSE:
			thread.handleClose((UsbDevice) msg.obj);
			break;
		case MSG_PREVIEW_START:
			thread.handleStartPreview(msg.obj);
			break;
		case MSG_PREVIEW_STOP:
			thread.handleStopPreview();
			break;
		case MSG_CAPTURE_STILL:
			thread.handleCaptureStill((String)msg.obj);
			break;
		case MSG_CAPTURE_START:
			thread.handleStartRecording();
			break;
		case MSG_CAPTURE_STOP:
			thread.handleStopRecording();
			break;
		case MSG_MEDIA_UPDATE:
			thread.handleUpdateMedia((String)msg.obj);
			break;
		case MSG_RELEASE:
			thread.handleRelease();
			break;
		case MSG_PREVIEW_RETRY:
			thread.handlePreviewRetry(msg.obj);
			break;
		default:
			throw new RuntimeException("unsupported message:what=" + msg.what);
		}
	}

	static final class CameraThread extends Thread {
		private static final String TAG_THREAD = "CameraThread";
		private final Object mSync = new Object();
		private final Class<? extends AbstractUVCCameraHandler> mHandlerClass;
		private final Activity mActivity;
		private final WeakReference<Activity> mWeakParent;
		private final WeakReference<CameraViewInterface> mWeakCameraView;
		private final int mEncoderType;
		private final Set<CameraCallback> mCallbacks = new CopyOnWriteArraySet<CameraCallback>();
		private int mWidth, mHeight, mPreviewMode;
		private float mBandwidthFactor;
		private boolean mIsPreviewing;
		private boolean mIsRecording;
		/**
		 * shutter sound
		 */
		private SoundPool mSoundPool;
		private int mSoundId;
		private AbstractUVCCameraHandler mHandler;
		/**
		 * for accessing UVC camera
		 */
		private UVCCamera mUVCCamera;
		/**
		 * muxer for audio/video recording
		 */
		private MediaMuxerWrapper mMuxer;
		private MediaVideoBufferEncoder mVideoEncoder;

		// External frame callback (e.g. slip tracker). Saved so we can re-apply it
		// after recording temporarily replaces the callback for the encoder.
		private IFrameCallback mExternalFrameCallback;
		private int mExternalFramePixelFormat;

		private long lastFrameProcessedTime;
		private Object mLastPreviewSurface;
		private int mPreviewRetryCount;

		/**
		 *
		 * @param clazz Class extends AbstractUVCCameraHandler
		 * @param parent parent Activity
		 * @param cameraView for still capturing
		 * @param encoderType 0: use MediaSurfaceEncoder, 1: use MediaVideoEncoder, 2: use MediaVideoBufferEncoder
		 * @param width
		 * @param height
		 * @param format either FRAME_FORMAT_YUYV(0) or FRAME_FORMAT_MJPEG(1)
		 * @param bandwidthFactor
		 */
		CameraThread(final Class<? extends AbstractUVCCameraHandler> clazz,
			final Activity parent, final CameraViewInterface cameraView,
			final int encoderType, final int width, final int height, final int format,
			final float bandwidthFactor) {

			super("CameraThread");
			mHandlerClass = clazz;
			mEncoderType = encoderType;
			mWidth = width;
			mHeight = height;
			mPreviewMode = format;
			mBandwidthFactor = bandwidthFactor;
			mActivity = parent;
			mWeakParent = new WeakReference<Activity>(parent);
			mWeakCameraView = new WeakReference<CameraViewInterface>(cameraView);
			loadShutterSound(parent);
		}

		@Override
		protected void finalize() throws Throwable {
			Log.i(TAG, "CameraThread#finalize");
			super.finalize();
		}

		public AbstractUVCCameraHandler getHandler() {
			if (DEBUG) Log.v(TAG_THREAD, "getHandler:");
			synchronized (mSync) {
				if (mHandler == null)
				try {
					mSync.wait();
				} catch (final InterruptedException e) {
				}
			}
			return mHandler;
		}

		public int getWidth() {
			synchronized (mSync) {
				return mWidth;
			}
		}

		public int getActualWidth() {
			synchronized (mSync) {
				return mUVCCamera != null ? mUVCCamera.getCurrentWidth() : mWidth;
			}
		}

		public int getActualHeight() {
			synchronized (mSync) {
				return mUVCCamera != null ? mUVCCamera.getCurrentHeight() : mHeight;
			}
		}

		public int getHeight() {
			synchronized (mSync) {
				return mHeight;
			}
		}

		public boolean isCameraOpened() {
			synchronized (mSync) {
				return mUVCCamera != null;
			}
		}

		public UsbDevice getCurrentDevice() {
			synchronized (mSync) {
				return mUVCCamera != null ? mUVCCamera.getDevice() : null;
			}
		}

		public boolean isPreviewing() {
			synchronized (mSync) {
				return mUVCCamera != null && mIsPreviewing;
			}
		}

		public boolean isRecording() {
			synchronized (mSync) {
				return (mUVCCamera != null) && (mMuxer != null);
			}
		}

		public boolean isEqual(final UsbDevice device) {
			return (mUVCCamera != null) && (mUVCCamera.getDevice() != null) && mUVCCamera.getDevice().equals(device);
		}

		public void handleOpen(final USBMonitor.UsbControlBlock ctrlBlock) {
			AmpaLog.d("AMPA", "handleOpen: closing stale camera first, isPreviewing=" + mIsPreviewing);
			handleClose();
			mFrameCount = 0;
			try {
				AmpaLog.d("AMPA", "handleOpen: opening camera device=" + ctrlBlock.getDeviceName());
				final UVCCamera camera = new UVCCamera();
				camera.open(ctrlBlock);
				synchronized (mSync) {
					mUVCCamera = camera;
				}
				AmpaLog.d("AMPA", "handleOpen: SUCCESS - camera opened");
				callOnOpen();
			} catch (final Exception e) {
				AmpaLog.e("AMPA", "handleOpen: FAILED - " + e.getMessage());
				callOnError(e);
			}
			if (DEBUG) Log.i(TAG, "supportedSize:" + (mUVCCamera != null ? mUVCCamera.getSupportedSize() : null));
		}

		/**
		 * Identity-aware variant. If expectedDevice is non-null and the currently open
		 * camera is for a different device, skip the close — the queued close is stale
		 * (a faster open already replaced the camera). Falls through to the normal
		 * unconditional close otherwise.
		 */
		public void handleClose(final UsbDevice expectedDevice) {
			if (expectedDevice != null) {
				final UVCCamera camera;
				synchronized (mSync) { camera = mUVCCamera; }
				if (camera != null) {
					final UsbDevice cur = camera.getDevice();
					if (cur != null && !expectedDevice.equals(cur)) {
						AmpaLog.d("AMPA", "handleClose: SKIPPED stale close — current="
							+ cur.getDeviceName() + " expected=" + expectedDevice.getDeviceName());
						return;
					}
				}
			}
			handleClose();
		}

		public void handleClose() {
			if (DEBUG) Log.v(TAG_THREAD, "handleClose:");
			// Cancel any pending preview retry
			if (mHandler != null) {
				mHandler.removeMessages(MSG_PREVIEW_RETRY);
			}
			mPreviewRetryCount = 0;
			handleStopRecording();
			final UVCCamera camera;
			synchronized (mSync) {
				camera = mUVCCamera;
				mUVCCamera = null;
				// Must be cleared here, not after camera.stopPreview() below — stopping
				// a physically-removed device can throw, and the catch would leave
				// mIsPreviewing latched true with mUVCCamera already null. Nothing can
				// clear it after that (isPreviewing() reports false once the camera is
				// null), so the next open's handleStartPreview would be skipped forever.
				mIsPreviewing = false;
			}
			if (camera != null) {
				try {
					AmpaLog.d("AMPA",  "handleCLose Step:stopPreview()");
					camera.stopPreview();
					AmpaLog.d("AMPA",  "handleCLose Step:close()");
					camera.close();
					AmpaLog.d("AMPA",  "handleCLose Step:destroy()");
					camera.destroy();
					AmpaLog.d("AMPA",  "handleCLose Step:callOnClose()");
					callOnClose();
				}catch(Exception  e) {
					AmpaLog.d("AMPA", e.toString());
				}
				if (DEBUG) Log.v(TAG_THREAD, "called camera.stopPreview,close,destroy:");
			}
			if (DEBUG) Log.v(TAG_THREAD, "Finished camera.stopPreview,close,destroy:");
		}

		public void handleStartPreview(final Object surface) {
			AmpaLog.d("AMPA", "handleStartPreview: camera=" + (mUVCCamera != null) + " isPreviewing=" + mIsPreviewing + " surface=" + (surface != null ? surface.getClass().getSimpleName() : "null"));
			if ((mUVCCamera == null) || mIsPreviewing) {
				AmpaLog.d("AMPA", "handleStartPreview: SKIPPED - camera=" + (mUVCCamera != null) + " isPreviewing=" + mIsPreviewing);
				return;
			}
			try {
				Size nearestSize = mUVCCamera.getNearestSize(mWidth, mHeight, UVCCamera.FRAME_FORMAT_MJPEG);
				AmpaLog.d("AMPA", "handleStartPreview: MJPEG nearestSize=" + (nearestSize != null ? nearestSize.width + "x" + nearestSize.height : "null") + " requested=" + mWidth + "x" + mHeight);
				if (nearestSize == null) {
					mUVCCamera.setPreviewSize(mWidth, mHeight, 1, 31, UVCCamera.FRAME_FORMAT_MJPEG, mBandwidthFactor);
				} else {
					mUVCCamera.setPreviewSize(nearestSize.width, nearestSize.height, 1, 31, UVCCamera.FRAME_FORMAT_MJPEG, mBandwidthFactor);
				}
			} catch (final IllegalArgumentException e) {
				AmpaLog.d("AMPA", "handleStartPreview: MJPEG failed, falling back to YUYV: " + e.getMessage());
				try {
					Size nearestSize = mUVCCamera.getNearestSize(mWidth, mHeight, UVCCamera.FRAME_FORMAT_YUYV);
					AmpaLog.d("AMPA", "handleStartPreview: YUYV nearestSize=" + (nearestSize != null ? nearestSize.width + "x" + nearestSize.height : "null"));
					if (nearestSize == null) {
						mUVCCamera.setPreviewSize(mWidth, mHeight, 1, 31, UVCCamera.FRAME_FORMAT_YUYV, mBandwidthFactor);
					} else {
						mUVCCamera.setPreviewSize(nearestSize.width, nearestSize.height, 1, 31, UVCCamera.FRAME_FORMAT_YUYV, mBandwidthFactor);
					}
				} catch (final IllegalArgumentException e1) {
					AmpaLog.e("AMPA", "handleStartPreview: YUYV also failed: " + e1.getMessage());
					callOnError(e1);
					return;
				}
			}
			if (surface instanceof SurfaceHolder) {
				AmpaLog.d("AMPA", "handleStartPreview: setPreviewDisplay(SurfaceHolder)");
				mUVCCamera.setPreviewDisplay((SurfaceHolder)surface);
			} if (surface instanceof Surface) {
				AmpaLog.d("AMPA", "handleStartPreview: setPreviewDisplay(Surface) hashCode=" + surface.hashCode());
				mUVCCamera.setPreviewDisplay((Surface)surface);
			} else {
				AmpaLog.d("AMPA", "handleStartPreview: setPreviewTexture(SurfaceTexture)");
				mUVCCamera.setPreviewTexture((SurfaceTexture)surface);
			}
			mUVCCamera.startPreview();
			mUVCCamera.updateCameraParams();
			mUVCCamera.setFrameCallback(mIFramePreviewCallback, UVCCamera.PIXEL_FORMAT_RAW);

			synchronized (mSync) {
				mIsPreviewing = true;
			}
			AmpaLog.d("AMPA", "handleStartPreview: SUCCESS - preview is now active");
			callOnStartPreview();

			// Store surface and schedule frame check for Java-level retry
			mLastPreviewSurface = surface;
			if (mHandler != null) {
				mHandler.removeMessages(MSG_PREVIEW_RETRY);
				Message retryMsg = mHandler.obtainMessage(MSG_PREVIEW_RETRY, surface);
				mHandler.sendMessageDelayed(retryMsg, 2000);
				AmpaLog.d("AMPA", "handleStartPreview: scheduled frame check in 2s");
			}
		}

		public void handleStopPreview() {
			AmpaLog.d("AMPA", "handleStopPreview: isPreviewing=" + mIsPreviewing);
			// Cancel any pending retry
			if (mHandler != null) {
				mHandler.removeMessages(MSG_PREVIEW_RETRY);
			}
			mPreviewRetryCount = 0;
			if (mIsPreviewing) {
				if (mUVCCamera != null) {
					mUVCCamera.stopPreview();
				}
				synchronized (mSync) {
					mIsPreviewing = false;
					mSync.notifyAll();
				}
				AmpaLog.d("AMPA", "handleStopPreview: preview stopped, isPreviewing=false");
				callOnStopPreview();
			}
		}

		public void handlePreviewRetry(final Object surface) {
			int maxJavaRetries = 3;
			AmpaLog.d("AMPA", "handlePreviewRetry: frameCount=" + mFrameCount + " isPreviewing=" + mIsPreviewing + " camera=" + (mUVCCamera != null) + " retryCount=" + mPreviewRetryCount + "/" + maxJavaRetries);

			if (mUVCCamera == null) {
				AmpaLog.d("AMPA", "handlePreviewRetry: no camera, skipping");
				return;
			}

			if (mFrameCount > 0) {
				AmpaLog.d("AMPA", "handlePreviewRetry: frames are flowing (" + mFrameCount + "), no retry needed");
				mPreviewRetryCount = 0;
				return;
			}

			if (mPreviewRetryCount >= maxJavaRetries) {
				AmpaLog.e("AMPA", "handlePreviewRetry: exhausted " + maxJavaRetries + " retries, giving up");
				mPreviewRetryCount = 0;
				return;
			}

			mPreviewRetryCount++;
			AmpaLog.d("AMPA", "handlePreviewRetry: no frames detected, restarting preview (attempt " + mPreviewRetryCount + "/" + maxJavaRetries + ")");

			// Stop current preview
			if (mIsPreviewing) {
				mUVCCamera.stopPreview();
				synchronized (mSync) {
					mIsPreviewing = false;
				}
			}

			// Reset frame count and restart
			mFrameCount = 0;

			// Re-run handleStartPreview with the stored surface
			handleStartPreview(surface);
		}

		public void handleCaptureStill(final String path) {
			if (DEBUG) Log.v(TAG_THREAD, "handleCaptureStill:");
			final Activity parent = mWeakParent.get();
			if (parent == null) return;
			mSoundPool.play(mSoundId, 0.2f, 0.2f, 0, 0, 1.0f);	// play shutter sound
			try {
				final Bitmap bitmap = mWeakCameraView.get().captureStillImage();
				callOnPictureTaken(bitmap);
				// // get buffered output stream for saving a captured still image as a file on external storage.
				// // the file name is came from current time.
				// // You should use extension name as same as CompressFormat when calling Bitmap#compress.
				// final File outputFile = TextUtils.isEmpty(path)
				// 	? MediaMuxerWrapper.getCaptureFile(Environment.DIRECTORY_DCIM, ".png")
				// 	: new File(path);
				// final BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(outputFile));
				// try {
				// 	try {
				// 		bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
				// 		os.flush();
				// 		mHandler.sendMessage(mHandler.obtainMessage(MSG_MEDIA_UPDATE, outputFile.getPath()));
				// 	} catch (final IOException e) {
				// 	}
				// } finally {
				// 	os.close();
				// }
			} catch (final Exception e) {
				callOnError(e);
			}
		}

		public void handleStartRecording() {
			if (DEBUG) Log.v(TAG_THREAD, "handleStartRecording:");
			try {
				if ((mUVCCamera == null) || (mMuxer != null)) return;
				final MediaMuxerWrapper muxer = new MediaMuxerWrapper(".mp4");	// if you record audio only, ".m4a" is also OK.
				MediaVideoBufferEncoder videoEncoder = null;
				int width = getWidth();
				int height = getHeight();
				Size nearestSize = mUVCCamera.getNearestSize(width, height, UVCCamera.FRAME_FORMAT_MJPEG);
				if (nearestSize != null) {
					width = nearestSize.width;
					height = nearestSize.height;
				} else {
					nearestSize = mUVCCamera.getNearestSize(width, height, UVCCamera.FRAME_FORMAT_YUYV);
					if (nearestSize != null) {
						width = nearestSize.width;
						height = nearestSize.height;
					}
				}
				switch (mEncoderType) {
				case 1:	// for video capturing using MediaVideoEncoder
					new MediaVideoEncoder(muxer, width, height, mMediaEncoderListener);
					break;
				case 2:	// for video capturing using MediaVideoBufferEncoder
					videoEncoder = new MediaVideoBufferEncoder(muxer, width, height, mMediaEncoderListener);
					break;
				// case 0:	// for video capturing using MediaSurfaceEncoder
				default:
					new MediaSurfaceEncoder(muxer, width, height, mMediaEncoderListener);
					break;
				}
				if (true) {
					// for audio capturing
					new MediaAudioEncoder(muxer, mMediaEncoderListener);
				}
				muxer.prepare();
				muxer.startRecording();
				if (videoEncoder != null) {
					mUVCCamera.setFrameCallback(mIFrameCallback, UVCCamera.PIXEL_FORMAT_NV21);
				}
				synchronized (mSync) {
					mMuxer = muxer;
					mVideoEncoder = videoEncoder;
				}
				callOnStartRecording();
			} catch (final IOException e) {
				callOnError(e);
				Log.e(TAG, "startCapture:", e);
			}
		}

		/**
		 * Worker-thread method. Called via {@link AbstractUVCCameraHandler#setExternalFrameCallback}.
		 * Records the request and applies it immediately unless recording is active —
		 * in which case the recording callback owns the slot until {@code handleStopRecording}
		 * re-applies the external callback.
		 */
		void applyExternalFrameCallback(final IFrameCallback callback, final int pixelFormat) {
			mExternalFrameCallback = callback;
			mExternalFramePixelFormat = pixelFormat;
			if (mUVCCamera != null && mMuxer == null) {
				try {
					mUVCCamera.setFrameCallback(countingFrameCallback(callback), pixelFormat);
				} catch (final Throwable t) {
					Log.w(TAG_THREAD, "applyExternalFrameCallback: setFrameCallback failed", t);
				}
			}
		}

		/**
		 * Wraps a frame callback so each delivered frame increments {@link #mFrameCount}.
		 * The UVCCamera exposes a single frame-callback slot, so when an external consumer
		 * (e.g. the slip tracker) owns it, the internal {@link #mIFramePreviewCallback} no
		 * longer runs. Without this, the preview-liveness watchdog ({@link #handlePreviewRetry})
		 * would see zero frames and trigger a spurious restart loop. Counting here keeps the
		 * watchdog accurate regardless of which consumer currently holds the slot.
		 */
		private IFrameCallback countingFrameCallback(final IFrameCallback delegate) {
			return new IFrameCallback() {
				@Override
				public void onFrame(final ByteBuffer frame) {
					mFrameCount++;
					if (delegate != null) {
						delegate.onFrame(frame);
					}
				}
			};
		}

		public void handleStopRecording() {
			if (DEBUG) Log.v(TAG_THREAD, "handleStopRecording:mMuxer=" + mMuxer);
			final MediaMuxerWrapper muxer;
			synchronized (mSync) {
				muxer = mMuxer;
				mMuxer = null;
				mVideoEncoder = null;
				if (mUVCCamera != null) {
					mUVCCamera.stopCapture();
				}
			}
			try {
				mWeakCameraView.get().setVideoEncoder(null);
			} catch (final Exception e) {
				// ignore
			}
			if (muxer != null) {
				muxer.stopRecording();
				mUVCCamera.setFrameCallback(null, 0);
				// Re-apply external (tracking) frame callback if one was registered.
				if (mExternalFrameCallback != null && mUVCCamera != null) {
					try {
						mUVCCamera.setFrameCallback(countingFrameCallback(mExternalFrameCallback), mExternalFramePixelFormat);
					} catch (final Throwable t) {
						Log.w(TAG_THREAD, "re-apply external frame callback failed", t);
					}
				}
				// you should not wait here
				callOnStopRecording();
			}
			if (DEBUG) Log.v(TAG_THREAD, "handleStopRecording:Fineshed");
		}

		private final IFrameCallback mIFrameCallback = new IFrameCallback() {
			@Override
			public void onFrame(final ByteBuffer frame) {
				final MediaVideoBufferEncoder videoEncoder;
				synchronized (mSync) {
					videoEncoder = mVideoEncoder;
				}
				if (videoEncoder != null) {
					videoEncoder.frameAvailableSoon();
					videoEncoder.encode(frame);
				}
			}
		};

		private int mFrameCount = 0;
		private final IFrameCallback mIFramePreviewCallback = new IFrameCallback() {
			@Override
			public void onFrame(final ByteBuffer frame) {
				mFrameCount++;
				if (mFrameCount == 1 || mFrameCount == 5 || mFrameCount == 30) {
					AmpaLog.d("AMPA", "onFrame: frame #" + mFrameCount + " received, size=" + (frame != null ? frame.remaining() : 0));
				}
				synchronized (mSync) {
				}
				if(!shouldProcessFrame()) {
					return;
				}

				final Activity parent = mWeakParent.get();
				if (parent == null) return;
				try {
					final Bitmap bitmap = mWeakCameraView.get().captureStillImage();
					if(bitmap == null) {
						if (mFrameCount <= 5) {
							AmpaLog.d("AMPA", "onFrame: bitmap is null at frame #" + mFrameCount);
						}
						return;
					}
					callOnPreviewFrame(bitmap);
				} catch (final Exception e) {
					if (mFrameCount <= 5) {
						AmpaLog.e("AMPA", "onFrame: exception at frame #" + mFrameCount + ": " + e.getMessage());
					}
				}
			}
		};

		private boolean shouldProcessFrame() {
			long currentTime = System.currentTimeMillis();
			boolean isTimeThresholdPassed = currentTime - lastFrameProcessedTime >= FRAME_DELAY;
			if (isTimeThresholdPassed) {
				lastFrameProcessedTime = currentTime;
			}
			return isTimeThresholdPassed;
		}

		private Bitmap getOutputImage(ByteBuffer output){
			output.rewind();
			int outputWidth = 384;
			int outputHeight = 384;
			Bitmap bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
			int [] pixels = new int[outputWidth * outputHeight];
			for (int i = 0; i < outputWidth * outputHeight; i++) {
				int a = 0xFF;

				float r = output.getFloat() * 255.0f;
				float g = output.getFloat() * 255.0f;
				float b = output.getFloat() * 255.0f;

				pixels[i] = a << 24 | ((int) r << 16) | ((int) g << 8) | (int) b;
			}
			bitmap.setPixels(pixels, 0, outputWidth, 0, 0, outputWidth, outputHeight);
			return bitmap;
		}


		public void handleUpdateMedia(final String path) {
			if (DEBUG) Log.v(TAG_THREAD, "handleUpdateMedia:path=" + path);
			final Activity parent = mWeakParent.get();
			final boolean released = (mHandler == null) || mHandler.mReleased;
			if (parent != null && parent.getApplicationContext() != null) {
				try {
					if (DEBUG) Log.i(TAG, "MediaScannerConnection#scanFile");
					MediaScannerConnection.scanFile(parent.getApplicationContext(), new String[]{ path }, null, null);
				} catch (final Exception e) {
					Log.e(TAG, "handleUpdateMedia:", e);
				}
				if (released || parent.isDestroyed())
					handleRelease();
			} else {
				Log.w(TAG, "MainActivity already destroyed");
				// give up to add this movie to MediaStore now.
				// Seeing this movie on Gallery app etc. will take a lot of time.
				handleRelease();
			}
		}

		public void handleRelease() {
			if (DEBUG) Log.v(TAG_THREAD, "handleRelease:mIsRecording=" + mIsRecording);
			handleClose();
			mCallbacks.clear();
			if (!mIsRecording) {
				mHandler.mReleased = true;
				if (DEBUG) Log.v(TAG_THREAD, "Quiting Lopper Thread");
				Looper.myLooper().quit();
			}
			if (DEBUG) Log.v(TAG_THREAD, "handleRelease:finished");
		}

		private final MediaEncoder.MediaEncoderListener mMediaEncoderListener = new MediaEncoder.MediaEncoderListener() {
			@Override
			public void onPrepared(final MediaEncoder encoder) {
				if (DEBUG) Log.v(TAG, "onPrepared:encoder=" + encoder);
				mIsRecording = true;
				if (encoder instanceof MediaVideoEncoder)
				try {
					mWeakCameraView.get().setVideoEncoder((MediaVideoEncoder)encoder);
				} catch (final Exception e) {
					Log.e(TAG, "onPrepared:", e);
				}
				if (encoder instanceof MediaSurfaceEncoder)
				try {
					mWeakCameraView.get().setVideoEncoder((MediaSurfaceEncoder)encoder);
					mUVCCamera.startCapture(((MediaSurfaceEncoder)encoder).getInputSurface());
				} catch (final Exception e) {
					Log.e(TAG, "onPrepared:", e);
				}
			}

			@Override
			public void onStopped(final MediaEncoder encoder) {
				if (DEBUG) Log.v(TAG_THREAD, "onStopped:encoder=" + encoder);
				if ((encoder instanceof MediaVideoEncoder)
					|| (encoder instanceof MediaSurfaceEncoder))
				try {
					mIsRecording = false;
					final Activity parent = mWeakParent.get();
					mWeakCameraView.get().setVideoEncoder(null);
					synchronized (mSync) {
						if (mUVCCamera != null) {
							mUVCCamera.stopCapture();
						}
					}
					final String path = encoder.getOutputPath();
					if (!TextUtils.isEmpty(path)) {
						callOnVideoRecorded(path);
						mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_MEDIA_UPDATE, path), 1000);
					} else {
						final boolean released = (mHandler == null) || mHandler.mReleased;
						if (released || parent == null || parent.isDestroyed()) {
							handleRelease();
						}
					}
				} catch (final Exception e) {
					Log.e(TAG, "onPrepared:", e);
				}
			}
		};

		/**
		 * prepare and load shutter sound for still image capturing
		 */
		@SuppressWarnings("deprecation")
		private void loadShutterSound(final Context context) {
	    	// get system stream type using reflection
	        int streamType;
	        try {
	            final Class<?> audioSystemClass = Class.forName("android.media.AudioSystem");
	            final Field sseField = audioSystemClass.getDeclaredField("STREAM_SYSTEM_ENFORCED");
	            streamType = sseField.getInt(null);
	        } catch (final Exception e) {
	        	streamType = AudioManager.STREAM_SYSTEM;	// set appropriate according to your app policy
	        }
	        if (mSoundPool != null) {
	        	try {
	        		mSoundPool.release();
	        	} catch (final Exception e) {
	        	}
	        	mSoundPool = null;
	        }
	        // load shutter sound from resource
		    mSoundPool = new SoundPool(2, streamType, 0);
		    mSoundId = mSoundPool.load(context, R.raw.camera_click, 1);
		}

		@Override
		public void run() {
			Looper.prepare();
			AbstractUVCCameraHandler handler = null;
			try {
				final Constructor<? extends AbstractUVCCameraHandler> constructor = mHandlerClass.getDeclaredConstructor(CameraThread.class);
				handler = constructor.newInstance(this);
			} catch (final NoSuchMethodException e) {
				Log.w(TAG, e);
			} catch (final IllegalAccessException e) {
				Log.w(TAG, e);
			} catch (final InstantiationException e) {
				Log.w(TAG, e);
			} catch (final InvocationTargetException e) {
				Log.w(TAG, e);
			}
			if (handler != null) {
				synchronized (mSync) {
					mHandler = handler;
					mSync.notifyAll();
				}
				Looper.loop();
				if (mSoundPool != null) {
					mSoundPool.release();
					mSoundPool = null;
				}
				if (mHandler != null) {
					mHandler.mReleased = true;
				}
			}
			mCallbacks.clear();
			synchronized (mSync) {
				mHandler = null;
				mSync.notifyAll();
			}
		}

		private void callOnOpen() {
			for (final CameraCallback callback: mCallbacks) {
				try {
					callback.onOpen();
				} catch (final Exception e) {
					mCallbacks.remove(callback);
					Log.w(TAG, e);
				}
			}
		}

		private void callOnClose() {
			for (final CameraCallback callback: mCallbacks) {
				try {
					callback.onClose();
				} catch (final Exception e) {
					mCallbacks.remove(callback);
					Log.w(TAG, e);
				}
			}
		}

		private void callOnStartPreview() {
			for (final CameraCallback callback: mCallbacks) {
				try {
					callback.onStartPreview();
				} catch (final Exception e) {
					mCallbacks.remove(callback);
					Log.w(TAG, e);
				}
			}
		}

		private void callOnStopPreview() {
			for (final CameraCallback callback: mCallbacks) {
				try {
					callback.onStopPreview();
				} catch (final Exception e) {
					mCallbacks.remove(callback);
					Log.w(TAG, e);
				}
			}
		}

		private void callOnStartRecording() {
			for (final CameraCallback callback: mCallbacks) {
				try {
					callback.onStartRecording();
				} catch (final Exception e) {
					mCallbacks.remove(callback);
					Log.w(TAG, e);
				}
			}
		}

		private void callOnStopRecording() {
			for (final CameraCallback callback: mCallbacks) {
				try {
					callback.onStopRecording();
				} catch (final Exception e) {
					mCallbacks.remove(callback);
					Log.w(TAG, e);
				}
			}
		}

		private void callOnPictureTaken(Bitmap bitmap) {
			for (final CameraCallback callback: mCallbacks) {
				try {
					callback.onPictureTaken(bitmap);
				} catch (final Exception e) {
					mCallbacks.remove(callback);
					Log.w(TAG, e);
				}
			}
		}

		private void callOnVideoRecorded(String path) {
			for (final CameraCallback callback: mCallbacks) {
				try {
					callback.onVideoRecorded(path);
				} catch (final Exception e) {
					mCallbacks.remove(callback);
					Log.w(TAG, e);
				}
			}
		}

		private void callOnPreviewFrame(Bitmap data) {
			for (final CameraCallback callback: mCallbacks) {
				try {
					callback.onPreviewFrame(data, mWidth, mHeight, 0);
				} catch (final Exception e) {
					mCallbacks.remove(callback);
					Log.w(TAG, e);
				}
			}
		}

		private void callOnError(final Exception e) {
			for (final CameraCallback callback: mCallbacks) {
				try {
					callback.onError(e);
				} catch (final Exception e1) {
					mCallbacks.remove(callback);
					Log.w(TAG, e);
				}
			}
		}
	}
}
