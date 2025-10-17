package com.jiangdg.demo

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.load.resource.bitmap.VideoDecoder.byteBuffer
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.BaseViewHolder
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.MultiCameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.callback.IEncodeDataCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.utils.Logger
import com.jiangdg.ausbc.utils.ToastUtils
import com.jiangdg.demo.databinding.FragmentMultiCameraBinding
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.util.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.charset.Charset

/** Multi-road camera demo
 *
 * @author Created by jiangdg on 2022/7/20
 */
class DemoMultiCameraFragment : MultiCameraFragment(), ICameraStateCallBack {
    private lateinit var mAdapter: CameraAdapter
    private lateinit var mViewBinding: FragmentMultiCameraBinding
    private lateinit var room: Room
    private val mCameraList by lazy {
        ArrayList<MultiCameraClient.ICamera>()
    }
    private val mHasRequestPermissionList by lazy {
        ArrayList<MultiCameraClient.ICamera>()
    }

    override fun onCameraAttached(camera: MultiCameraClient.ICamera) {
        mAdapter.data.add(camera)
        mAdapter.notifyItemInserted(mAdapter.data.size - 1)
        mViewBinding.multiCameraTip.visibility = View.GONE
    }

    override fun onCameraDetached(camera: MultiCameraClient.ICamera) {
        mHasRequestPermissionList.remove(camera)
        for ((position, cam) in mAdapter.data.withIndex()) {
            if (cam.getUsbDevice().deviceId == camera.getUsbDevice().deviceId) {
                camera.closeCamera()
                mAdapter.data.removeAt(position)
                mAdapter.notifyItemRemoved(position)
                break
            }
        }
        if (mAdapter.data.isEmpty()) {
            mViewBinding.multiCameraTip.visibility = View.VISIBLE
        }
    }

    override fun generateCamera(ctx: Context, device: UsbDevice): MultiCameraClient.ICamera {
        return CameraUVC(ctx, device)
    }

    override fun onCameraConnected(camera: MultiCameraClient.ICamera) {
        for ((position, cam) in mAdapter.data.withIndex()) {
            if (cam.getUsbDevice().deviceId == camera.getUsbDevice().deviceId) {
                val textureView = mAdapter.getViewByPosition(position, R.id.multi_camera_texture_view)
                cam.openCamera(textureView, getCameraRequest())
                cam.setCameraStateCallBack(this)
                break
            }
        }
        // request permission for other camera
        mAdapter.data.forEach { cam ->
            val device = cam.getUsbDevice()
            if (! hasPermission(device)) {
                mHasRequestPermissionList.add(cam)
                requestPermission(device)
                return@forEach
            }
        }
    }

