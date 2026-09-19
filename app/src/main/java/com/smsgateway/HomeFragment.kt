package com.smsgateway

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
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
    private lateinit var etSearch: EditText
    private lateinit var btnSearchClear: ImageView
    private lateinit var rvLogs: RecyclerView
    private lateinit var logAdapter: LogAdapter

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

    private var currentFilter: String = ""
    private var allLogs: List<String> = emptyList()

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
        // legacy may be gone
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
        etSearch = view.findViewById(R.id.etSearch)
        btnSearchClear = view.findViewById(R.id.btnSearchClear)
        rvLogs = view.findViewById(R.id.rvLogs)

        logAdapter = LogAdapter()
        rvLogs.layoutManager = LinearLayoutManager(requireContext())
        rvLogs.adapter = logAdapter
        allLogs = LogStore.getAll()
        LogStore.addListener {
            allLogs = LogStore.getAll()
            activity?.runOnUiThread { applyLogFilter() }
        }
        applyLogFilter()

        view.findViewById<View>(R.id.btnToggleService)?.setOnClickListener { toggleService() }
        view.findViewById<View>(R.id.btnBattery)?.setOnClickListener { (activity as? MainActivity)?.requestBatteryExemption() }
        view.findViewById<View>(R.id.btnTestSend)?.setOnClickListener { doTestSend(view) }

        // Search filter — monochrome: filter logs only
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentFilter = s?.toString()?.trim()?.lowercase() ?: ""
                btnSearchClear.visibility = if (currentFilter.isEmpty()) View.GONE else View.VISIBLE
                applyLogFilter()
            }
        })
        btnSearchClear.setOnClickListener { etSearch.text.clear() }

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
        // service status hidden now — keep for compat if exists
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
        // Gateways card shows active count numeric; sub stays "active"
        tvGatewaysCount.text = enabled.size.toString()
        tvGatewayCountActive?.text = enabled.size.toString()
        view?.findViewById<TextView>(R.id.tvGatewaysSub)?.let {
            it.text = "active"
        }
        tvActivitySub?.text = "Failed: ${prefs.failedCount} • Sent: ${prefs.sentCount}"
        tvPollInfo?.text = "poll 15s"
    }

    private fun applyLogFilter() {
        val filtered = if (currentFilter.isEmpty()) allLogs else allLogs.filter { it.lowercase().contains(currentFilter) }
        logAdapter.update(filtered)
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
        // hidden in new UI but keep for compat if invoked via hidden button
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
            Toast.makeText(requireContext(), getString(R.string.msg_grant_sms_first), Toast.LENGTH_SHORT).show()
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

    inner class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {
        private var items: List<String> = emptyList()
        fun update(newItems: List<String>) { items = newItems; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
            return VH(v)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.tv.text = items[position]
            // monochrome — white card, black dot 6dp, no pastel
            holder.dot.setBackgroundResource(R.drawable.bg_timeline_dot_mono)
            holder.card.setCardBackgroundColor(ContextCompat.getColor(holder.itemView.context, R.color.white))
            // subtle divider via stroke already
        }
        override fun getItemCount(): Int = items.size
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tv: TextView = view.findViewById(R.id.tvLog)
            val dot: View = view.findViewById(R.id.dotTimeline)
            val card: MaterialCardView = view.findViewById(R.id.cardLog)
        }
    }
}
