package com.syncwatch.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.syncwatch.MainActivity
import com.syncwatch.databinding.FragmentHomeBinding
import com.syncwatch.model.RoomJoinedPayload
import com.syncwatch.ui.watch.WatchFragment
import com.syncwatch.util.collectFlow
import com.syncwatch.util.toast

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupTabSwitcher()
        setupCreateButton()
        setupJoinButton()
        observeViewModel()
    }

    private fun setupTabSwitcher() {
        binding.tabCreate.setOnClickListener { showCreateTab() }
        binding.tabJoin.setOnClickListener { showJoinTab() }
        showCreateTab() // default
    }

    private fun showCreateTab() {
        binding.layoutCreate.isVisible = true
        binding.layoutJoin.isVisible = false
        binding.tabCreate.isSelected = true
        binding.tabJoin.isSelected = false
    }

    private fun showJoinTab() {
        binding.layoutCreate.isVisible = false
        binding.layoutJoin.isVisible = true
        binding.tabCreate.isSelected = false
        binding.tabJoin.isSelected = true
    }

    private fun setupCreateButton() {
        binding.btnCreate.setOnClickListener {
            val nickname = binding.etCreateNickname.text.toString()
            val password = binding.etCreatePassword.text.toString()
            val mode = when (binding.rgMode.checkedRadioButtonId) {
                binding.rbUpload.id      -> "upload"
                binding.rbScreenshare.id -> "screenshare"
                else                     -> "local"
            }
            viewModel.createRoom(nickname, password, mode)
        }
    }

    private fun setupJoinButton() {
        binding.btnJoin.setOnClickListener {
            val code     = binding.etJoinCode.text.toString()
            val nickname = binding.etJoinNickname.text.toString()
            val password = binding.etJoinPassword.text.toString()
            viewModel.joinRoom(code, nickname, password)
        }
    }

    private fun observeViewModel() {
        collectFlow(viewModel.uiState) { state ->
            when (state) {
                is HomeUiState.Idle -> {
                    binding.progressBar.isVisible = false
                    setInputsEnabled(true)
                }
                is HomeUiState.Loading -> {
                    binding.progressBar.isVisible = true
                    setInputsEnabled(false)
                }
                is HomeUiState.NeedsPassword -> {
                    binding.progressBar.isVisible = false
                    setInputsEnabled(true)
                    binding.tilJoinPassword.isVisible = true
                    toast("This room requires a password")
                }
                is HomeUiState.Error -> {
                    binding.progressBar.isVisible = false
                    setInputsEnabled(true)
                    toast(state.message, long = true)
                }
            }
        }

        collectFlow(viewModel.navigateToWatch) { payload ->
            navigateToWatch(payload)
        }
    }

    private fun setInputsEnabled(enabled: Boolean) {
        binding.btnCreate.isEnabled = enabled
        binding.btnJoin.isEnabled = enabled
        binding.etCreateNickname.isEnabled = enabled
        binding.etJoinNickname.isEnabled = enabled
        binding.etJoinCode.isEnabled = enabled
    }

    private fun navigateToWatch(payload: RoomJoinedPayload) {
        val fragment = WatchFragment.newInstance(payload, viewModel.pendingHostToken)
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(com.syncwatch.R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
