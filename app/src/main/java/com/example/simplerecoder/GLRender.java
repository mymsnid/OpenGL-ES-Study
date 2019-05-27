package com.askey.dvr.cdr7010.dashcam.simplerecoder;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.location.Location;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Process;
import android.util.Log;
import android.view.Surface;

import com.askey.dvr.cdr7010.dashcam.BuildConfig;
import com.askey.dvr.cdr7010.dashcam.service.GPSStatusManager;
import com.askey.dvr.cdr7010.dashcam.util.LocationUtil;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static android.opengl.EGL14.EGL_ALPHA_SIZE;
import static android.opengl.EGL14.EGL_BLUE_SIZE;
import static android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION;
import static android.opengl.EGL14.EGL_DEFAULT_DISPLAY;
import static android.opengl.EGL14.EGL_GREEN_SIZE;
import static android.opengl.EGL14.EGL_NONE;
import static android.opengl.EGL14.EGL_NO_CONTEXT;
import static android.opengl.EGL14.EGL_NO_SURFACE;
import static android.opengl.EGL14.EGL_RED_SIZE;
import static android.opengl.EGL14.EGL_RENDERABLE_TYPE;

public class GLRender extends Thread {

    public SurfaceTexture mSurfaceTexture;

    private final String TAG;

    private int[] mEGLAttributeList = new int[]{
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_NONE
    };

    private int[] mEGLClientVersion = new int[]{
            EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL_NONE
    };

    private EGLConfig[] mConfig = new EGLConfig[1];
    private int[] mConfigNumber = new int[1];


    private ReentrantLock mFrameSyncLock = new ReentrantLock();
    private Condition mFrameSyncCond = mFrameSyncLock.newCondition();

    private EGLDisplay mDisplay;
    private EGLContext mContext;
    private int[] mCameraTexture = new int[1];

    private GLPreviewTask mGLPreviewTask = null;
    private GLEncoderTask mGLEncoderTask = null;
    private GLYuvTask mGLYuvTask = null;

    private ReentrantLock mReadyLock = new ReentrantLock();
    private Condition mReadyCond = mReadyLock.newCondition();

    public GLRender(Camera.CAM_NAME id) {
        TAG = "GLRender_" + id;
        mReadyLock.lock();
        start();
        mReadyCond.awaitUninterruptibly();
        mReadyLock.unlock();
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        //EGL initialize.
        mDisplay = EGL14.eglGetDisplay(EGL_DEFAULT_DISPLAY);
        EGL14.eglInitialize(mDisplay, null, 0, null, 0);
        EGL14.eglChooseConfig(mDisplay, mEGLAttributeList, 0, mConfig, 0, 1, mConfigNumber, 0);
        EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API);
        mContext = EGL14.eglCreateContext(mDisplay, mConfig[0], EGL_NO_CONTEXT, mEGLClientVersion, 0);
        EGL14.eglMakeCurrent(mDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, mContext);
        //Create textures.
        GLES20.glGenTextures(1, mCameraTexture, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mCameraTexture[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
        mSurfaceTexture = new SurfaceTexture(mCameraTexture[0]);
        mSurfaceTexture.setDefaultBufferSize(1920, 1080);
        mSurfaceTexture.setOnFrameAvailableListener((st) -> {
            mFrameSyncLock.lock();
            mFrameSyncCond.signal();
            mFrameSyncLock.unlock();
        }, null);
        mFrameSyncLock.lock();
        mReadyLock.lock();
        mReadyCond.signal();
        mReadyLock.unlock();
        while (true) {
            mFrameSyncCond.awaitUninterruptibly();
            synchronized (mContext) {
                mSurfaceTexture.updateTexImage();
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,0);
                GLES20.glFlush();
            }
        }
    }

    public void startRenderToPreview(Surface surface, int width, int height) {
        mGLPreviewTask = new GLPreviewTask(mContext,mCameraTexture[0],surface,width,height);
    }

    public void startRenderToEncoder(Surface surface, int width, int height) {
        mGLEncoderTask = new GLEncoderTask(mContext,mCameraTexture[0],surface,width,height);
    }

    public void startRenderToYuv(int width, int height) {
        mGLYuvTask = new GLYuvTask(mContext,mCameraTexture[0],width,height);
    }


}
