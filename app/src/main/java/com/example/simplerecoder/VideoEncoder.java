package com.askey.dvr.cdr7010.dashcam.simplerecoder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

public class VideoEncoder {

    private final String TAG;

    public Surface mEncoderInputSurface = null;
    public MediaFormat mOutputFormat = null;

    private MediaCodec mEncoder = null;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private int  mFrameCount = -1;
    private long mFrameCountTime;
    private int  mDataBytesCount = -1;
    private long mDataBytesCountTime;

    private ByteBuffer buffer;

    public VideoEncoder(Camera.CAM_NAME id, int width, int height, int frameRate, int bitRate) {
        TAG = "EncoderUtil_" + id;
        String name;
        mHandlerThread = new HandlerThread("EncoderUtilTask_" + id);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        MediaFormat MF = new MediaFormat();
        MF.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC);
        MF.setInteger(MediaFormat.KEY_WIDTH,width);
        MF.setInteger(MediaFormat.KEY_HEIGHT,height);
        MF.setInteger(MediaFormat.KEY_FRAME_RATE,frameRate);
        MF.setInteger(MediaFormat.KEY_BIT_RATE,bitRate);
        MF.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
        //MF.setInteger(MediaFormat.KEY_BITRATE_MODE,MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        MF.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        name = list.findEncoderForFormat(MF);
        try {
            mEncoder = MediaCodec.createByCodecName(name);
            mEncoder.setCallback(new MediaCodec.Callback() {
                @Override
                public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                    codec.releaseOutputBuffer(index,false);
                    if(mFrameCount == -1){
                        mFrameCountTime = System.currentTimeMillis();
                        mFrameCount++;
                    }else if(mFrameCount == 256){
                        mFrameCountTime = System.currentTimeMillis() - mFrameCountTime;
                        Log.d(TAG, "Encoder frame output rate = " + (float)mFrameCount/ mFrameCountTime *1000);
                        mFrameCount = -1;
                    }else{
                        mFrameCount++;
                    }
                    if(mDataBytesCount == -1){
                        mDataBytesCountTime = System.currentTimeMillis();
                        mDataBytesCount = 0;
                    }else if(mDataBytesCount >= 10485760){
                        mDataBytesCountTime = System.currentTimeMillis() - mDataBytesCountTime;
                        Log.d(TAG, "Encoder data output rate = " + (float)mDataBytesCount/ mDataBytesCountTime/1000 + "MB/s");
                        mDataBytesCount = -1;
                    }else{
                        mDataBytesCount += info.size;
                    }
                }
                @Override
                public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                    Log.d(TAG, "onError: ");
                }
                @Override
                public void onInputBufferAvailable(@NonNull MediaCodec codec, int index){

                    Log.d(TAG, "onInputBufferAvailable: ");
                }
                @Override
                public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                    mOutputFormat = format;
                    Log.d(TAG, "onOutputFormatChanged: ");
                }
            },mHandler);
            mEncoder.configure(MF,null,null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mEncoderInputSurface = mEncoder.createInputSurface();
            mEncoder.start();
        } catch (IOException e) {
            Log.e(TAG, "mEncoder: Create mEncoder Fail");
            if(mEncoderInputSurface != null){
                mEncoderInputSurface.release();
                mEncoderInputSurface = null;
            }
            if(mEncoder != null){
                mEncoder.release();
                mEncoder = null;
            }
            e.printStackTrace();
        }
    }
}
