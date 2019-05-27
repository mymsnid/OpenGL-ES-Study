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


public class Render {

    private final String TAG;
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

    private String mYuvConverterVSS =
            "attribute highp vec4 aYuvConverterVertex;\n" +
            "attribute highp vec2 aYuvConverterTextureCoordinate;\n" +
            "varying   highp vec2 vYuvConverterTextureCoordinate;\n" +
            "\n" +
            "void main(){\n" +
            "    gl_Position = aYuvConverterVertex;\n" +
            "    vYuvConverterTextureCoordinate = aYuvConverterTextureCoordinate;\n" +
            "}";

    private String mYuvConverterFSS =
                    "precision highp float;\n" +
                    "precision highp int;\n" +
                    "\n" +
                    "varying vec2 vYuvConverterTextureCoordinate;\n" +
                    "uniform sampler2D uYuvConverterTexture;\n" +
                    "\n" +
                    "uniform float uYuvImageWidth;\n" +
                    "uniform float uYuvImageHeight;\n" +
                    "\n" +
                    "float cY(float x,float y){\n" +
                    "    vec4 c=texture2D(uYuvConverterTexture,vec2(x,y));\n" +
                    "    return c.r*0.257+c.g*0.504+c.b*0.098+0.0625;\n" +
                    "}\n" +
                    "\n" +
                    "vec4 cC(float x,float y,float dx,float dy){\n" +
                    "    vec4 c0=texture2D(uYuvConverterTexture,vec2(x,y));\n" +
                    "    vec4 c1=texture2D(uYuvConverterTexture,vec2(x+dx,y));\n" +
                    "    vec4 c2=texture2D(uYuvConverterTexture,vec2(x,y+dy));\n" +
                    "    vec4 c3=texture2D(uYuvConverterTexture,vec2(x+dx,y+dy));\n" +
                    "    return (c0+c1+c2+c3)/4.;\n" +
                    "}\n" +
                    "\n" +
                    "float cU(float x,float y,float dx,float dy){\n" +
                    "    vec4 c=cC(x,y,dx,dy);\n" +
                    "    return -0.148*c.r - 0.291*c.g + 0.439*c.b+0.5000;\n" +
                    "}\n" +
                    "\n" +
                    "float cV(float x,float y,float dx,float dy){\n" +
                    "    vec4 c=cC(x,y,dx,dy);\n" +
                    "    return 0.439*c.r - 0.368*c.g - 0.071*c.b+0.5000;\n" +
                    "}\n" +
                    "\n" +
                    "vec2 cPos(float t,float shiftx,float gy){\n" +
                    "    vec2 pos=vec2(floor(uYuvImageWidth*vYuvConverterTextureCoordinate.x),floor(uYuvImageWidth*gy));\n" +
                    "    return vec2(mod(pos.x*shiftx,uYuvImageWidth),(pos.y*shiftx+floor(pos.x*shiftx/uYuvImageWidth))*t);\n" +
                    "}\n" +
                    "\n" +
                    "vec4 calculateY(){\n" +
                    "    vec2 pos=cPos(1.,4.,vYuvConverterTextureCoordinate.y);\n" +
                    "    vec4 oColor=vec4(0);\n" +
                    "    float textureYPos=pos.y/uYuvImageWidth;\n" +
                    "    oColor[0]=cY(pos.x/uYuvImageWidth,textureYPos);\n" +
                    "    oColor[1]=cY((pos.x+1.)/uYuvImageWidth,textureYPos);\n" +
                    "    oColor[2]=cY((pos.x+2.)/uYuvImageWidth,textureYPos);\n" +
                    "    oColor[3]=cY((pos.x+3.)/uYuvImageWidth,textureYPos);\n" +
                    "    return oColor;\n" +
                    "}\n" +
                    "vec4 calculateU(float gy,float dx,float dy){\n" +
                    "    vec2 pos=cPos(2.,8.,vYuvConverterTextureCoordinate.y-gy);\n" +
                    "    vec4 oColor=vec4(0);\n" +
                    "    float textureYPos=pos.y/uYuvImageWidth;\n" +
                    "    oColor[0]= cU(pos.x/uYuvImageWidth,textureYPos,dx,dy);\n" +
                    "    oColor[1]= cU((pos.x+2.)/uYuvImageWidth,textureYPos,dx,dy);\n" +
                    "    oColor[2]= cU((pos.x+4.)/uYuvImageWidth,textureYPos,dx,dy);\n" +
                    "    oColor[3]= cU((pos.x+6.)/uYuvImageWidth,textureYPos,dx,dy);\n" +
                    "    return oColor;\n" +
                    "}\n" +
                    "vec4 calculateV(float gy,float dx,float dy){\n" +
                    "    vec2 pos=cPos(2.,8.,vYuvConverterTextureCoordinate.y-gy);\n" +
                    "    vec4 oColor=vec4(0);\n" +
                    "    float textureYPos=pos.y/uYuvImageWidth;\n" +
                    "    oColor[0]=cV(pos.x/uYuvImageWidth,textureYPos,dx,dy);\n" +
                    "    oColor[1]=cV((pos.x+2.)/uYuvImageWidth,textureYPos,dx,dy);\n" +
                    "    oColor[2]=cV((pos.x+4.)/uYuvImageWidth,textureYPos,dx,dy);\n" +
                    "    oColor[3]=cV((pos.x+6.)/uYuvImageWidth,textureYPos,dx,dy);\n" +
                    "    return oColor;\n" +
                    "}\n" +
                    "void main() {\n" +
                    "    if(vYuvConverterTextureCoordinate.y<0.2500){\n" +
                    "        gl_FragColor=calculateY();\n" +
                    "    }else if(vYuvConverterTextureCoordinate.y<0.3125){\n" +
                    "        gl_FragColor=calculateU(0.2500,1./uYuvImageWidth,1./uYuvImageWidth);\n" +
                    "    }else if(vYuvConverterTextureCoordinate.y<0.3750){\n" +
                    "        gl_FragColor=calculateV(0.3125,1./uYuvImageWidth,1./uYuvImageWidth);\n" +
                    "    }else{\n" +
                    "        gl_FragColor=vec4(0,0,0,0);\n" +
                    "    }\n" +
                    "}";


