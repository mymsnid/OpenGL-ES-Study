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
import android.os.SystemClock;
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

import javax.microedition.khronos.opengles.GL;

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

public class GLYuvTask extends Thread {

    private String TAG = "GLYuvTask";

    private FloatBuffer mFullScreenVertexPoint = ByteBuffer.allocateDirect(8 * Float.SIZE / Byte.SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer().put(new float[]{-1f, -1f, -1f, 1f, 1f, -1f, 1f, 1f});
    private FloatBuffer mFullScreenTexturePoint = ByteBuffer.allocateDirect(8 * Float.SIZE / Byte.SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer().put(new float[]{0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f});

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

    private String mYuvConverterVSS =
            "attribute vec4 aVertexCo;\n" +
            "attribute vec2 aTextureCo;\n" +
            "\n" +
            "varying vec2 vTextureCo;\n" +
            "\n" +
            "void main(){\n" +
            "    gl_Position = aVertexCo;\n" +
            "    vTextureCo = aTextureCo;\n" +
            "}";

    private String mYuvConverterFSS =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            "\n" +
            "varying vec2 vTextureCo;\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "\n" +
            "uniform float uWidth;\n" +
            "uniform float uHeight;\n" +
            "\n" +
            "float cY(float x,float y){\n" +
            "    vec4 c=texture2D(uTexture,vec2(-x,-y));\n" +
            "    return c.r*0.257+c.g*0.504+c.b*0.098+0.0625;\n" +
            "}\n" +
            "\n" +
            "vec4 cC(float x,float y,float dx,float dy){\n" +
            "    vec4 c0=texture2D(uTexture,vec2(-x,-y));\n" +
            "    vec4 c1=texture2D(uTexture,vec2(-(x+dx),-y));\n" +
            "    vec4 c2=texture2D(uTexture,vec2(-x,-(y+dy)));\n" +
            "    vec4 c3=texture2D(uTexture,vec2(-(x+dx),-(y+dy)));\n" +
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
            "    vec2 pos=vec2(floor(uWidth*vTextureCo.x),floor(uHeight*gy));\n" +
            "    return vec2(mod(pos.x*shiftx,uWidth),(pos.y*shiftx+floor(pos.x*shiftx/uWidth))*t);\n" +
            "}\n" +
            "\n" +
            "vec4 calculateY(){\n" +
            "    vec2 pos=cPos(1.,4.,vTextureCo.y);\n" +
            "    vec4 oColor=vec4(0);\n" +
            "    float textureYPos=pos.y/uHeight;\n" +
            "    oColor[0]=cY(pos.x/uWidth,textureYPos);\n" +
            "    oColor[1]=cY((pos.x+1.)/uWidth,textureYPos);\n" +
            "    oColor[2]=cY((pos.x+2.)/uWidth,textureYPos);\n" +
            "    oColor[3]=cY((pos.x+3.)/uWidth,textureYPos);\n" +
            "    return oColor;\n" +
            "}\n" +
            "vec4 calculateU(float gy,float dx,float dy){\n" +
            "    vec2 pos=cPos(2.,8.,vTextureCo.y-gy);\n" +
            "    vec4 oColor=vec4(0);\n" +
            "    float textureYPos=pos.y/uHeight;\n" +
            "    oColor[0]= cU(pos.x/uWidth,textureYPos,dx,dy);\n" +
            "    oColor[1]= cU((pos.x+2.)/uWidth,textureYPos,dx,dy);\n" +
            "    oColor[2]= cU((pos.x+4.)/uWidth,textureYPos,dx,dy);\n" +
            "    oColor[3]= cU((pos.x+6.)/uWidth,textureYPos,dx,dy);\n" +
            "    return oColor;\n" +
            "}\n" +
            "vec4 calculateV(float gy,float dx,float dy){\n" +
            "    vec2 pos=cPos(2.,8.,vTextureCo.y-gy);\n" +
            "    vec4 oColor=vec4(0);\n" +
            "    float textureYPos=pos.y/uHeight;\n" +
            "    oColor[0]=cV(pos.x/uWidth,textureYPos,dx,dy);\n" +
            "    oColor[1]=cV((pos.x+2.)/uWidth,textureYPos,dx,dy);\n" +
            "    oColor[2]=cV((pos.x+4.)/uWidth,textureYPos,dx,dy);\n" +
            "    oColor[3]=cV((pos.x+6.)/uWidth,textureYPos,dx,dy);\n" +
            "    return oColor;\n" +
            "}\n" +
            "void main() {\n" +
            "    if(vTextureCo.y<0.2500){\n" +
            "        gl_FragColor=calculateY();\n" +
            "    }else if(vTextureCo.y<0.3125){\n" +
            "        gl_FragColor=calculateU(0.2500,1./uWidth,1./uHeight);\n" +
            "    }else if(vTextureCo.y<0.3750){\n" +
            "        gl_FragColor=calculateV(0.3125,1./uWidth,1./uHeight);\n" +
            "    }else{\n" +
            "        gl_FragColor=vec4(0,0,0,0);\n" +
            "    }\n" +
            "}";

    private EGLConfig[] mConfig = new EGLConfig[1];
    private int[] mConfigNumber = new int[1];

    private int mYuvWidth;
    private int mYuvHeight;

    private ByteBuffer mImageBuffer;
    private ByteBuffer mImageBuffer0;
    private EGLContext mShardContext;
    private int mCameraTexture;

    public GLYuvTask(EGLContext sharedContext, int cameraTexture, int width, int height) {
        setName(TAG);
        mShardContext = sharedContext;
        mCameraTexture = cameraTexture;
        mYuvWidth = width;
        mYuvHeight = height;
        mImageBuffer = ByteBuffer.allocateDirect(width * height * 3 / 2);
        mImageBuffer0 = ByteBuffer.allocateDirect(width * height * 4);
        start();
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        //Need release.
        EGLDisplay display;
        EGLContext context;
        int yuvConverterVertexShader;
        int yuvConverterFragmentShader;
        int yuvConverterProgram;
        int internalFrameBuffer[] = new int[]{-1};
        int internalFrameBufferColorBuffer[] = new int[1];
        int internalFrameBufferDepthBuffer[] = new int[1];
        //Locations.
        int yuvConverterVertexLocation;
        int yuvConverterTextureCoordinationLocation;
        int yuvConverterTextureLocation;
        int yuvConverterWidthLocation;
        int yumConverterHeightLocation;
        mFullScreenVertexPoint.position(0);
        mFullScreenTexturePoint.position(0);
        //EGL initialize.
        display = EGL14.eglGetDisplay(EGL_DEFAULT_DISPLAY);
        EGL14.eglInitialize(display, null, 0, null, 0);
        EGL14.eglChooseConfig(display, mEGLAttributeList, 0, mConfig, 0, 1, mConfigNumber, 0);
        EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API);
        context = EGL14.eglCreateContext(display, mConfig[0], mShardContext, mEGLClientVersion, 0);
        EGL14.eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, context);
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
        yuvConverterVertexLocation = GLES20.glGetAttribLocation(yuvConverterProgram, "aVertexCo");
        yuvConverterTextureCoordinationLocation = GLES20.glGetAttribLocation(yuvConverterProgram, "aTextureCo");
        yuvConverterTextureLocation = GLES20.glGetUniformLocation(yuvConverterProgram, "uTexture");
        yuvConverterWidthLocation = GLES20.glGetUniformLocation(yuvConverterProgram, "uWidth");
        yumConverterHeightLocation = GLES20.glGetUniformLocation(yuvConverterProgram, "uHeight");
        GLES20.glGenFramebuffers(1, internalFrameBuffer, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, internalFrameBuffer[0]);
        GLES20.glGenTextures(1, internalFrameBufferColorBuffer, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, internalFrameBufferColorBuffer[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, mYuvWidth, mYuvHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glGenTextures(1,internalFrameBufferDepthBuffer,0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, internalFrameBufferDepthBuffer[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_DEPTH_COMPONENT, mYuvWidth, mYuvHeight, 0, GLES20.GL_DEPTH_COMPONENT, GLES20.GL_UNSIGNED_INT, null);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, internalFrameBufferColorBuffer[0],0);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_TEXTURE_2D, internalFrameBufferDepthBuffer[0],0);
        GLES20.glViewport(0, 0, mYuvWidth, mYuvHeight);
        long deltaTime = 0;
        while (true) {
            deltaTime = System.currentTimeMillis();
            synchronized (mShardContext) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mCameraTexture);
                GLES20.glUseProgram(yuvConverterProgram);
                GLES20.glVertexAttribPointer(yuvConverterVertexLocation, 2, GLES20.GL_FLOAT, true, 0, mFullScreenVertexPoint);
                GLES20.glVertexAttribPointer(yuvConverterTextureCoordinationLocation, 2, GLES20.GL_FLOAT, true, 0, mFullScreenTexturePoint);
                GLES20.glEnableVertexAttribArray(yuvConverterVertexLocation);
                GLES20.glEnableVertexAttribArray(yuvConverterTextureCoordinationLocation);
                GLES20.glUniform1i(yuvConverterTextureLocation, 0);
                GLES20.glUniform1f(yuvConverterWidthLocation, mYuvWidth);
                GLES20.glUniform1f(yumConverterHeightLocation, mYuvHeight);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            }
            mImageBuffer.clear();
            GLES20.glReadPixels(0, 0, mYuvWidth, mYuvHeight * 3 / 8, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, mImageBuffer);
//            File f = new File("/sdcard/img.jpg");
//            if (!f.exists()) {
//                mImageBuffer0.clear();
//                YUV2RGB.I420ToRGB(mImageBuffer,mImageBuffer0,mYuvWidth,mYuvHeight);
//                Bitmap bmp = Bitmap.createBitmap( mYuvWidth, mYuvHeight, Bitmap.Config.ARGB_8888);
//                bmp.copyPixelsFromBuffer(mImageBuffer0);
//                try {
//                    FileOutputStream fs = new FileOutputStream("/sdcard/img.jpg");
//                    bmp.compress(Bitmap.CompressFormat.JPEG, 100, fs);
//                    fs.flush();
//                    fs.close();
//                } catch (FileNotFoundException e) {
//                    e.printStackTrace();
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }
//            }
            deltaTime = 50 - (System.currentTimeMillis() - deltaTime);
            if (deltaTime > 0) {
                SystemClock.sleep(deltaTime);
            }
        }
    }
}
