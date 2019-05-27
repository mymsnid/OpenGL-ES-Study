package com.askey.dvr.cdr7010.dashcam.simplerecoder;

import android.annotation.SuppressLint;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;

public class Camera extends CameraDevice.StateCallback {

    private final String TAG;
    private CameraManager mCameraManager;
    private String mCameraID;
    private Surface mSurface;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private CameraDevice mCameraDevice;
    private CaptureRequest.Builder mCaptureRequest;
    private CameraCaptureSession mCameraCaptureSession;

    public enum CAM_NAME{
        FRONT,
        BACK
    }

    @SuppressLint("MissingPermission")
    public Camera(CAM_NAME name, CameraManager cameraManager, String cameraID,Surface surface){
        TAG = "Camera_" + name;
        mCameraManager = cameraManager;
        mCameraID = cameraID;
        mSurface = surface;
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    @SuppressLint("MissingPermission")
    public void open(){
        try {
            mCameraManager.openCamera(mCameraID,this,mHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Open camera error, access exception.");
        }
    }

    @Override
    public void onOpened(@NonNull CameraDevice camera) {
        Log.d(TAG, "Opened.");
        List<Surface> list = new ArrayList(1);
        list.add(mSurface);
        mCameraDevice = camera;
        try {
            mCaptureRequest = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            mCaptureRequest.addTarget(mSurface);
            mCaptureRequest.set(CaptureRequest.SENSOR_FRAME_DURATION,66666667l);
            mCameraDevice.createCaptureSession(list, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    mCameraCaptureSession = session;
                    try {
                        session.setRepeatingRequest(mCaptureRequest.build(),null,mHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "Camera capture session fail.");
                }
            },mHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDisconnected(@NonNull CameraDevice camera) {
        Log.d(TAG, "Disconnected.");
    }

    @Override
    public void onClosed(@NonNull CameraDevice camera) {
        Log.d(TAG, "Closed.");
        super.onClosed(camera);
    }

    @Override
    public void onError(@NonNull CameraDevice camera, int error) {
        Log.e(TAG, "Error, error No. = " + error);
    }
}