    private EGLConfig[] mConfig = new EGLConfig[1];
    private int[] mConfigNumber = new int[1];

    private Bitmap mWaterMarkBitmap;
    private Canvas mWaterMarkCanvas;
    private Paint mWaterMarkPaint;
    private boolean mWaterMarkBitmapUpdated = false;

    private DateFormat mDateFormat;
    private TimerTask mWaterMarkUpdateTask;
    private Timer mWaterMarkUpdateTimer;
    private StringBuilder mWaterMarkGNSSStringBuilder;
    private GPSStatusManager mGPSStatusManager;
    private Date mDate;

    private ReentrantLock mFrameSyncLock = new ReentrantLock();
    private Condition mFrameSyncCond = mFrameSyncLock.newCondition();

    private Surface mEncoderSurface;
    private int mEncoderSurfaceWidth;
    private int mEncoderSurfaceHeight;

    private Surface mPreviewSurface;
    private int mPreviewSurfaceWidth;
    private int mPreviewSurfaceHeight;

    private int mYuvWidth;
    private int mYuvHeight;

    RenderAvailableListener mRenderAvailableListener;

    private int mPreviewReq = 0;
    private int mEncoderReq = 0;
    private int mAdasReq = 0;
    private ReentrantLock mReqLock = new ReentrantLock();
    private Condition mReqCond = mReqLock.newCondition();

    private ByteBuffer mImageBuffer = ByteBuffer.allocate(1280*720*3/2);
    private ByteBuffer mImageBuffer0 = ByteBuffer.allocate(1280*720*4);

