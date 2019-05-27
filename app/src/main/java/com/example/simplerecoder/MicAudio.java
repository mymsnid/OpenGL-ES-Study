package com.askey.dvr.cdr7010.dashcam.simplerecoder;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class MicAudio extends Thread{

    private final String TAG = "MicAudio";
    private AudioRecord mAudioRecord;
    private int mSta = 0;
    private ReentrantLock mLock;
    private Condition mCond;
    private MediaCodec mAudioEncoder;
    private MediaFormat mAudioFormat;

    public ArrayBlockingQueue<AudioSimple> mAudioSampleQueue = new ArrayBlockingQueue<AudioSimple>(700);
    public ArrayBlockingQueue<AudioSimple> mAudioSampleEmptyQueue = new ArrayBlockingQueue<AudioSimple>(700);

    public MicAudio(){
        mLock = new ReentrantLock();
        mCond = mLock.newCondition();
        mAudioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,44100,1);
        mAudioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        mAudioFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_IN_MONO);
        mAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 131072);
        mAudioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,8192);
        try {
            mAudioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            mAudioEncoder.configure(mAudioFormat,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
            mAudioEncoder.start();
            for(int i = 0;i < 700;i++){
                mAudioSampleEmptyQueue.add(new AudioSimple());
            }
            Log.d(TAG, "Mic audio create success.");
            mLock.lock();
            start();
            mCond.awaitUninterruptibly();
            mLock.unlock();
        } catch (IOException e) {
            mAudioEncoder = null;
            Log.e(TAG, "Mic audio create fail.");
            e.printStackTrace();
        }
    }

    public void startRecorder(){
        mLock.lock();
        mSta = 1;
        mCond.signal();
        mLock.unlock();
    }

    public void stopRecorder(){
        mSta = 0;
        mLock.lock();
        if(mAudioRecord != null){
            mAudioRecord.stop();
            mAudioRecord.release();
            mAudioRecord = null;
        }
        for(AudioSimple s:mAudioSampleQueue){
            mAudioSampleEmptyQueue.add(s);
        }
        mAudioSampleQueue.clear();
        mLock.unlock();
    }

    public void exitRecorder(){
        mSta = 0;
        mLock.lock();
        if(mAudioRecord != null){
            mAudioRecord.stop();
            mAudioRecord.release();
            mAudioRecord = null;
        }
        if(mAudioEncoder != null) {
            mAudioEncoder.flush();
            mAudioEncoder.stop();
            mAudioEncoder.release();
            mAudioEncoder = null;
        }
        mAudioSampleQueue.clear();
        mAudioSampleEmptyQueue.clear();
        mSta = -1;
        mCond.signal();
        mLock.unlock();
    }

    public AudioSimple readSample(){
        AudioSimple sample;
        sample = mAudioSampleQueue.poll();
        if(sample != null){
            mAudioSampleEmptyQueue.add(sample);
        }
        return sample;
    };


    @Override
    public void run() {
        int encoder_input_index;
        int encoder_output_index;
        MediaCodec.BufferInfo encoder_buffer_info = new MediaCodec.BufferInfo();
        ByteBuffer input_buffer;
        int recorder_read_ret;
        long pts;
        int dec_cnt;
        AudioSimple simple;
        boolean error_flag = true;
        android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        mLock.lock();
        mCond.signal();
        while(true){
            switch(mSta){
                case 0:
                    Log.d(TAG, "Mic audio task sleep.");
                    mCond.awaitUninterruptibly();
                    Log.d(TAG, "Mic audio task wakeup, next state = " + mSta);
                    if(mSta == 1){
                        mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,44100, AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,16384);
                        mAudioRecord.startRecording();
                        mAudioEncoder.flush();
                    }
                    break;
                case 1:
                    encoder_input_index = mAudioEncoder.dequeueInputBuffer(0);
                    if(encoder_input_index >= 0){
                        input_buffer = mAudioEncoder.getInputBuffer(encoder_input_index);
                        recorder_read_ret = mAudioRecord.read(input_buffer,8192);
                        pts = System.nanoTime() / 1000 - 92880;
                        if(recorder_read_ret != 8192){
                            Log.e(TAG, "Mic recorder read buffer error, system problem, force stop mic recorder, read ret = " + recorder_read_ret);
                            mSta = 0;
                            break;
                        }else{
                            mAudioEncoder.queueInputBuffer(encoder_input_index,0,8192, pts,0);
                        }
                        dec_cnt = 4;
                        do{
                            encoder_output_index = mAudioEncoder.dequeueOutputBuffer(encoder_buffer_info,10000);
                            if(encoder_output_index >= 0){
                                dec_cnt--;
                                simple = mAudioSampleEmptyQueue.poll();
                                if(simple != null){
                                    simple.mPts = encoder_buffer_info.presentationTimeUs;
                                    simple.size = encoder_buffer_info.size;
                                    mAudioEncoder.getOutputBuffer(encoder_output_index).get(simple.mData,0,simple.size);
                                    mAudioSampleQueue.add(simple);
                                    error_flag = false;
                                }else{
                                    if(!error_flag) {
                                        error_flag = true;
                                        Log.e(TAG, "Mic encoder stream queue overflow.");
                                    }
                                }
                                mAudioEncoder.releaseOutputBuffer(encoder_output_index,false);
                            }
                        }while(dec_cnt > 0);
                    }
                    break;
                case -1:
                    mLock.unlock();
                    Log.d(TAG, "Mic recording task exit.");
                    return;
            }
        }
    }

    public class AudioSimple{
        public byte[] mData = new byte[400];
        public long mPts;
        public int size;
    }
}
