package com.askey.dvr.cdr7010.dashcam.simplerecoder;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;


import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;


import static android.opengl.EGL14.EGL_ALPHA_SIZE;
import static android.opengl.EGL14.EGL_BLUE_SIZE;
import static android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION;
import static android.opengl.EGL14.EGL_DEFAULT_DISPLAY;
import static android.opengl.EGL14.EGL_GREEN_SIZE;
import static android.opengl.EGL14.EGL_NONE;
import static android.opengl.EGL14.EGL_NO_SURFACE;
import static android.opengl.EGL14.EGL_RED_SIZE;
import static android.opengl.EGL14.EGL_RENDERABLE_TYPE;

public class GLPreviewTask extends Thread {

    private String TAG = "GLPreviewTask";

    private FloatBuffer mFullScreenVertexPoint = ByteBuffer.allocateDirect(8 * Float.SIZE / Byte.SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer().put(new float[]{-1f, -1f, -1f, 1f, 1f, -1f, 1f, 1f});
    private FloatBuffer mFullScreenTexturePoint = ByteBuffer.allocateDirect(8 * Float.SIZE / Byte.SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer().put(new float[]{0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f});
    private float mMatrix[] = new float[16];

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

    private String mCameraFrameVSS
            = "attribute highp vec4 aCameraFrameVertex;\n"
            + "attribute highp vec2 aCameraFrameTextureCoordinate;\n"
            + "uniform   highp mat4 uCameraFrameVertexMatrix;\n"
            + "varying   highp vec2 vCameraFrameTextureCoordinate;\n"
            + "void main() {\n"
            + "    gl_Position = uCameraFrameVertexMatrix * aCameraFrameVertex;\n"
            + "	   vCameraFrameTextureCoordinate = aCameraFrameTextureCoordinate;\n"
            + "}\n";

    private String mCameraFrameFSS
            = "#extension GL_OES_EGL_image_external : require\n"
            + "uniform highp samplerExternalOES uCameraFrameTexture;\n"
            + "varying highp vec2 vCameraFrameTextureCoordinate;\n"
            + "void main() {\n"
            + "    gl_FragColor = texture2D(uCameraFrameTexture, vCameraFrameTextureCoordinate);\n"
            + "}\n";

    private EGLConfig[] mConfig = new EGLConfig[1];
    private int[] mConfigNumber = new int[1];

    private EGLContext mShardContext;
    private int mCameraTexture;

    private Surface mPreviewSurface;
    private int mPreviewSurfaceWidth;
    private int mPreviewSurfaceHeight;

    public GLPreviewTask(EGLContext sharedContext,int cameraTexture,Surface surface,int width,int height){
        setName(TAG);
        mShardContext = sharedContext;
        mCameraTexture = cameraTexture;
        mPreviewSurface = surface;
        mPreviewSurfaceWidth = width;
        mPreviewSurfaceHeight = height;
        start();
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        //Need release.
        EGLDisplay display;
        EGLContext context;
        EGLSurface previewSurface;
        int cameraVertexShader;
        int cameraFragmentShader;
        int cameraProgram;
        //Locations.
        int cameraFrameVertexLocation;
        int cameraFrameTextureCoordinateLocation;
        int cameraFrameVertexMatrixLocation;
        int cameraFrameTextureLocation;
        mFullScreenVertexPoint.position(0);
        mFullScreenTexturePoint.position(0);
        //EGL initialize.
        display = EGL14.eglGetDisplay(EGL_DEFAULT_DISPLAY);
        EGL14.eglInitialize(display, null, 0, null, 0);
        EGL14.eglChooseConfig(display, mEGLAttributeList, 0, mConfig, 0, 1, mConfigNumber, 0);
        EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API);
        context = EGL14.eglCreateContext(display, mConfig[0], mShardContext, mEGLClientVersion, 0);
        previewSurface = EGL14.eglCreateWindowSurface(display, mConfig[0], mPreviewSurface, null, 0);
        EGL14.eglMakeCurrent(display, previewSurface, previewSurface, context);
        //GL compile shader.->camera frame
        cameraVertexShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(cameraVertexShader, mCameraFrameVSS);
        GLES20.glCompileShader(cameraVertexShader);
        cameraFragmentShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(cameraFragmentShader, mCameraFrameFSS);
        GLES20.glCompileShader(cameraFragmentShader);
        cameraProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(cameraProgram, cameraVertexShader);
        GLES20.glAttachShader(cameraProgram, cameraFragmentShader);
        GLES20.glLinkProgram(cameraProgram);
        GLES20.glUseProgram(cameraProgram);
        cameraFrameVertexLocation = GLES20.glGetAttribLocation(cameraProgram, "aCameraFrameVertex");
        cameraFrameTextureCoordinateLocation = GLES20.glGetAttribLocation(cameraProgram, "aCameraFrameTextureCoordinate");
        cameraFrameVertexMatrixLocation = GLES20.glGetUniformLocation(cameraProgram, "uCameraFrameVertexMatrix");
        cameraFrameTextureLocation = GLES20.glGetUniformLocation(cameraProgram, "uCameraFrameTexture");
        GLES20.glVertexAttribPointer(cameraFrameVertexLocation, 2, GLES20.GL_FLOAT, true, 0, mFullScreenVertexPoint);
        GLES20.glVertexAttribPointer(cameraFrameTextureCoordinateLocation, 2, GLES20.GL_FLOAT, true, 0, mFullScreenTexturePoint);
        GLES20.glEnableVertexAttribArray(cameraFrameVertexLocation);
        GLES20.glEnableVertexAttribArray(cameraFrameTextureCoordinateLocation);
        GLES20.glViewport(0, 0, mPreviewSurfaceWidth, mPreviewSurfaceHeight);
        Matrix.setIdentityM(mMatrix,0);
        Matrix.scaleM(mMatrix, 0, -1, 1, 1);
        long deltaTime;
        while (true) {
            deltaTime = System.currentTimeMillis();
            synchronized (mShardContext) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mCameraTexture);
                GLES20.glUniformMatrix4fv(cameraFrameVertexMatrixLocation, 1, false, mMatrix, 0);
                GLES20.glUniform1i(cameraFrameTextureLocation, 0);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
                GLES20.glFlush();
            }
            EGL14.eglSwapBuffers(display, previewSurface);
            deltaTime = 50 - (System.currentTimeMillis() - deltaTime);
            if(deltaTime > 0) {
                SystemClock.sleep(deltaTime);
            }
        }
    }
}
