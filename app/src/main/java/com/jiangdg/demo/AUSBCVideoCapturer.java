package com.jiangdg.demo;

import android.content.Context;
import android.os.SystemClock;

import com.jiangdg.ausbc.utils.Logger;
import com.jiangdg.natives.YUVUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import livekit.org.webrtc.CapturerObserver;
import livekit.org.webrtc.JavaI420Buffer;
import livekit.org.webrtc.SurfaceTextureHelper;
import livekit.org.webrtc.VideoCapturer;
import livekit.org.webrtc.VideoFrame;
import livekit.org.webrtc.YuvHelper;

public class AUSBCVideoCapturer implements VideoCapturer {

    public interface AUSBCallback {
        void callStartCapture();

        void callStopCapture();
    }

    private final static String TAG = "AUSBCVideoCapturer";
    private CapturerObserver capturerObserver;
    private final AUSBCallback ausbCallback;

    public AUSBCVideoCapturer(AUSBCallback ausbCallback) throws IOException {
        this.ausbCallback = ausbCallback;
    }

    @Override
    public void initialize(SurfaceTextureHelper surfaceTextureHelper, Context applicationContext,
                           CapturerObserver capturerObserver) {
        this.capturerObserver = capturerObserver;
    }

    @Override
    public void startCapture(int width, int height, int frameRate) {
        ausbCallback.callStartCapture();
    }

    @Override
    public void stopCapture() throws InterruptedException {
        ausbCallback.callStopCapture();
    }

    @Override
    public void changeCaptureFormat(int width, int height, int frameRate) {
        // Empty on purpose
    }

    @Override
    public void dispose() {
    }

    @Override
    public boolean isScreencast() {
        return false;
    }

    /**
     * 外部调用，用于通知SDK外部是否已经成功开启视频数据提交
     *
     * @param success 是否成功开启
     */
    public void onCapturerStarted(boolean success) {
        if (capturerObserver != null) {
            capturerObserver.onCapturerStarted(success);
        }
    }

    /**
     * 外部调用，用于通知SDK外部已经停止视频数据提交
     */
    public void onCapturerStopped() {
        if (capturerObserver != null) {
            capturerObserver.onCapturerStopped();
        }
    }

    /**
     * 外部调用，用于提交每一帧视频数据
     *
     * @param buffer    视频数据
     * @param width     视频数据高度
     * @param height    视频数据高度
     * @param format    视频数据格式 I420、NV21
     * @return 视频帧数据是否提交成功，0成功，其他失败。
     * <p>-1-数据为空或长度不合法;
     * <p>-2-长宽不合法，如超过最大分辨率1920*1080或长宽和数据长度不匹配;
     * <p>-3-旋转角度不合法;
     * <p>-4-帧率不合法;
     * <p>-5-数据格式不合法;
     * <p>-10-其他错误;
     */
    public int onByteBufferFrameCaptured(
            byte[] buffer,
            int width,
            int height,
            int format
    ) {

        Logger.INSTANCE.d(TAG, "onByteBufferFrameCaptured:"
                + ",width:" + width + ",height:" + height
                + ",format:" + format
        );
        int ret = -10;
        if (buffer == null) {
            Logger.INSTANCE.e(TAG, "buffer is null");
            return -1;
        }

//        if (buffer.length < size) {
//            Logger.INSTANCE.e(TAG, "Illegal data length!");
//            return -2;
//        }

        try {
            VideoFrame videoFrame = getNextFrame(buffer, width, height);
            if (capturerObserver != null) {
                capturerObserver.onFrameCaptured(videoFrame);
                ret = 0;
            }
            videoFrame.release();
        } catch (Exception e) {
            Logger.INSTANCE.e(TAG, " onByteBufferFrameCaptured " + e);
        }

        return ret;
    }

    private VideoFrame getNextFrame(byte[] data,int width, int height) {
        final long captureTimeNs = TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime());
        // Create a direct ByteBuffer for each plane
        ByteBuffer yPlane = ByteBuffer.allocateDirect(width * height);
        ByteBuffer uPlane = ByteBuffer.allocateDirect(width * height / 4);
        ByteBuffer vPlane = ByteBuffer.allocateDirect(width * height / 4);

        // Convert NV21 to I420
        convertNV21ToI420(data, yPlane, uPlane, vPlane, width, height);

        int strideY = width;
        int strideU = width / 2;
        int strideV = width / 2;

        // Create a JavaI420Buffer from the YUV planes
        VideoFrame.Buffer buffer = JavaI420Buffer.wrap(width, height, yPlane, strideY, uPlane, strideU, vPlane, strideV,null);
        return new VideoFrame(buffer, 0 /* rotation */, captureTimeNs);
    }

    private void convertNV21ToI420(byte[] nv21, ByteBuffer yPlane, ByteBuffer uPlane, ByteBuffer vPlane, int width, int height) {
        int ySize = width * height;
        int uvSize = width * height / 4;

        // Copy Y plane
        yPlane.put(nv21, 0, ySize).flip();

        // Separate UV plane into U and V planes
        for (int j = 0; j < uvSize; j++) {
            // 下面 u v变量所作操作 ai给的是错的，会导致蓝色变成红色，更改之后正常显示
            vPlane.put(nv21[ySize + j * 2]);
            uPlane.put(nv21[ySize + j * 2 + 1]);
        }
        uPlane.flip();
        vPlane.flip();
    }

}