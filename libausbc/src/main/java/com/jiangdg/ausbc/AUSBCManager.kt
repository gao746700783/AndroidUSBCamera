package com.jiangdg.ausbc

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.usb.USBMonitor
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.collections.hashMapOf

class AUSBCManager private constructor() {
    companion object {
        val instance: AUSBCManager by lazy { AUSBCManager() }

        private const val TAG = "AUSBCManager"
    }

    private var isInitialized = false
    private lateinit var mContext: Context

    private var mCameraClient: MultiCameraClient? = null
    private val _mCameraMap = hashMapOf<Int, MultiCameraClient.ICamera>()
    val mCameraMap:HashMap<Int, MultiCameraClient.ICamera>
        get() = _mCameraMap

    private var _mCameraRequest: CameraRequest = getCameraRequest()
    // cameraRequest params
    var mCameraRequest: CameraRequest
        set(value) {
            _mCameraRequest = value
        }
        get() = _mCameraRequest

    // camera callback, device connected/disconnected/attached/disattached
    private val mCameraCallback: CopyOnWriteArrayList<AUSBCCallback> = CopyOnWriteArrayList()

    // cameraState Callback
    var mCameraStateCallback: ICameraStateCallBack? = null
        set(value) {
            field = value
        }

    /**
     * 初始化
     * @param context  上下文
     * @param config   会议相关配置封装对象
     */
    fun init(context: Context) {
        if (isInitialized) {
            Log.d(TAG, "MeetingManager is already initialized.")
            return
        }
        isInitialized = true
        mContext = context

        mCameraClient = MultiCameraClient(context, object : IDeviceConnectCallBack {
            override fun onAttachDev(device: UsbDevice?) {
                device ?: return
                if (_mCameraMap.containsKey(device.deviceId)) {
                    return
                }
                generateCamera(context, device).apply {
                    _mCameraMap[device.deviceId] = this
                    mCameraCallback.forEach { it.onCameraAttached(this) }
                }
                // Initiate permission request when device insertion is detected
                // If you want to open the specified camera, you need to let isAutoRequestPermission() false
                // And then you need to call requestPermission(device) in your own Fragment when onAttachDev() called
                if (isAutoRequestPermission()) {
                    requestPermission(device)
                }
            }

            override fun onDetachDec(device: UsbDevice?) {
                _mCameraMap.remove(device?.deviceId)?.apply {
                    setUsbControlBlock(null)
                    mCameraCallback.forEach { it.onCameraDetached(this) }
                }
            }

            override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                device ?: return
                ctrlBlock ?: return
                _mCameraMap[device.deviceId]?.apply {
                    setUsbControlBlock(ctrlBlock)
                    this.setCameraStateCallBack(mCameraStateCallback)
                    mCameraCallback.forEach { it.onCameraConnected(this) }
                }
            }

            override fun onDisConnectDec(
                device: UsbDevice?,
                ctrlBlock: USBMonitor.UsbControlBlock?
            ) {
                _mCameraMap[device?.deviceId]?.apply {
                    mCameraCallback.forEach { it.onCameraDisConnected(this) }
                }
            }

            override fun onCancelDev(device: UsbDevice?) {
                _mCameraMap[device?.deviceId]?.apply {
                    mCameraCallback.forEach { it.onCameraDisConnected(this) }
                }
            }
        }).apply {
            this.register()
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        if (!isInitialized) {
            return
        }
        isInitialized = false
        _mCameraMap.values.forEach { it.closeCamera() }
        _mCameraMap.clear()
        mCameraClient?.unRegister()
        mCameraClient?.destroy()
        mCameraClient = null
    }

    private fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(640)
            .setPreviewHeight(480)
            .setRawPreviewData(true)
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_YUYV)
            .create()
    }

    /**
     * Generate camera
     *
     * @param ctx context [Context]
     * @param device Usb device, see [UsbDevice]
     * @return Inheritor assignment camera api policy
     */
    fun generateCamera(ctx: Context, device: UsbDevice): MultiCameraClient.ICamera {
        return CameraUVC(ctx, device)
    }

    /**
     * If you want to open the specified camera,you need to let isAutoRequestPermission() false.
     *  And then you need to call requestPermission(device) in your own Fragment
     * when onAttachDev() called, default is true.
     */
    fun isAutoRequestPermission() = true

    /**
     * Request permission
     *
     * @param device see [UsbDevice]
     */
    fun requestPermission(device: UsbDevice?) {
        mCameraClient?.requestPermission(device)
    }

    /**
     * Has permission
     *
     * @param device see [UsbDevice]
     */
    fun hasPermission(device: UsbDevice?) = mCameraClient?.hasPermission(device) == true

    fun openDebug(debug: Boolean) {
        mCameraClient?.openDebug(debug)
    }

    fun addUSBCCallback(listener: AUSBCCallback) {
        if (!mCameraCallback.contains(listener)) {
            mCameraCallback.add(listener)
        }
    }

    fun removeUSBCCallback(listener: AUSBCCallback) {
        mCameraCallback.remove(listener)
    }

    interface AUSBCCallback {

        /**
         * On camera connected
         *
         * @param camera see [MultiCameraClient.ICamera]
         */
        fun onCameraConnected(camera: MultiCameraClient.ICamera) {}

        /**
         * On camera disconnected
         *
         * @param camera see [MultiCameraClient.ICamera]
         */
        fun onCameraDisConnected(camera: MultiCameraClient.ICamera) {}

        /**
         * On camera attached
         *
         * @param camera see [MultiCameraClient.ICamera]
         */
        fun onCameraAttached(camera: MultiCameraClient.ICamera) {}

        /**
         * On camera detached
         *
         * @param camera see [MultiCameraClient.ICamera]
         */
        fun onCameraDetached(camera: MultiCameraClient.ICamera) {}


    }
}