    override fun onCameraDisConnected(camera: MultiCameraClient.ICamera) {
        camera.closeCamera()
    }


    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        if (code == ICameraStateCallBack.State.ERROR) {
            ToastUtils.show(msg ?: "open camera failed.")
        }
        for ((position, cam) in mAdapter.data.withIndex()) {
            if (cam.getUsbDevice().deviceId == self.getUsbDevice().deviceId) {
                mAdapter.notifyItemChanged(position, "switch")
                break
            }
        }
    }


    override fun initView() {
        super.initView()
        openDebug(true)
        mAdapter = CameraAdapter()
        mAdapter.setNewData(mCameraList)
        mAdapter.bindToRecyclerView(mViewBinding.multiCameraRv)
        mViewBinding.multiCameraRv.adapter = mAdapter
        mViewBinding.multiCameraRv.layoutManager = GridLayoutManager(requireContext(), 3)
        mAdapter.setOnItemChildClickListener { adapter, view, position ->
            val camera = adapter.data[position] as MultiCameraClient.ICamera
            when (view.id) {
                R.id.multi_camera_capture_image -> {
                    camera.captureImage(object : ICaptureCallBack {
                        override fun onBegin() {}

                        override fun onError(error: String?) {
                            ToastUtils.show(error ?: "capture image failed")
                        }

                        override fun onComplete(path: String?) {
                            ToastUtils.show(path ?: "capture image success")
                        }
                    })
                }
                R.id.multi_camera_capture_video -> {
                    if (camera.isRecording()) {
                        camera.captureVideoStop()
                        return@setOnItemChildClickListener
                    }
//                    camera.captureVideoStart(object : ICaptureCallBack {
//                        override fun onBegin() {
//                            mAdapter.notifyItemChanged(position, "video")
//                        }
//
//                        override fun onError(error: String?) {
//                            mAdapter.notifyItemChanged(position, "video")
//                            ToastUtils.show(error ?: "capture video failed")
//                        }
//
//                        override fun onComplete(path: String?) {
//                            mAdapter.notifyItemChanged(position, "video")
//                            ToastUtils.show(path ?: "capture video success")
//                        }
//                    })

                    val capturer = AUSBCVideoCapturer(object : AUSBCVideoCapturer.AUSBCallback{
                        override fun callStartCapture() {
                            camera.captureStreamStart()
                        }

                        override fun callStopCapture() {
                            camera.captureStreamStop()
                        }
                    })
                    camera.addPreviewDataCallBack(object : IPreviewDataCallBack {
                        override fun onPreviewData(
                            data: ByteArray?,
                            width: Int,
                            height: Int,
                            format: IPreviewDataCallBack.DataFormat
                        ) {
                            capturer.onByteBufferFrameCaptured(data,width,height,format.ordinal)
                        }
                    })
                    publishUsbStream(capturer)

                }
                else -> {
                }
            }
        }

        initLiveKit()
        connectToRoom()
    }

    // ############ for livekit #### #############

    private fun publishUsbStream(capturer: AUSBCVideoCapturer) {
        val localParticipant = room.localParticipant
        lifecycleScope.launch {
            val fileTrack = localParticipant.createVideoTrack(name = "usb", capturer)
            localParticipant.publishVideoTrack(fileTrack)
            capturer.startCapture(1280, 720, 30)

            // 使用 usb，禁用摄像头
            localParticipant.setCameraEnabled(false)
        }
    }

    private fun initLiveKit(){
        // Create Room object.
        room = LiveKit.create(requireContext().applicationContext)

        // Setup the video renderer
        room.initVideoRenderer(mViewBinding.renderer)
        room.initVideoRenderer(mViewBinding.localCamera)
    }

    private fun connectToRoom() {
        val url = "wss://cq.hongxinspkf.com:9442"
        val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjE3NjA2NzAzMzQsImlzcyI6ImRldmtleSIsIm1ldGFkYXRhIjoie1wib3JnXCI6XCJkZWZhdWx0XCIsXCJpc093bmVyXCI6dHJ1ZSxcImlzQ29Ib3N0XCI6ZmFsc2UsXCJpc0RlYWZuZXNzXCI6ZmFsc2UsXCJvcmlnaW5cIjpcIlwiLFwibWV0YWRhdGFcIjpudWxsfSIsIm5iZiI6MTc2MDY2NjczNCwic3ViIjoiMTExMXBwcHBwIiwidmlkZW8iOnsicm9vbSI6IjcyODAxNDA2Iiwicm9vbUpvaW4iOnRydWV9fQ.zDPfY5Ww68-2I5ytY4tDqWeZh1GCHC9FnZR_LxKpuao"

        lifecycleScope.launch {
            // Setup event handling.
            launch {
                room.events.collect { event ->
                    when (event) {
                        is RoomEvent.TrackSubscribed -> onTrackSubscribed(event)
                        else -> {}
                    }
                }
            }

            // Connect to server.
            try {
                room.connect(
                    url,
                    token,
                )
            } catch (e: Exception) {
                Log.e("MainActivity", "Error while connecting to server:", e)
                return@launch
            }

            // Turn on audio/video recording.
            val localParticipant = room.localParticipant
            localParticipant.setMicrophoneEnabled(false) // 禁用 mic
            localParticipant.setCameraEnabled(true)      // 启用摄像头

//            // Attach local video camera
//            val localTrack = localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
//            if (localTrack != null) {
//                attachLocalVideo(localTrack)
//            }

            val videoTrackPubFlow = localParticipant::videoTrackPublications.flow
                .map { localParticipant to it }
                .flatMapLatest { (participant, videoTracks) ->
                    val trackPublication = getVideoTrackByName(participant,"usb")
                        ?: getVideoTrackBySource(participant,Track.Source.SCREEN_SHARE)
                        ?: getVideoTrackBySource(participant,Track.Source.CAMERA)
                        ?: videoTracks.firstOrNull()?.first
                    flowOf(trackPublication)
                }
            val videoTrackFlow = videoTrackPubFlow.flatMapLatestOrNull { pub -> pub::track.flow }
            lifecycleScope.launch {
                videoTrackFlow.collectLatest { videoTrack ->
                    videoTrack?.let {
                        attachLocalVideo(videoTrack as VideoTrack)
                    }
                }
            }

            // Attach video of remote participant if already available.
            val remoteVideoTrack = room.remoteParticipants.values.firstOrNull()
                ?.getTrackPublication(Track.Source.CAMERA)
                ?.track as? VideoTrack

            if (remoteVideoTrack != null) {
                attachVideo(remoteVideoTrack)
            }
        }
    }

    private fun getVideoTrackBySource(participant: Participant,source:Track.Source): TrackPublication? {
        return participant.getTrackPublication(source)
    }
    private fun getVideoTrackByName(participant: Participant, name:String): TrackPublication? {
        return participant.getTrackPublicationByName(name)
    }

    private fun onTrackSubscribed(event: RoomEvent.TrackSubscribed) {
        val track = event.track
        if (track is VideoTrack) {
            attachVideo(track)
        }
    }

    private fun attachVideo(videoTrack: VideoTrack) {
        videoTrack.addRenderer(mViewBinding.renderer)
    }

    private fun attachLocalVideo(videoTrack: VideoTrack) {
        videoTrack.addRenderer(mViewBinding.localCamera)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    inline fun <T, R> Flow<T?>.flatMapLatestOrNull(
        crossinline transform: suspend (value: T) -> Flow<R>,
    ): Flow<R?> {
        return flatMapLatest {
            if (it == null) {
                flowOf(null)
            } else {
                transform(it)
            }
        }
    }
    // ############ for livekit #### #############

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        mViewBinding = FragmentMultiCameraBinding.inflate(inflater, container, false)
        return mViewBinding.root
    }

    private fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(640)
            .setPreviewHeight(480)
            .setRawPreviewData(true)
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_YUYV)
            .create()
    }

    inner class CameraAdapter :
        BaseQuickAdapter<MultiCameraClient.ICamera, BaseViewHolder>(R.layout.layout_item_camera) {
        override fun convert(helper: BaseViewHolder, camera: MultiCameraClient.ICamera?) {}

        override fun convertPayloads(
            helper: BaseViewHolder,
            camera: MultiCameraClient.ICamera?,
            payloads: MutableList<Any>
        ) {
            camera ?: return
            if (payloads.isEmpty()) {
                return
            }
            helper.setText(R.id.multi_camera_name, camera.getUsbDevice().deviceName)
            helper.addOnClickListener(R.id.multi_camera_capture_video)
            helper.addOnClickListener(R.id.multi_camera_capture_image)
            // local update
            val switchIv = helper.getView<ImageView>(R.id.multi_camera_switch)
            val captureVideoIv = helper.getView<ImageView>(R.id.multi_camera_capture_video)
            if (payloads.find { "switch" == it } != null) {
                if (camera.isCameraOpened()) {
                    switchIv.setImageResource(R.mipmap.ic_switch_on)
                } else {
                    switchIv.setImageResource(R.mipmap.ic_switch_off)
                }
            }
            if (payloads.find { "video" == it } != null) {
                if (camera.isRecording()) {
                    captureVideoIv.setImageResource(R.mipmap.ic_capture_video_on)
                } else {
                    captureVideoIv.setImageResource(R.mipmap.ic_capture_video_off)
                }
            }
        }
    }
}