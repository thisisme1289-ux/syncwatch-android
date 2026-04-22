package com.syncwatch.screenshare

import android.content.Context
import android.util.Log
import com.syncwatch.network.SocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.webrtc.*

private const val TAG = "ScreenShareGuest"

/**
 * Receives a WebRTC screen-share stream from the host and renders it on
 * a [SurfaceViewRenderer].
 *
 * Lifecycle:
 *  1. Create ScreenShareGuest
 *  2. Call [start] — listens for `ss_offer` and sets up PeerConnection
 *  3. Call [stop] on fragment destroy
 *
 * Note: one-to-one only (matches server behaviour).
 */
class ScreenShareGuest(
    private val context: Context,
    private val roomId: String,
    private val renderer: SurfaceViewRenderer
) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var eglBase: EglBase? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    fun start() {
        eglBase = EglBase.create()
        renderer.init(eglBase!!.eglBaseContext, null)
        renderer.setMirror(false)

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .createPeerConnectionFactory()

        observeSignals()
    }

    private fun observeSignals() {
        scope.launch {
            SocketManager.ssOffer.collect { payload ->
                Log.d(TAG, "Received ss_offer — creating peer connection")
                handleOffer(payload.offer)
            }
        }

        scope.launch {
            SocketManager.ssIce.collect { payload ->
                Log.d(TAG, "Received ICE candidate from host")
                peerConnection?.addIceCandidate(IceCandidate("0", 0, payload.candidate))
            }
        }

        scope.launch {
            SocketManager.ssStopped.collect {
                Log.d(TAG, "Host stopped screen share")
                renderer.clearImage()
            }
        }
    }

    private fun handleOffer(offerSdp: String) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {

            override fun onIceCandidate(candidate: IceCandidate) {
                SocketManager.sendSsIce(roomId, candidate.sdp)
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track() as? VideoTrack ?: return
                Log.d(TAG, "onAddTrack — attaching video to renderer")
                track.addSink(renderer)
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
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                Log.d(TAG, "Connection state: $newState")
            }
        })

        // Set remote description (the offer) then create answer
        val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                createAnswer()
            }
            override fun onSetFailure(error: String?) = Log.e(TAG, "setRemoteDesc fail: $error")
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, remoteSdp)
    }

    private fun createAnswer() {
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local desc set, emitting ss_answer")
                        SocketManager.sendSsAnswer(roomId, sdp.description)
                    }
                    override fun onSetFailure(e: String?) = Log.e(TAG, "setLocalDesc fail: $e")
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) = Log.e(TAG, "createAnswer fail: $error")
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints())
    }

    fun stop() {
        Log.d(TAG, "stop()")
        scope.cancel()
        peerConnection?.dispose()
        renderer.release()
        factory.dispose()
        eglBase?.release()
    }
}
