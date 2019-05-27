package com.askey.dvr.cdr7010.dashcam.simplerecoder;

import android.media.MediaCodec;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.IntDef;
import android.util.Log;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
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


public class FastBuffer {

    final String TAG = "FAST_BUFFER";

    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private EGLDisplay mDisplay;
    private EGLContext mContext;
    private boolean mIsReady;
    private ReentrantLock mLock;
    private Condition mCond;
    private ByteBuffer mInputBuffer;
    private ByteBuffer mOutputBuffer;
    private int mBufferSize;

    private FastBufferInfo mFastBufferInfo;
    private Queue<FastBufferInfo> mBufferInfoQueue;

    public FastBuffer(@BufferSize int size) {
        mBufferSize = size;
        mIsReady = false;
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mLock = new ReentrantLock();
        mCond = mLock.newCondition();
        mInputBuffer = ByteBuffer.allocateDirect(mBufferSize);
        mOutputBuffer = ByteBuffer.allocateDirect(mBufferSize);
        mBufferInfoQueue = new ArrayBlockingQueue<>(15);
        mHandler.post(() -> {
            int[] attributes = new int[]{
                    EGL_RED_SIZE, 8,
                    EGL_GREEN_SIZE, 8,
                    EGL_BLUE_SIZE, 8,
                    EGL_ALPHA_SIZE, 8,
                    EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_NONE
            };
            int[] version = new int[]{
                    EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL_NONE
            };
            EGLConfig[] config = new EGLConfig[1];
            int[] config_num = new int[1];
            mDisplay = EGL14.eglGetDisplay(EGL_DEFAULT_DISPLAY);
            EGL14.eglInitialize(mDisplay, null, 0, null, 0);
            EGL14.eglChooseConfig(mDisplay, attributes, 0, config, 0, 1, config_num, 0);
            EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API);
            mContext = EGL14.eglCreateContext(mDisplay, config[0], EGL_NO_CONTEXT, version, 0);
            EGL14.eglMakeCurrent(mDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, mContext);
            mIsReady = true;
        });
    }

    public void beginPutBuffer() {
        mFastBufferInfo = new FastBufferInfo();
        mInputBuffer.clear();
    }

    public void putBuffer(MediaCodec.BufferInfo info, ByteBuffer data) {
        mFastBufferInfo.mInfoList.add(info);
        mInputBuffer.put(data);
    }

    public void finishedPutBuffer() {
        mFastBufferInfo.finishAddInfo();
        mLock.lock();
        mHandler.post(() -> {
            GLES20.glGenFramebuffers(1, mFastBufferInfo.mFrameBuffer, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFastBufferInfo.mFrameBuffer[0]);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glGenTextures(1, mFastBufferInfo.mDepthBuffer, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mFastBufferInfo.mDepthBuffer[0]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_DEPTH_COMPONENT, 1024, 512, 0, GLES20.GL_DEPTH_COMPONENT, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_TEXTURE_2D, mFastBufferInfo.mDepthBuffer[0], 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
            GLES20.glGenTextures(1, mFastBufferInfo.mColorBuffer, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mFastBufferInfo.mColorBuffer[0]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, mFastBufferInfo.mType, 1024, 512, 0, mFastBufferInfo.mType, GLES20.GL_UNSIGNED_BYTE, mInputBuffer);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, mFastBufferInfo.mColorBuffer[0], 0);
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.e(TAG, "GLBuffer Texture Configure Frame Buffer Fail.");
            }
            mLock.lock();
            mCond.signal();
            mLock.unlock();
        });
        mCond.awaitUninterruptibly();
        mLock.unlock();
        Log.d(TAG, "Put new media package to storage, buffer id = " + mFastBufferInfo.mColorBuffer[0]);
    }

    public void release() {
        mLock.lock();
        mHandler.post(() -> {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            for (FastBufferInfo info : mBufferInfoQueue) {
                GLES20.glDeleteFramebuffers(1, info.mFrameBuffer, 0);
                GLES20.glDeleteTextures(1, info.mDepthBuffer, 0);
                GLES20.glDeleteTextures(1, info.mColorBuffer, 0);
            }
            EGL14.eglDestroyContext(mDisplay, mContext);
            EGL14.eglTerminate(mDisplay);
            EGL14.eglReleaseThread();
            mLock.lock();
            mCond.signal();
            mLock.unlock();
        });
        mCond.awaitUninterruptibly();
        mLock.unlock();
        mHandlerThread.quitSafely();
        mIsReady = false;
        mInputBuffer = null;
    }

    private class FastBufferInfo {
        int[] mFrameBuffer;
        int[] mDepthBuffer;
        int[] mColorBuffer;
        int mType;
        List<MediaCodec.BufferInfo> mInfoList;

        public FastBufferInfo() {
            mFrameBuffer = new int[1];
            mDepthBuffer = new int[1];
            mColorBuffer = new int[1];
            mInfoList = new ArrayList<>(32);
        }

        public void finishAddInfo() {
            int size = 0;
            for (MediaCodec.BufferInfo info:mInfoList){
                size = size + info.size;
            }
            if(size > mBufferSize){
                Log.e(TAG, "Data size exceeds buffer size, this is not normal.");
            }
            if (mBufferSize <= 1572864) {
                mType = GLES20.GL_RGB;
            } else if (mBufferSize <= 2097152) {
                mType = GLES20.GL_RGBA;
            } else {
                Log.e(TAG, "It should never run to here.");
            }
            mInputBuffer.position(0);
            mInputBuffer.limit(mBufferSize);
        }
    }

    public static final int SIZE_1080P = 1572864;
    public static final int SIZE_1512P = 2097152;

    @IntDef({
            SIZE_1080P,
            SIZE_1512P,
    })
    @Target(ElementType.PARAMETER)
    @Retention(RetentionPolicy.SOURCE)
    public @interface BufferSize {}
}