    public Render(Camera.CAM_NAME id, RenderAvailableListener listener) {
        TAG = "EGLUtil_" + id;
        mRenderAvailableListener = listener;
        mWaterMarkBitmap = Bitmap.createBitmap(1920, 32, Bitmap.Config.ARGB_8888);
        mWaterMarkCanvas = new Canvas(mWaterMarkBitmap);
        mWaterMarkPaint = new Paint();
        mWaterMarkPaint.setColor(Color.WHITE);
        mWaterMarkPaint.setTextSize(32);
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
                    mWaterMarkCanvas.drawText(mWaterMarkGNSSStringBuilder.toString(), 19, 32, mWaterMarkPaint);
                    mWaterMarkCanvas.drawText(dateTime,1344, 32, mWaterMarkPaint);
                    mWaterMarkBitmapUpdated = true;
                }
            }
        };
        mWaterMarkUpdateTimer.schedule(mWaterMarkUpdateTask, 0, 500);
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
            //Need release.
            EGLDisplay display;
            EGLContext context;
            EGLSurface encoderSurface = EGL_NO_SURFACE;
            EGLSurface previewSurface = EGL_NO_SURFACE;
            int cameraVertexShader;
            int cameraFragmentShader;
            int cameraProgram;
            int waterMarkVertexShader;
            int waterMarkFragmentShader;
            int waterMarkProgram;
            int yuvConverterVertexShader;
            int yuvConverterFragmentShader;
            int yuvConverterProgram;
            int cameraTextures[] = new int[1];
            int waterMarkTextures[] = new int[1];
            int internalFrameBuffer[] = new int[]{-1};
            int internalFrameBufferColorBuffer[] = new int[1];
            int internalFrameBufferDepthBuffer[] = new int[1];
            int internalFrameBufferTransformColorBuffer[] = new int[1];
            SurfaceTexture surfaceTexture;
            //Locations.
            int cameraFrameVertexLocation;
            int cameraFrameTextureCoordinateLocation;
            int cameraFrameVertexMatrixLocation;
            int cameraFrameTextureLocation;
            int waterMarkVertexLocation;
            int waterMarkTextureCoordinationLocation;
            int waterMarkTextureLocation;
            int yuvConverterVertexLocation;
            int yuvConverterTextureCoordinationLocation;
            int yuvConverterTextureLocation;
            int yuvConverterWidthLocation;
            int yumConverterHeightLocation;
            mFullScreenVertexPoint.position(0);
            mFullScreenTexturePoint.position(0);
            mWaterMarkFrameVertexPoint.position(0);
            mWaterMarkFrameTexturePoint.position(0);
            //EGL initialize.
            display = EGL14.eglGetDisplay(EGL_DEFAULT_DISPLAY);
            EGL14.eglInitialize(display, null, 0, null, 0);
            EGL14.eglChooseConfig(display, mEGLAttributeList, 0, mConfig, 0, 1, mConfigNumber, 0);
            EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API);
            context = EGL14.eglCreateContext(display, mConfig[0], EGL_NO_CONTEXT, mEGLClientVersion, 0);
            EGL14.eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, context);
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
            //GL compile shader.->yuv converter.
            yuvConverterVertexShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
            GLES20.glShaderSource(yuvConverterVertexShader, mYuvConverterVSS);
            GLES20.glCompileShader(yuvConverterVertexShader);
            yuvConverterFragmentShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
            GLES20.glShaderSource(yuvConverterFragmentShader, mYuvConverterFSS);
            GLES20.glCompileShader(yuvConverterFragmentShader);
            yuvConverterProgram = GLES20.glCreateProgram();
            GLES20.glAttachShader(yuvConverterProgram, yuvConverterVertexShader);
            GLES20.glAttachShader(yuvConverterProgram, yuvConverterFragmentShader);
            GLES20.glLinkProgram(yuvConverterProgram);
            GLES20.glUseProgram(yuvConverterProgram);
            yuvConverterVertexLocation = GLES20.glGetAttribLocation(yuvConverterProgram, "aYuvConverterVertex");
            yuvConverterTextureCoordinationLocation = GLES20.glGetAttribLocation(yuvConverterProgram, "aYuvConverterTextureCoordinate");
            yuvConverterTextureLocation = GLES20.glGetUniformLocation(yuvConverterProgram, "uYuvConverterTexture");
            yuvConverterWidthLocation = GLES20.glGetUniformLocation(yuvConverterProgram, "uYuvImageWidth");
            yumConverterHeightLocation = GLES20.glGetUniformLocation(yuvConverterProgram, "uYuvImageHeight");
            //Create textures.
            GLES20.glGenTextures(1, cameraTextures, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextures[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            surfaceTexture = new SurfaceTexture(cameraTextures[0]);
            surfaceTexture.setDefaultBufferSize(2688, 1512);
            surfaceTexture.setOnFrameAvailableListener((st) -> {
                mFrameSyncLock.lock();
                mFrameSyncCond.signal();
                mFrameSyncLock.unlock();
            }, null);
            GLES20.glGenTextures(1, waterMarkTextures, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, waterMarkTextures[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            mFrameSyncLock.lock();
            mRenderAvailableListener.onInputSurfaceReady(surfaceTexture);
            long timeAwait;

            while (true) {
                try {
                    timeAwait = mFrameSyncCond.awaitNanos(100000000);
                    if(mEncoderReq == 1){
                        mReqLock.lock();
                        encoderSurface = EGL14.eglCreateWindowSurface(display, mConfig[0], mEncoderSurface, null, 0);
                        mEncoderReq = 0;
                        mReqCond.signal();
                        mReqLock.unlock();
                    }
                    if (mPreviewReq == 1) {
                        mReqLock.lock();
                        previewSurface = EGL14.eglCreateWindowSurface(display, mConfig[0], mPreviewSurface, null, 0);
                        mPreviewReq = 0;
                        mReqCond.signal();
                        mReqLock.unlock();
                    }
                    if (mAdasReq == 1) {
                        mReqLock.lock();
                        GLES20.glGenTextures(1, internalFrameBufferColorBuffer, 0);
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, internalFrameBufferColorBuffer[0]);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, mYuvWidth, mYuvHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                        GLES20.glGenTextures(1, internalFrameBufferDepthBuffer, 0);
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, internalFrameBufferDepthBuffer[0]);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_DEPTH_COMPONENT, mYuvWidth, mYuvHeight, 0, GLES20.GL_DEPTH_COMPONENT, GLES20.GL_UNSIGNED_INT, null);
                        GLES20.glGenTextures(1, internalFrameBufferTransformColorBuffer, 0);
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE4);
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, internalFrameBufferTransformColorBuffer[0]);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, mYuvWidth, mYuvHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_TEXTURE_2D, internalFrameBufferDepthBuffer[0], 0);
                        GLES20.glGenFramebuffers(1, internalFrameBuffer, 0);
                        mAdasReq = 0;
                        mReqCond.signal();
                        mReqLock.unlock();
                    }
                    if (timeAwait >= 0) {
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                        surfaceTexture.updateTexImage();
                        if (mWaterMarkBitmapUpdated) {
                            synchronized (mWaterMarkBitmap) {
                                GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, waterMarkTextures[0]);
                                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, mWaterMarkBitmap, 0);
                                mWaterMarkBitmapUpdated = false;
                            }
                        }
                    } else {
                        Log.e(TAG, "Wait camera frame to long " + (100000000 - timeAwait));
                        continue;
                    }
                } catch (InterruptedException e) {
                    Log.e(TAG, "Render interrupted.");
                    e.printStackTrace();
                    mFrameSyncLock.lock();
                    break;
                }
                if(encoderSurface != EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(display, encoderSurface, encoderSurface, context);
                    GLES20.glViewport(0, 0, mEncoderSurfaceWidth, mEncoderSurfaceHeight);
                    GLES20.glEnable(GLES20.GL_BLEND);
                    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                    GLES20.glUseProgram(cameraProgram);
                    GLES20.glVertexAttribPointer(cameraFrameVertexLocation, 2, GLES20.GL_FLOAT, true, 0, mFullScreenVertexPoint);
                    GLES20.glVertexAttribPointer(cameraFrameTextureCoordinateLocation, 2, GLES20.GL_FLOAT, true, 0, mFullScreenTexturePoint);
                    GLES20.glEnableVertexAttribArray(cameraFrameVertexLocation);
                    GLES20.glEnableVertexAttribArray(cameraFrameTextureCoordinateLocation);
                    Matrix.setIdentityM(mMatrix, 0);
                    Matrix.rotateM(mMatrix, 0, 180, 0, 0, 1);
                    GLES20.glUniformMatrix4fv(cameraFrameVertexMatrixLocation, 1, false, mMatrix, 0);
                    GLES20.glUniform1i(cameraFrameTextureLocation, 0);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    GLES20.glUseProgram(waterMarkProgram);
                    GLES20.glVertexAttribPointer(waterMarkVertexLocation, 2, GLES20.GL_FLOAT, true, 0, mWaterMarkFrameVertexPoint);
                    GLES20.glVertexAttribPointer(waterMarkTextureCoordinationLocation, 2, GLES20.GL_FLOAT, true, 0, mWaterMarkFrameTexturePoint);
                    GLES20.glEnableVertexAttribArray(waterMarkVertexLocation);
                    GLES20.glEnableVertexAttribArray(waterMarkTextureCoordinationLocation);
                    GLES20.glUniform1i(waterMarkTextureLocation, 1);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    EGL14.eglSwapBuffers(display, encoderSurface);
                    GLES20.glDisable(GLES20.GL_BLEND);
                }
                if (previewSurface != EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(display, previewSurface, previewSurface, context);
                    GLES20.glViewport(0, 0, mPreviewSurfaceWidth, mPreviewSurfaceHeight);
                    GLES20.glUseProgram(cameraProgram);
                    GLES20.glVertexAttribPointer(cameraFrameVertexLocation, 2, GLES20.GL_FLOAT, true, 0, mFullScreenVertexPoint);
                    GLES20.glVertexAttribPointer(cameraFrameTextureCoordinateLocation, 2, GLES20.GL_FLOAT, true, 0, mFullScreenTexturePoint);
                    GLES20.glEnableVertexAttribArray(cameraFrameVertexLocation);
                    GLES20.glEnableVertexAttribArray(cameraFrameTextureCoordinateLocation);
                    Matrix.setIdentityM(mMatrix, 0);
                    Matrix.scaleM(mMatrix, 0, -1, 1, 1);
                    GLES20.glUniformMatrix4fv(cameraFrameVertexMatrixLocation, 1, false, mMatrix, 0);
                    GLES20.glUniform1i(cameraFrameTextureLocation, 0);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    EGL14.eglSwapBuffers(display, previewSurface);
                }
                if(internalFrameBuffer[0] != -1){
                    EGL14.eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, context);
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, internalFrameBuffer[0]);
                    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, internalFrameBufferTransformColorBuffer[0], 0);
                    GLES20.glViewport(0, 0, mYuvWidth, mYuvHeight);
                    GLES20.glUseProgram(cameraProgram);
                    GLES20.glVertexAttribPointer(cameraFrameVertexLocation, 2, GLES20.GL_FLOAT, true, 0, mFullScreenVertexPoint);
                    GLES20.glVertexAttribPointer(cameraFrameTextureCoordinateLocation, 2, GLES20.GL_FLOAT, true, 0, mFullScreenTexturePoint);
                    GLES20.glEnableVertexAttribArray(cameraFrameVertexLocation);
                    GLES20.glEnableVertexAttribArray(cameraFrameTextureCoordinateLocation);
                    Matrix.setIdentityM(mMatrix, 0);
                    Matrix.rotateM(mMatrix, 0, 180, 0, 0, 1);
                    GLES20.glUniformMatrix4fv(cameraFrameVertexMatrixLocation, 1, false, mMatrix, 0);
                    GLES20.glUniform1i(cameraFrameTextureLocation, 0);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, internalFrameBufferColorBuffer[0], 0);
                    GLES20.glUseProgram(yuvConverterProgram);
                    GLES20.glVertexAttribPointer(yuvConverterVertexLocation, 2, GLES20.GL_FLOAT, true, 0, mFullScreenVertexPoint);
                    GLES20.glVertexAttribPointer(yuvConverterTextureCoordinationLocation, 2, GLES20.GL_FLOAT, true, 0, mFullScreenTexturePoint);
                    GLES20.glEnableVertexAttribArray(yuvConverterVertexLocation);
                    GLES20.glEnableVertexAttribArray(yuvConverterTextureCoordinationLocation);
                    GLES20.glUniform1i(yuvConverterTextureLocation, 4);
                    GLES20.glUniform1f(yuvConverterWidthLocation, mYuvWidth);
                    GLES20.glUniform1f(yumConverterHeightLocation, mYuvHeight);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    GLES20.glReadPixels(0, 0, mYuvWidth, mYuvHeight * 3 / 8, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, mImageBuffer);
                    File f = new File("/sdcard/img.jpg");
                    if (!f.exists()) {
                        mImageBuffer.clear();
                        mImageBuffer0.clear();
                        GLES20.glReadPixels(0, 0, mYuvWidth, mYuvHeight * 3 / 8, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, mImageBuffer);
                        YUV2RGB.I420ToRGB(mImageBuffer,mImageBuffer0,mYuvWidth,mYuvHeight);
                        Bitmap bmp = Bitmap.createBitmap( mYuvWidth, mYuvHeight, Bitmap.Config.ARGB_8888);
                        bmp.copyPixelsFromBuffer(mImageBuffer0);
                        try {
                            FileOutputStream fs = new FileOutputStream("/sdcard/img.jpg");
                            bmp.compress(Bitmap.CompressFormat.JPEG, 100, fs);
                            fs.flush();
                            fs.close();
                        } catch (FileNotFoundException e) {
                            e.printStackTrace();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                }
            }
        }).start();
    }

    public void startRenderToEncoder(Surface surface,int width,int height){
        mReqLock.lock();
        mEncoderSurface = surface;
        mEncoderSurfaceWidth = width;
        mEncoderSurfaceHeight = height;
        mEncoderReq = 1;
        mReqCond.awaitUninterruptibly();
        mReqLock.unlock();
    }


    public void startRenderToPreview(Surface surface, int width, int height) {
        mReqLock.lock();
        mPreviewSurface = surface;
        mPreviewSurfaceWidth = width;
        mPreviewSurfaceHeight = height;
        mPreviewReq = 1;
        mReqCond.awaitUninterruptibly();
        mReqLock.unlock();
    }

    public void startRenderToYuv(int width,int height){
        mReqLock.lock();
        mYuvWidth = width;
        mYuvHeight = height;
        mAdasReq = 1;
        mReqCond.awaitUninterruptibly();
        mReqLock.unlock();
    }



    public interface RenderAvailableListener {
        void onInputSurfaceReady(SurfaceTexture input);
    }

}
