package com.smsgateway

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class HomeFragment : Fragment() {

    private lateinit var prefs: Prefs
    private lateinit var tvPending: TextView
    private lateinit var tvSent: TextView
    private lateinit var tvFailed: TextView
    private lateinit var tvGatewaysCount: TextView
    private lateinit var tvGreeting: TextView

    // legacy hidden refs kept for compat
    private var tvServiceStatus: TextView? = null
    private var tvLastPoll: TextView? = null
    private var tvWelcomeSub: TextView? = null
    private var tvGatewayCountActive: TextView? = null
    private var tvActivitySub: TextView? = null
    private var tvPollInfo: TextView? = null
    private var chipRow: LinearLayout? = null
    private var spinnerGateways: Spinner? = null
    private var etTestPhone: com.google.android.material.textfield.TextInputEditText? = null
    private var etTestMessage: com.google.android.material.textfield.TextInputEditText? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStats()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = Prefs.getInstance(requireContext())
        tvPending = view.findViewById(R.id.tvPending)
        tvSent = view.findViewById(R.id.tvSent)
        tvFailed = view.findViewById(R.id.tvFailed)
        tvGatewaysCount = view.findViewById(R.id.tvGatewaysCount)
        tvGreeting = view.findViewById(R.id.tvGreeting)

        // legacy views
        tvServiceStatus = view.findViewById(R.id.tvServiceStatus)
        tvLastPoll = view.findViewById(R.id.tvLastPoll)
        tvWelcomeSub = view.findViewById(R.id.tvWelcomeSub)
        tvGatewayCountActive = view.findViewById(R.id.tvGatewayCountActive)
        tvActivitySub = view.findViewById(R.id.tvActivitySub)
        tvPollInfo = view.findViewById(R.id.tvPollInfo)
        chipRow = view.findViewById(R.id.chipRow)
        spinnerGateways = view.findViewById(R.id.spinnerGateways)
        etTestPhone = view.findViewById(R.id.etTestPhone)
        etTestMessage = view.findViewById(R.id.etTestMessage)

        view.findViewById<View>(R.id.btnToggleService)?.setOnClickListener { toggleService() }
        view.findViewById<View>(R.id.btnBattery)?.setOnClickListener { (activity as? MainActivity)?.requestBatteryExemption() }
        view.findViewById<View>(R.id.btnTestSend)?.setOnClickListener { doTestSend(view) }

        updateGreeting()
        refreshStats()
        setupSpinner()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
        refreshStats()
        setupSpinner()
        updateGreeting()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun updateGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val suffix = when (hour) {
            in 0..11 -> "Good Morning!"
            in 12..16 -> "Good Afternoon!"
            in 17..20 -> "Good Evening!"
            else -> "Good Night!"
        }
        tvGreeting.text = "Hello, $suffix"
    }

    private fun refreshStats() {
        val running = SmsForegroundService.isRunning
        tvServiceStatus?.let {
            it.text = if (running) getString(R.string.status_service_running) else getString(R.string.status_service_stopped)
        }
        tvLastPoll?.text = "Last: ${prefs.lastPoll ?: "—"}"
        tvPending.text = "—"
        tvSent.text = prefs.sentCount.toString()
        tvFailed.text = prefs.failedCount.toString()

        val backends = prefs.getBackends()
        val enabled = backends.filter { it.enabled }
        tvWelcomeSub?.text = "${backends.size} gateways • ${enabled.size} active • polling 15s"
        tvGatewaysCount.text = enabled.size.toString()
        tvGatewayCountActive?.text = enabled.size.toString()
        view?.findViewById<TextView>(R.id.tvGatewaysSub)?.let {
            it.text = "active"
        }
        tvActivitySub?.text = "Failed: ${prefs.failedCount} • Sent: ${prefs.sentCount}"
        tvPollInfo?.text = "poll 15s"
    }

    private fun setupSpinner() {
        val spinner = spinnerGateways ?: return
        val backends = prefs.getBackends()
        val names = if (backends.isEmpty()) listOf("No gateways — add one") else backends.map { "${it.name} (${if (it.enabled) "on" else "off"})" }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, names)
        spinner.adapter = adapter
    }

    private fun toggleService() {
        val activity = activity as? MainActivity ?: return
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(requireContext(), getString(R.string.msg_grant_sms_first), Toast.LENGTH_LONG).show()
            activity.checkPermissions()
            return
        }
        if (!prefs.isConfigured()) {
            Toast.makeText(requireContext(), "Add & enable a gateway first", Toast.LENGTH_SHORT).show()
            activity.navigateToGateways()
            return
        }
        if (SmsForegroundService.isRunning) {
            SmsForegroundService.stop(requireContext())
            LogStore.add("Service stopped by user")
            Toast.makeText(requireContext(), "Service stopping…", Toast.LENGTH_SHORT).show()
        } else {
            SmsForegroundService.start(requireContext())
            LogStore.add("Service started")
            Toast.makeText(requireContext(), "Service starting…", Toast.LENGTH_SHORT).show()
        }
        handler.postDelayed({ refreshStats() }, 800)
    }

    private fun doTestSend(root: View) {
        val spinner = spinnerGateways
        val phoneView = etTestPhone
        val msgView = etTestMessage
        if (spinner == null || phoneView == null || msgView == null) return
        if (!prefs.isConfigured() && prefs.getBackends().isEmpty()) {
            Snackbar.make(root, "Add a gateway first", Snackbar.LENGTH_LONG).show()
            (activity as? MainActivity)?.navigateToGateways()
            return
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(requireContext(), getString(R.string.msg_grant_sms_first), Toast.LENGTH_LONG).show()
            (activity as? MainActivity)?.checkPermissions()
            return
        }
        val backends = prefs.getBackends()
        if (backends.isEmpty()) {
            Snackbar.make(root, "No gateways configured", Snackbar.LENGTH_LONG).show()
            return
        }
        val idx = spinner.selectedItemPosition.coerceIn(0, backends.size - 1)
        val config = backends[idx]
        if (!config.enabled) {
            Snackbar.make(root, "${config.name} is disabled — enable first", Snackbar.LENGTH_LONG).show()
            return
        }
        val phone = phoneView.text?.toString()?.trim() ?: ""
        val msg = msgView.text?.toString()?.trim() ?: ""
        if (phone.length < 10 || phone.length > 15 || !phone.matches(Regex("^[0-9]{10,15}$"))) {
            phoneView.error = "Enter 10-15 digits"
            return
        }
        if (msg.isBlank()) {
            msgView.error = "Enter message"
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val api = ApiClient.forConfig(config)
                val resp = api.sendSms(SendRequest(to = phone, message = msg))
                LogStore.add("Test enqueued via ${config.name} id=${resp.id} to $phone")
                withContext(Dispatchers.Main) {
                    Snackbar.make(root, "Enqueued via ${config.name}: ${resp.id}", Snackbar.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                LogStore.add("Test send failed ${config.name}: ${e.message}")
                withContext(Dispatchers.Main) {
                    Snackbar.make(root, "Test failed ${config.name}: ${e.message}", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }
}
