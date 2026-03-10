package com.nexusbridge.smsbridge

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.nexusbridge.smsbridge.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val args: HomeFragmentArgs by navArgs()

    private var bridgeService: BridgeService? = null
    private var serviceBound = false

    // Receive status updates from service
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BridgeService.ACTION_STATUS_UPDATE -> {
                    val connected = intent.getBooleanExtra(BridgeService.EXTRA_CONNECTED, false)
                    val pin       = intent.getStringExtra(BridgeService.EXTRA_PIN) ?: ""
                    updateStatusUI(connected, pin)
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? BridgeService.LocalBinder
            bridgeService = binder?.getService()
            serviceBound = true
            refreshStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bridgeService = null
            serviceBound = false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Display session info
        binding.tvSessionToken.text = "Session: ${args.sessionToken.take(12)}..."
        binding.tvServerAddress.text = "Server: your.domain.com"

        setupButtons()

        // Register status receiver
        val filter = IntentFilter(BridgeService.ACTION_STATUS_UPDATE)
        ContextCompat.registerReceiver(
            requireContext(), statusReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Bind to service
        val intent = Intent(requireContext(), BridgeService::class.java)
        requireContext().bindService(intent, serviceConnection, 0)

        // Initial focus
        view.post { binding.btnDisconnect.requestFocus() }
    }

    private fun setupButtons() {
        binding.btnDisconnect.setOnClickListener { disconnect() }
        binding.btnDisconnect.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                disconnect(); true
            } else false
        }

        binding.btnReconnect.setOnClickListener { reconnect() }
        binding.btnReconnect.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                reconnect(); true
            } else false
        }

        binding.btnRefresh.setOnClickListener { refreshMessages() }
        binding.btnRefresh.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                refreshMessages(); true
            } else false
        }
    }

    private fun refreshStatus() {
        val service = bridgeService ?: return
        updateStatusUI(service.isConnected, service.currentPin)
    }

    private fun updateStatusUI(connected: Boolean, pin: String) {
        if (_binding == null) return

        binding.tvConnectionStatus.text = if (connected) "Connected" else "Disconnected"
        binding.tvConnectionStatus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (connected) R.color.green_connected else R.color.red_disconnected
            )
        )
        binding.statusIndicator.setBackgroundResource(
            if (connected) R.drawable.bg_status_connected else R.drawable.bg_status_disconnected
        )
        binding.tvPin.text = if (pin.isNotBlank()) pin else "------"
        binding.btnReconnect.visibility = if (connected) View.GONE else View.VISIBLE
    }

    private fun disconnect() {
        bridgeService?.disconnect()

        // Stop the service entirely
        val intent = Intent(requireContext(), BridgeService::class.java)
        requireContext().stopService(intent)

        // Navigate back to pairing
        findNavController().navigate(R.id.action_home_to_pairing)
    }

    private fun reconnect() {
        bridgeService?.reconnectNow()
    }

    private fun refreshMessages() {
        bridgeService?.refreshConversations()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (serviceBound) {
            requireContext().unbindService(serviceConnection)
            serviceBound = false
        }
        try {
            requireContext().unregisterReceiver(statusReceiver)
        } catch (_: IllegalArgumentException) {}
        _binding = null
    }
}
