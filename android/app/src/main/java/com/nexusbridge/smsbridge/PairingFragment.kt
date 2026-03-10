package com.nexusbridge.smsbridge

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.zxing.integration.android.IntentIntegrator
import com.nexusbridge.smsbridge.databinding.FragmentPairingBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class PairingFragment : Fragment() {

    private var _binding: FragmentPairingBinding? = null
    private val binding get() = _binding!!

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var httpClient: OkHttpClient

    companion object {
        private const val SERVER_BASE = "https://your.domain.com"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPairingBinding.inflate(inflater, container, false)
        httpClient = TlsHelper.buildClient(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupPinInput()
        setupActionButtons()
    }

    // ── PIN text input ───────────────────────────────────────────────────────

    private fun setupPinInput() {
        // Auto-submit when 6 digits are typed
        binding.etPin.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if ((s?.length ?: 0) == 6) submitPin()
            }
        })
        // Trigger submit on keyboard "Done" action
        binding.etPin.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                submitPin()
                true
            } else false
        }
        // Auto-focus so keyboard opens immediately
        view?.post { binding.etPin.requestFocus() }
    }

    private fun setupActionButtons() {
        binding.btnConnect.setOnClickListener { submitPin() }
        binding.btnScanQr.setOnClickListener { launchQrScanner() }
    }

    // ── Pairing via PIN ────────────────────────────────────────────────────────

    private fun submitPin() {
        val pin = binding.etPin.text?.toString()?.trim() ?: ""
        if (pin.length != 6 || !pin.all { it.isDigit() }) {
            binding.tilPin.error = "Enter all 6 digits"
            return
        }
        binding.tilPin.error = null
        setLoading(true)
        val wsUrl = buildWsUrl(pin)
        connectAndNavigate(wsUrl, pin)
    }

    // ── Pairing via QR ────────────────────────────────────────────────────────

    private fun launchQrScanner() {
        val integrator = IntentIntegrator.forSupportFragment(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("Scan NexusBridge QR code")
        integrator.setBeepEnabled(true)
        integrator.setOrientationLocked(false)
        integrator.initiateScan()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents == null) {
                Toast.makeText(requireContext(), "QR scan cancelled", Toast.LENGTH_SHORT).show()
            } else {
                handleQrContent(result.contents)
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun handleQrContent(content: String) {
        // Expected: nexusbridge://pair?token=TOKEN&server=SERVER
        if (!content.startsWith("nexusbridge://pair")) {
            Toast.makeText(requireContext(), "Invalid QR code", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = android.net.Uri.parse(content)
        val token = uri.getQueryParameter("token")
        if (token.isNullOrBlank()) {
            Toast.makeText(requireContext(), "QR code missing token", Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true)
        val wsUrl = buildWsUrlFromToken(token)
        connectAndNavigate(wsUrl, token, isToken = true)
    }

    // ── WebSocket connection & navigation ─────────────────────────────────────

    private fun buildWsUrl(pin: String): String {
        // Phone connects via PIN: server maps pin → token
        return "wss://your.domain.com/ws/$pin?role=phone"
    }

    private fun buildWsUrlFromToken(token: String): String {
        return "wss://your.domain.com/ws/$token?role=phone"
    }

    /**
     * Attempt a quick HTTP validation, then start BridgeService and navigate to HomeFragment.
     */
    private fun connectAndNavigate(wsUrl: String, identifier: String, isToken: Boolean = false) {
        scope.launch {
            try {
                // Optionally validate identifier via HTTP first
                val sessionToken = if (isToken) {
                    identifier
                } else {
                    // Resolve PIN to session token via HTTP
                    resolvePin(identifier) ?: run {
                        withContext(Dispatchers.Main) {
                            setLoading(false)
                            Toast.makeText(requireContext(), "Invalid PIN. Try again.", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                }

                withContext(Dispatchers.Main) {
                    setLoading(false)
                    // Start foreground service with the resolved token
                    val serviceIntent = Intent(requireContext(), BridgeService::class.java).apply {
                        putExtra(BridgeService.EXTRA_SESSION_TOKEN, sessionToken)
                    }
                    requireContext().startForegroundService(serviceIntent)

                    // Navigate to HomeFragment
                    val action = PairingFragmentDirections.actionPairingToHome(sessionToken)
                    findNavController().navigate(action)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    Toast.makeText(requireContext(), "Connection failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Try to resolve a 6-digit PIN to a session token via the HTTP API.
     * The server status endpoint doesn't expose token by PIN, so we connect WebSocket
     * directly — the server handles PIN→token internally.
     * This function just verifies the server is reachable and returns the pin itself
     * (server accepts pin as path segment).
     */
    private suspend fun resolvePin(pin: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$SERVER_BASE/health")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) pin else null
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnConnect.isEnabled = !loading
        binding.btnScanQr.isEnabled = !loading
        binding.etPin.isEnabled = !loading
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
        _binding = null
    }
}
