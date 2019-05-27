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
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.MemoryFile;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.util.MutableLong;
import android.view.Surface;

import com.askey.dvr.cdr7010.dashcam.BuildConfig;
import com.askey.dvr.cdr7010.dashcam.service.GPSStatusManager;
import com.askey.dvr.cdr7010.dashcam.util.LocationUtil;

import java.io.ByteArrayInputStream;
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

import javax.microedition.khronos.opengles.GL10Ext;
import javax.microedition.khronos.opengles.GL11Ext;

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

public class GLEncoderTask extends Thread {

    private String TAG = "GLEncoderTask";

    private FloatBuffer mFullScreenVertexPoint = ByteBuffer.allocateDirect(8 * Float.SIZE / Byte.SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer().put(new float[]{-1f, -1f, -1f, 1f, 1f, -1f, 1f, 1f});
    private FloatBuffer mFullScreenTexturePoint = ByteBuffer.allocateDirect(8 * Float.SIZE / Byte.SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer().put(new float[]{0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f});
    private FloatBuffer mWaterMarkFrameVertexPoint = ByteBuffer.allocateDirect(8 * Float.SIZE / Byte.SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer().put(new float[]{-1f, 0.985f, -1f, 0.925f, 1f, 0.985f, 1f, 0.925f});
    private FloatBuffer mWaterMarkFrameTexturePoint = ByteBuffer.allocateDirect(8 * Float.SIZE / Byte.SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer().put(new float[]{0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f});
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

    private String mWaterMarkVSS
            = "attribute highp vec4 aWaterMarkVertex;\n"
            + "attribute highp vec2 aWaterMarkTextureCoordinate;\n"
            + "varying   highp vec2 vWaterMarkTextureCoordinate;\n"
            + "void main() {\n"
            + "    gl_Position = aWaterMarkVertex;\n"
            + "	   vWaterMarkTextureCoordinate = aWaterMarkTextureCoordinate;\n"
            + "}\n";

    private String mWatermarkFSS
            = "uniform highp sampler2D uWaterMarkTexture;\n"
            + "varying highp vec2 vWaterMarkTextureCoordinate;\n"
            + "void main() {\n"
            + "    gl_FragColor = texture2D(uWaterMarkTexture, vWaterMarkTextureCoordinate);\n"
            + "}\n";

    private EGLConfig[] mConfig = new EGLConfig[1];
    private int[] mConfigNumber = new int[1];

    private Bitmap mWaterMarkBitmap;
    private Canvas mWaterMarkCanvas;
    private Paint mWaterMarkPaint;

    private DateFormat mDateFormat;
    private TimerTask mWaterMarkUpdateTask;
    private Timer mWaterMarkUpdateTimer;
    private StringBuilder mWaterMarkGNSSStringBuilder;
    private GPSStatusManager mGPSStatusManager;
    private Date mDate;

    private EGLContext mShardContext;
    private int mCameraTexture;

    private Surface mEncoderSurface;
    private int mEncoderSurfaceWidth;
    private int mEncoderSurfaceHeight;

    private ByteBuffer mImageBuffer;
    private Bitmap mSnapshotBmp;
    private boolean mWaterMarkUpdated = false;

    public GLEncoderTask(EGLContext sharedContext, int cameraTexture, Surface surface, int width, int height) {
        setName(TAG);
        mShardContext = sharedContext;
        mCameraTexture = cameraTexture;
        mEncoderSurface = surface;
        mEncoderSurfaceWidth = width;
        mEncoderSurfaceHeight = height;
        mImageBuffer = ByteBuffer.allocateDirect(width * height * 4);
        mSnapshotBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        mWaterMarkBitmap = Bitmap.createBitmap(960, 16, Bitmap.Config.ARGB_8888);
        mWaterMarkCanvas = new Canvas(mWaterMarkBitmap);
        mWaterMarkPaint = new Paint();
        mWaterMarkPaint.setColor(Color.WHITE);
        mWaterMarkPaint.setTextSize(16);
        mWaterMarkPaint.setStyle(Paint.Style.FILL);
        mWaterMarkPaint.setAntiAlias(true);
        mWaterMarkPaint.setTypeface(Typeface.create("DroidSans", Typeface.BOLD));
        mWaterMarkPaint.setTextAlign(Paint.Align.LEFT);
        mGPSStatusManager = GPSStatusManager.getInstance();
        mWaterMarkGNSSStringBuilder = new StringBuilder(32);
        mDateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US);
        mDate = new Date();
        mWaterMarkUpdateTimer = new Timer();
        mWaterMarkUpdateTask = new TimerTask() {
            @Override
            public void run() {
                double latitude;
                double longitude;
                String dateTime;
                Location currentLocation = mGPSStatusManager.getCurrentLocation();
                if (currentLocation != null) {
                    latitude = currentLocation.getLatitude();
                    longitude = currentLocation.getLongitude();
                } else {
                    latitude = 0.0;
                    longitude = 0.0;
                }
                mWaterMarkGNSSStringBuilder.setLength(0);
                mWaterMarkGNSSStringBuilder.append(LocationUtil.getCurrentSpeed());
                mWaterMarkGNSSStringBuilder.append("  ");
                mWaterMarkGNSSStringBuilder.append(LocationUtil.latitudeToDMS(latitude));
                mWaterMarkGNSSStringBuilder.append("  ");
                mWaterMarkGNSSStringBuilder.append(LocationUtil.longitudeToDMS(longitude));
                mDate.setTime(System.currentTimeMillis());
                if (mDate.before(BuildConfig.buildTime)) {
                    dateTime = "--/--/--　--:--:--";
                } else {
                    dateTime = mDateFormat.format(mDate);
                }
                synchronized (mWaterMarkBitmap) {
                    mWaterMarkCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                    mWaterMarkCanvas.drawText(mWaterMarkGNSSStringBuilder.toString(), 10, 16, mWaterMarkPaint);
                    mWaterMarkCanvas.drawText(dateTime, 672, 16, mWaterMarkPaint);
                    mWaterMarkUpdated = true;
                }
            }
        };
        mWaterMarkUpdateTimer.schedule(mWaterMarkUpdateTask, 0, 500);
        start();
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        //Need release.
        EGLDisplay display;
        EGLContext context;
        EGLSurface encoderSurface = EGL_NO_SURFACE;
        int cameraVertexShader;
        int cameraFragmentShader;
        int cameraProgram;
        int waterMarkVertexShader;
        int waterMarkFragmentShader;
        int waterMarkProgram;
        int waterMarkTextures[] = new int[1];
        //Locations.
        int cameraFrameVertexLocation;
        int cameraFrameTextureCoordinateLocation;
        int cameraFrameVertexMatrixLocation;
        int cameraFrameTextureLocation;
        int waterMarkVertexLocation;
        int waterMarkTextureCoordinationLocation;
        int waterMarkTextureLocation;
        mFullScreenVertexPoint.position(0);
        mFullScreenTexturePoint.position(0);
        mWaterMarkFrameVertexPoint.position(0);
        mWaterMarkFrameTexturePoint.position(0);
        //EGL initialize.
        display = EGL14.eglGetDisplay(EGL_DEFAULT_DISPLAY);
        EGL14.eglInitialize(display, null, 0, null, 0);
        EGL14.eglChooseConfig(display, mEGLAttributeList, 0, mConfig, 0, 1, mConfigNumber, 0);
        EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API);
        context = EGL14.eglCreateContext(display, mConfig[0], mShardContext, mEGLClientVersion, 0);
        encoderSurface = EGL14.eglCreateWindowSurface(display, mConfig[0], mEncoderSurface, null, 0);
        EGL14.eglMakeCurrent(display, encoderSurface, encoderSurface, context);
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
        //GL compile shader.->water mark.
        waterMarkVertexShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(waterMarkVertexShader, mWaterMarkVSS);
        GLES20.glCompileShader(waterMarkVertexShader);
        waterMarkFragmentShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(waterMarkFragmentShader, mWatermarkFSS);
        GLES20.glCompileShader(waterMarkFragmentShader);
        waterMarkProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(waterMarkProgram, waterMarkVertexShader);
        GLES20.glAttachShader(waterMarkProgram, waterMarkFragmentShader);
        GLES20.glLinkProgram(waterMarkProgram);
        GLES20.glUseProgram(waterMarkProgram);
        waterMarkVertexLocation = GLES20.glGetAttribLocation(waterMarkProgram, "aWaterMarkVertex");
        waterMarkTextureCoordinationLocation = GLES20.glGetAttribLocation(waterMarkProgram, "aWaterMarkTextureCoordinate");
        waterMarkTextureLocation = GLES20.glGetUniformLocation(waterMarkProgram, "uWaterMarkTexture");
        GLES20.glGenTextures(1, waterMarkTextures, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, waterMarkTextures[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glViewport(0, 0, mEncoderSurfaceWidth, mEncoderSurfaceHeight);
        Matrix.setIdentityM(mMatrix, 0);
        Matrix.rotateM(mMatrix, 0, 180, 0, 0, 1);
        long deltaTime;
        while (true) {
            deltaTime = System.currentTimeMillis();
            synchronized (mShardContext) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mCameraTexture);
                GLES20.glUseProgram(cameraProgram);
                GLES20.glVertexAttribPointer(cameraFrameVertexLocation, 2, GLES20.GL_FLOAT, true, 0, mFullScreenVertexPoint);
                GLES20.glVertexAttribPointer(cameraFrameTextureCoordinateLocation, 2, GLES20.GL_FLOAT, true, 0, mFullScreenTexturePoint);
                GLES20.glEnableVertexAttribArray(cameraFrameVertexLocation);
                GLES20.glEnableVertexAttribArray(cameraFrameTextureCoordinateLocation);
                GLES20.glUniformMatrix4fv(cameraFrameVertexMatrixLocation, 1, false, mMatrix, 0);
                GLES20.glUniform1i(cameraFrameTextureLocation, 0);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
                GLES20.glFlush();
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, waterMarkTextures[0]);
            synchronized (mWaterMarkBitmap) {
                if (mWaterMarkUpdated) {
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, mWaterMarkBitmap, 0);
                    mWaterMarkUpdated = false;
                }
            }
            GLES20.glUseProgram(waterMarkProgram);
            GLES20.glVertexAttribPointer(waterMarkVertexLocation, 2, GLES20.GL_FLOAT, true, 0, mWaterMarkFrameVertexPoint);
            GLES20.glVertexAttribPointer(waterMarkTextureCoordinationLocation, 2, GLES20.GL_FLOAT, true, 0, mWaterMarkFrameTexturePoint);
            GLES20.glEnableVertexAttribArray(waterMarkVertexLocation);
            GLES20.glEnableVertexAttribArray(waterMarkTextureCoordinationLocation);
            GLES20.glUniform1i(waterMarkTextureLocation, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            File f = new File("/sdcard/bmp.jpg");
            if (!f.exists()) {
                mImageBuffer.clear();
                GLES20.glReadPixels(0, 0, mEncoderSurfaceWidth, mEncoderSurfaceHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, mImageBuffer);
                mImageBuffer.rewind();
                mSnapshotBmp.copyPixelsFromBuffer(mImageBuffer);
                try {
                    FileOutputStream fs = new FileOutputStream("/sdcard/bmp.jpg");
                    mSnapshotBmp.compress(Bitmap.CompressFormat.JPEG, 50, fs);
                    fs.flush();
                    fs.close();
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            EGL14.eglSwapBuffers(display, encoderSurface);
            deltaTime = 35 - (System.currentTimeMillis() - deltaTime);
            if (deltaTime > 0) {
                SystemClock.sleep(deltaTime);
            }
        }
    }
}