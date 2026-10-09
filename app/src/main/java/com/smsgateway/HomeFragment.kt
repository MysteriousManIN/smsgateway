package com.smsgateway

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton

class HomeFragment : Fragment() {

    private lateinit var prefs: Prefs
    private lateinit var tvPending: TextView
    private lateinit var tvSent: TextView
    private lateinit var tvFailed: TextView
    private lateinit var tvGatewaysCount: TextView
    private lateinit var tvServiceStatus: TextView
    private lateinit var tvLastPoll: TextView
    private lateinit var btnToggleService: MaterialButton

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStats()
            handler.postDelayed(this, 5000)
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
        tvServiceStatus = view.findViewById(R.id.tvServiceStatus)
        tvLastPoll = view.findViewById(R.id.tvLastPoll)
        btnToggleService = view.findViewById(R.id.btnToggleService)

        btnToggleService.setOnClickListener { toggleService() }
        view.findViewById<View>(R.id.btnBattery)?.setOnClickListener { (activity as? MainActivity)?.requestBatteryExemption() }

        refreshStats()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
        refreshStats()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun refreshStats() {
        val running = SmsForegroundService.isRunning
        tvServiceStatus.text = if (running) getString(R.string.status_service_running) else getString(R.string.status_service_stopped)
        tvLastPoll.text = "Last: ${prefs.lastPoll ?: "—"}"
        btnToggleService.text = if (running) getString(R.string.btn_stop_service) else getString(R.string.btn_start_service)
        tvPending.text = "—"
        tvSent.text = prefs.sentCount.toString()
        tvFailed.text = prefs.failedCount.toString()

        val enabled = prefs.getEnabledBackends()
        tvGatewaysCount.text = enabled.size.toString()
        view?.findViewById<TextView>(R.id.tvGatewaysSub)?.let {
            it.text = "active"
        }
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
}
