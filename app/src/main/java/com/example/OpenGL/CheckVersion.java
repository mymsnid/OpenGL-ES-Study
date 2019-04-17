package com.example.OpenGL;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.util.Log;

public class CheckVersion {

    public static class GLSupportedVersion{
        public boolean isGL30Supported = false;
        public boolean isGL20Supported = false;
    }

    public static GLSupportedVersion getGLSupportedVersion(){
        GLSupportedVersion ret = new GLSupportedVersion();
        EGLDisplay display;
        EGLContext context3;
        EGLContext context2;
        int[] major = new int[1];
        int[] minor = new int[1];
        int[] attr = new int[]{
                EGL14.EGL_BUFFER_SIZE,32,
                EGL14.EGL_RED_SIZE,8,
                EGL14.EGL_GREEN_SIZE,8,
                EGL14.EGL_BLUE_SIZE,8,
                EGL14.EGL_LUMINANCE_SIZE,0,
                EGL14.EGL_ALPHA_SIZE,8,
                EGL14.EGL_ALPHA_MASK_SIZE,0,
                EGL14.EGL_DEPTH_SIZE,8,
                EGL14.EGL_RENDERABLE_TYPE,EGLExt.EGL_OPENGL_ES3_BIT_KHR | EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID,1,
                EGL14.EGL_NONE
        };
        int[] ctx_attr3 = new int[]{
                EGL14.EGL_CONTEXT_CLIENT_VERSION,3,
                EGL14.EGL_NONE
        };
        int[] ctx_attr2 = new int[]{
                EGL14.EGL_CONTEXT_CLIENT_VERSION,3,
                EGL14.EGL_NONE
        };
        EGLConfig[] config = new EGLConfig[1];
        int[] config_num = new int[1];
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if(display == EGL14.EGL_NO_DISPLAY){
            return ret;
        }
        if(!EGL14.eglInitialize(display,major,0,minor,0)){
            return ret;
        }
        EGL14.eglChooseConfig(display,attr,0,config,0,1,config_num,0);
        if(!EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API)){
            return ret;
        }
        context3 = EGL14.eglCreateContext(display,config[0], EGL14.EGL_NO_CONTEXT,ctx_attr3,0);
        if(context3 !=  EGL14.EGL_NO_CONTEXT){
            ret.isGL30Supported = true;
            EGL14.eglDestroyContext(display,context3);
        }
        context2 = EGL14.eglCreateContext(display,config[0], EGL14.EGL_NO_CONTEXT,ctx_attr2,0);
        if(context2 !=  EGL14.EGL_NO_CONTEXT){
            ret.isGL20Supported = true;
            EGL14.eglDestroyContext(display,context2);
        }
        EGL14.eglTerminate(display);
        EGL14.eglReleaseThread();
        return ret;
    }

}
