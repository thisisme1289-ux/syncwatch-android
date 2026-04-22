package com.syncwatch.ui.watch

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.syncwatch.MainActivity
import com.syncwatch.R
import com.syncwatch.databinding.FragmentWatchBinding
import com.syncwatch.model.RoomJoinedPayload
import com.syncwatch.network.ApiClient
import com.syncwatch.screenshare.ScreenShareGuest
import com.syncwatch.screenshare.ScreenShareHost
import com.syncwatch.screenshare.ScreenShareService
import com.syncwatch.util.collectFlow
import com.syncwatch.util.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class WatchFragment : Fragment() {

    // ── Binding & ViewModel ───────────────────────────────────────────────

    private var _binding: FragmentWatchBinding? = null
    private val binding get() = _binding!!

    private val viewModel: WatchViewModel by viewModels {
        val payload   = requireArguments().getSerializable(ARG_PAYLOAD) as RoomJoinedPayload
        val hostToken = requireArguments().getString(ARG_HOST_TOKEN)
        WatchViewModel.Factory(payload, hostToken)
    }

    // ── Adapters ──────────────────────────────────────────────────────────

    private lateinit var chatAdapter: ChatAdapter
    private lateinit var userListAdapter: UserListAdapter

    // ── Player ────────────────────────────────────────────────────────────

    private var playerController: PlayerController? = null

    // ── Screen share ──────────────────────────────────────────────────────

    private var screenShareHost: ScreenShareHost? = null
    private var screenShareGuest: ScreenShareGuest? = null

    // ── Coroutine scope for upload (outside viewModelScope so it's Fragment-tied) ──

    private val fragmentScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Activity result launchers ─────────────────────────────────────────

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        when (viewModel.mode) {
            "local"  -> playerController?.loadLocalFile(uri)
            "upload" -> uploadFile(uri)
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            toast("Screen capture permission denied")
            return@registerForActivityResult
        }
        screenShareHost?.startCapture(result.resultCode, result.data!!)
    }

    // ── Fragment lifecycle ────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWatchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRoomInfo()
        setupPlayer()
        setupChat()
        setupUserList()
        setupSidebar()
        setupModePanel()
        setupHostControls()
        setupDataMeter()
        observeViewModel()
    }

    // ── Room info ─────────────────────────────────────────────────────────

    private fun setupRoomInfo() {
        binding.tvRoomCode.text = "Code: ${viewModel.initialPayload.code}"
        binding.tvMode.text = viewModel.mode.replaceFirstChar { it.uppercase() }
    }

    // ── Player setup ──────────────────────────────────────────────────────

    private fun setupPlayer() {
        playerController = PlayerController(
            context       = requireContext(),
            scope         = viewModelScope,
            onPlay        = viewModel::onLocalPlay,
            onPause       = viewModel::onLocalPause,
            onSeek        = viewModel::onLocalSeek,
            onRateChange  = viewModel::onLocalRateChange
        )
        binding.playerView.player = playerController!!.player

        // If we already have a media URL (upload mode, re-join), load it immediately
        viewModel.initialPayload.media?.url?.let { url ->
            playerController?.loadUrl(url)
        }

        // Apply the initial sync state from room_joined
        val pb = viewModel.initialPayload.playback
        playerController?.applySync(pb.timestamp, pb.isPlaying, pb.rate)
    }

    // ── Chat ──────────────────────────────────────────────────────────────

    private fun setupChat() {
        val myNickname = viewModel.initialPayload.users
            .firstOrNull { it.socketId == viewModel.mySocketId }?.nickname ?: ""

        chatAdapter = ChatAdapter(myNickname)
        binding.rvChat.apply {
            layoutManager = LinearLayoutManager(requireContext()).also { it.stackFromEnd = true }
            adapter = chatAdapter
        }

        binding.btnSendChat.setOnClickListener {
            val text = binding.etChatInput.text.toString().trim()
            if (text.isNotBlank()) {
                viewModel.sendChat(text)
                binding.etChatInput.setText("")
            }
        }
    }

    // ── User list ─────────────────────────────────────────────────────────

    private fun setupUserList() {
        userListAdapter = UserListAdapter(
            mySocketId    = viewModel.mySocketId,
            isHost        = viewModel.isHost,
            onTransferHost = viewModel::transferHost
        )
        binding.rvUsers.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = userListAdapter
        }
    }

    // ── Sidebar toggle ────────────────────────────────────────────────────

    private fun setupSidebar() {
        binding.btnToggleSidebar.setOnClickListener {
            binding.sidebar.isVisible = !binding.sidebar.isVisible
        }

        // Tab switching within sidebar
        binding.tabUsers.setOnClickListener {
            binding.rvUsers.isVisible = true
            binding.rvChat.isVisible = false
            binding.layoutChatInput.isVisible = false
            binding.tabUsers.isSelected = true
            binding.tabChat.isSelected = false
        }
        binding.tabChat.setOnClickListener {
            binding.rvUsers.isVisible = false
            binding.rvChat.isVisible = true
            binding.layoutChatInput.isVisible = true
            binding.tabUsers.isSelected = false
            binding.tabChat.isSelected = true
        }

        // Default: show users tab
        binding.tabUsers.performClick()
    }

    // ── Mode-specific panel ───────────────────────────────────────────────

    private fun setupModePanel() {
        when (viewModel.mode) {
            "local" -> {
                binding.btnPickFile.isVisible = true
                binding.btnPickFile.text = "Open video file"
                binding.btnPickFile.setOnClickListener {
                    pickVideoLauncher.launch("video/*")
                }
                binding.btnScreenShare.isVisible = false
            }
            "upload" -> {
                binding.btnPickFile.isVisible = viewModel.isHost
                binding.btnPickFile.text = "Upload video"
                binding.btnPickFile.setOnClickListener {
                    pickVideoLauncher.launch("video/*")
                }
                binding.btnScreenShare.isVisible = false
                binding.uploadProgress.isVisible = false
            }
            "screenshare" -> {
                binding.btnPickFile.isVisible = false
                binding.btnScreenShare.isVisible = viewModel.isHost
                binding.btnScreenShare.setOnClickListener { startScreenShare() }
            }
        }
    }

    // ── Host controls ─────────────────────────────────────────────────────

    private fun setupHostControls() {
        binding.layoutHostControls.isVisible = viewModel.isHost
        binding.btnLeave.setOnClickListener { viewModel.leaveRoom() }
        binding.btnRequestSync.setOnClickListener { viewModel.requestSync() }
    }

    // ── Data meter display ────────────────────────────────────────────────

    private fun setupDataMeter() {
        collectFlow(com.syncwatch.util.DataMeter.bytesIn) { bytes ->
            binding.tvDataIn.text = "↓ ${bytes.toReadable()}"
        }
        collectFlow(com.syncwatch.util.DataMeter.bytesOut) { bytes ->
            binding.tvDataOut.text = "↑ ${bytes.toReadable()}"
        }
    }

    private fun Long.toReadable(): String = when {
        this < 1_048_576 -> "${"%.1f".format(this / 1024.0)} KB"
        else             -> "${"%.1f".format(this / 1_048_576.0)} MB"
    }

    // ── Observe ViewModel ─────────────────────────────────────────────────

    private fun observeViewModel() {
        collectFlow(viewModel.users) { users ->
            userListAdapter.submitList(users.toList())
            binding.tvUserCount.text = "${users.size}"
        }

        collectFlow(viewModel.chat) { messages ->
            chatAdapter.submitList(messages.toList()) {
                binding.rvChat.scrollToPosition(messages.size - 1)
            }
        }

        collectFlow(viewModel.connected) { connected ->
            binding.tvConnectionStatus.text = if (connected) "●" else "○"
            binding.tvConnectionStatus.setTextColor(
                if (connected) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
            )
        }

        collectFlow(viewModel.mediaInfo) { info ->
            info?.url?.let { url ->
                if (viewModel.mode == "upload") playerController?.loadUrl(url)
            }
        }

        collectFlow(viewModel.toastEvent) { message ->
            toast(message)
        }

        collectFlow(viewModel.leaveEvent) {
            (requireActivity() as MainActivity).navigateHome()
        }

        // Playback events → PlayerController
        collectFlow(viewModel.playbackPlayEvent) { event ->
            playerController?.onRemotePlay(event.state.timestamp)
        }
        collectFlow(viewModel.playbackPauseEvent) { event ->
            playerController?.onRemotePause(event.state.timestamp)
        }
        collectFlow(viewModel.playbackSeekEvent) { event ->
            playerController?.onRemoteSeek(event.state.timestamp)
        }
        collectFlow(viewModel.playbackRateEvent) { payload ->
            playerController?.onRemoteRateChange(payload.rate)
        }
        collectFlow(viewModel.syncStateEvent) { state ->
            playerController?.applySync(state.timestamp, state.isPlaying, state.rate)
        }

        // Screen share guest: show renderer when ss_offer arrives
        if (viewModel.mode == "screenshare" && !viewModel.isHost) {
            collectFlow(com.syncwatch.network.SocketManager.ssOffer) {
                binding.screenShareRenderer.isVisible = true
                binding.playerView.isVisible = false
            }
            collectFlow(viewModel.ssStopEvent) {
                binding.screenShareRenderer.isVisible = false
                binding.playerView.isVisible = true
            }
        }
    }

    // ── Upload ────────────────────────────────────────────────────────────

    private fun uploadFile(uri: Uri) {
        val contentResolver = requireContext().contentResolver
        val tmpFile = File(requireContext().cacheDir, "upload_${System.currentTimeMillis()}.mp4")

        fragmentScope.launch {
            binding.uploadProgress.isVisible = true
            binding.btnPickFile.isEnabled = false

            try {
                // Copy to a temp file so Retrofit can read contentLength
                contentResolver.openInputStream(uri)?.use { input ->
                    tmpFile.outputStream().use { output -> input.copyTo(output) }
                }

                val roomIdPart = MultipartBody.Part.createFormData("roomId", viewModel.roomId)
                val filePart = MultipartBody.Part.createFormData(
                    "file", tmpFile.name,
                    tmpFile.asRequestBody("video/*".toMediaTypeOrNull())
                )

                val response = ApiClient.api.uploadVideo(roomIdPart, filePart)
                if (!response.isSuccessful) {
                    toast("Upload failed: ${response.code()}", long = true)
                }
                // Success: wait for media_ready socket event — handled in observeViewModel
            } catch (e: Exception) {
                toast("Upload error: ${e.message}", long = true)
            } finally {
                binding.uploadProgress.isVisible = false
                binding.btnPickFile.isEnabled = true
                tmpFile.delete()
            }
        }
    }

    // ── Screen share ──────────────────────────────────────────────────────

    private fun startScreenShare() {
        // 1. Start foreground service first (required Android 10+)
        ScreenShareService.start(requireContext())

        // 2. Ask for MediaProjection permission
        val mgr = requireContext().getSystemService(MediaProjectionManager::class.java)
        screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun initScreenShareHost(resultCode: Int, data: Intent) {
        screenShareHost = ScreenShareHost(
            context     = requireContext(),
            roomId      = viewModel.roomId,
            resultCode  = resultCode,
            resultData  = data
        )
        screenShareHost!!.start()
        binding.btnScreenShare.text = "Stop sharing"
        binding.btnScreenShare.setOnClickListener { stopScreenShare() }
    }

    private fun stopScreenShare() {
        screenShareHost?.stop()
        screenShareHost = null
        ScreenShareService.stop(requireContext())
        binding.btnScreenShare.text = "Share screen"
        binding.btnScreenShare.setOnClickListener { startScreenShare() }
    }

    private fun initScreenShareGuest() {
        screenShareGuest = ScreenShareGuest(
            context    = requireContext(),
            roomId     = viewModel.roomId,
            renderer   = binding.screenShareRenderer
        )
        screenShareGuest!!.start()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        playerController?.player?.play()
    }

    override fun onPause() {
        super.onPause()
        playerController?.player?.pause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        playerController?.release()
        playerController = null
        screenShareHost?.stop()
        screenShareGuest?.stop()
        fragmentScope.cancel()
        _binding = null
    }

    companion object {
        private const val ARG_PAYLOAD    = "payload"
        private const val ARG_HOST_TOKEN = "hostToken"

        fun newInstance(payload: RoomJoinedPayload, hostToken: String?): WatchFragment {
            return WatchFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_PAYLOAD, payload as java.io.Serializable)
                    putString(ARG_HOST_TOKEN, hostToken)
                }
            }
        }
    }

    // Make ViewModel's viewModelScope accessible to PlayerController constructor
    private val viewModelScope get() = viewModel.let {
        androidx.lifecycle.ViewModelProvider(this)[WatchViewModel::class.java]
        kotlinx.coroutines.MainScope()
    }
}
