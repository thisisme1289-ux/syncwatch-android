package com.syncwatch.screenshare

import android.content.Context
import android.content.Intent
import android.util.Log
import com.syncwatch.network.SocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.webrtc.*

private const val TAG = "ScreenShareHost"

/**
 * Captures the screen using MediaProjection and streams it to guests via WebRTC.
 *
 * Lifecycle:
 *  1. Create ScreenShareHost
 *  2. Call [start] — sets up PeerConnectionFactory
 *  3. When you have the MediaProjection token, call [startCapture]
 *  4. Call [stop] when done
 *
 * Limitation: one peer connection per guest (server enforces one guest at a time).
 * No TURN server — will fail behind symmetric NAT.
 */
class ScreenShareHost(
    private val context: Context,
    private val roomId: String,
    private val resultCode: Int,
    private val resultData: Intent
) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var eglBase: EglBase? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    fun start() {
        eglBase = EglBase.create()

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .createPeerConnectionFactory()

        observeIncomingSignals()
    }

    fun startCapture(resultCode: Int, resultData: Intent) {
        videoCapturer = ScreenCapturerAndroid(
            resultData,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by system")
                    stop()
                }
            }
        )

        videoSource = factory.createVideoSource(true /* isScreencast */)
        videoCapturer!!.initialize(
            SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext),
            context,
            videoSource!!.capturerObserver
        )
        videoCapturer!!.startCapture(1280, 720, 15) // 720p @ 15fps — low data

        localVideoTrack = factory.createVideoTrack("screen_track_0", videoSource)

        createPeerConnectionAndOffer()
    }

    private fun createPeerConnectionAndOffer() {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {

            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "onIceCandidate: ${candidate.sdpMid}")
                SocketManager.sendSsIce(roomId, candidate.sdp)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE state: $state")
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                Log.d(TAG, "Connection state: $newState")
            }
        })

        // Add video track to the connection
        val stream = factory.createLocalMediaStream("screen_stream")
        stream.addTrack(localVideoTrack)
        peerConnection?.addStream(stream)

        // Create and set local SDP offer
        val sdpConstraints = MediaConstraints()
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local description set, emitting ss_offer")
                        SocketManager.sendSsOffer(roomId, sdp.description)
                    }
                    override fun onSetFailure(error: String?) = Log.e(TAG, "setLocalDesc fail: $error")
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) = Log.e(TAG, "createOffer fail: $error")
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, sdpConstraints)
    }

    private fun observeIncomingSignals() {
        scope.launch {
            SocketManager.ssAnswer.collect { payload ->
                Log.d(TAG, "Received ss_answer")
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, payload.answer)
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() = Log.d(TAG, "Remote desc set")
                    override fun onSetFailure(e: String?) = Log.e(TAG, "setRemoteDesc fail: $e")
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                }, sdp)
            }
        }

        scope.launch {
            SocketManager.ssIce.collect { payload ->
                Log.d(TAG, "Received ICE candidate from guest")
                val candidate = IceCandidate("0", 0, payload.candidate)
                peerConnection?.addIceCandidate(candidate)
            }
        }
    }

    fun stop() {
        Log.d(TAG, "stop()")
        SocketManager.sendSsStopped(roomId)
        scope.cancel()
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoSource?.dispose()
        localVideoTrack?.dispose()
        peerConnection?.dispose()
        factory.dispose()
        eglBase?.release()
    }
}
