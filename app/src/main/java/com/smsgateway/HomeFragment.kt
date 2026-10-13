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

import android.widget.EditText
import android.widget.RadioButton
import androidx.appcompat.app.AlertDialog

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
        view.findViewById<View>(R.id.btnSendTest)?.setOnClickListener { showSendTestDialog() }
        tvPending.setOnClickListener {
            prefs.clearAcked()
            Toast.makeText(requireContext(), "Ack cache cleared — pending queue ready to dispatch", Toast.LENGTH_SHORT).show()
            LogStore.add("Ack cache cleared by user — pending messages ready")
            refreshStats()
        }

        refreshStats()
    }

    private fun showSendTestDialog() {
        val context = requireContext()
        val activity = activity as? MainActivity ?: return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, getString(R.string.msg_grant_sms_first), Toast.LENGTH_LONG).show()
            activity.checkPermissions()
            return
        }

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_send_test, null)
        val etPhone = dialogView.findViewById<EditText>(R.id.etTestPhone)
        val etMessage = dialogView.findViewById<EditText>(R.id.etTestMessage)
        val rbRcs = dialogView.findViewById<RadioButton>(R.id.rbChannelRcs)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnTestCancel)
        val btnSend = dialogView.findViewById<MaterialButton>(R.id.btnTestSend)

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSend.setOnClickListener {
            val phone = etPhone.text?.toString()?.trim() ?: ""
            val message = etMessage.text?.toString()?.trim() ?: ""
            val normalized = SmsValidator.normalizePhone(phone)

            if (normalized == null) {
                Toast.makeText(context, "Please enter a valid phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!SmsValidator.isValidMessage(message)) {
                Toast.makeText(context, "Message must be 1-1000 characters", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val channel = if (rbRcs.isChecked) "rcs" else "sms"
            val testMsg = SmsMessage(
                id = "test-" + System.currentTimeMillis(),
                to = normalized,
                message = message,
                channel = channel
            )
            LogStore.add("Manual test dispatch: ${testMsg.id} [$channel] -> $normalized")
            SmsSender.send(context, testMsg)
            dialog.dismiss()
            Toast.makeText(context, "Dispatch initiated [$channel] to $normalized", Toast.LENGTH_SHORT).show()
            handler.postDelayed({ refreshStats() }, 1000)
        }

        dialog.show()
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
        tvPending.text = if (running) prefs.pendingCount.toString() else "—"
        tvSent.text = prefs.sentCount.toString()
        tvFailed.text = prefs.failedCount.toString()

        val tvPollInfo = view?.findViewById<TextView>(R.id.tvPollInfo)
        tvPollInfo?.visibility = if (running) View.VISIBLE else View.GONE
        tvPollInfo?.text = if (running) "polling every 15s" else "stopped"

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
