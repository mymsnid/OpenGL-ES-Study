package com.askey.dvr.cdr7010.dashcam.simplerecoder;

import android.app.Application;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.Surface;

import com.askey.dvr.cdr7010.dashcam.application.DashCamApplication;

import static android.content.Context.CAMERA_SERVICE;

public class Recorder extends Thread{

    private final String TAG = "SimpleRecorder";

    private CameraManager mCameraManager;

    private Camera mCameraFront;
    private Camera mCameraBack;
    private Render mRenderFront;
    private Render mRenderBack;
    private GLRender mGLRenderBack;
    private VideoEncoder mVideoEncoderFront;
    private VideoEncoder mVideoEncoderBack;
    private MicAudio     mMicAudioRecorder = null;

    public Recorder(Context ctx ,@Nullable final Surface previewSurface) {
        String[] mCameraList;
        CameraCharacteristics cc;
        int facing;
        mCameraManager = (CameraManager) ctx.getSystemService(CAMERA_SERVICE);
        try {
            mCameraList = mCameraManager.getCameraIdList();
            if (mCameraList != null) {
                for (String id : mCameraList) {
                    cc = mCameraManager.getCameraCharacteristics(id);
                    facing = cc.get(CameraCharacteristics.LENS_FACING);
                    if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        Log.d(TAG, "Front camera exists.");
                        mVideoEncoderFront = new VideoEncoder(Camera.CAM_NAME.FRONT,1280,720,15,5000000);
                        mRenderFront = new Render(Camera.CAM_NAME.FRONT,(surface)->{
                            mCameraFront = new Camera(Camera.CAM_NAME.FRONT,mCameraManager,id,new Surface(surface));
                        });
                        if(mMicAudioRecorder == null){
                            mMicAudioRecorder = new MicAudio();
                        }
                    } else if (facing == CameraMetadata.LENS_FACING_BACK) {
                        Log.d(TAG, "Back camera exists.");
                        mVideoEncoderBack = new VideoEncoder(Camera.CAM_NAME.BACK,1920,1080,30,10000000);
                        mGLRenderBack = new GLRender(Camera.CAM_NAME.BACK);
                        mGLRenderBack.startRenderToEncoder(mVideoEncoderBack.mEncoderInputSurface,1920,1080);
                        mGLRenderBack.startRenderToPreview(previewSurface,320,240);
                        mGLRenderBack.startRenderToYuv(1280,720);
                        mCameraBack = new Camera(Camera.CAM_NAME.BACK,mCameraManager,id,new Surface(mGLRenderBack.mSurfaceTexture));
                        mCameraBack.open();
//                        mRenderBack = new Render(Camera.CAM_NAME.BACK,(surface)-> {
//                            mCameraBack = new Camera(Camera.CAM_NAME.BACK,mCameraManager,id,new Surface(surface));
//                            mCameraBack.open();
//                        });
//                        mRenderBack.startRenderToEncoder(mVideoEncoderBack.mEncoderInputSurface,1920,1080);
//                        mRenderBack.startRenderToPreview(previewSurface,320,240);
//                        mRenderBack.startRenderToYuv(1280,720);
                        if(mMicAudioRecorder == null){
                            mMicAudioRecorder = new MicAudio();
                        }
                    }else{
                        Log.e(TAG, "Camera facing unknown.");
                    }
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Get camera list error.");
        }
    }

    @Override
    public void run() {

        while(true){


        }
    }
}
