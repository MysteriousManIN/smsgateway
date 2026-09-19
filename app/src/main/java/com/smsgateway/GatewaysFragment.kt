package com.smsgateway

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GatewaysFragment : Fragment() {

    private lateinit var prefs: Prefs
    private lateinit var rv: RecyclerView
    private var tvCount: TextView? = null
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: GatewayAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_gateways, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = Prefs.getInstance(requireContext())
        rv = view.findViewById(R.id.rvGateways)
        // ponytail: tvGatewayCount chip removed — keep nullable, no lookup to avoid R id missing
        tvCount = null
        tvEmpty = view.findViewById(R.id.tvEmpty)

        adapter = GatewayAdapter(
            items = prefs.getBackends(),
            onToggle = { cfg, enabled ->
                prefs.updateBackend(cfg.copy(enabled = enabled))
                LogStore.add("${cfg.name} ${if (enabled) "enabled" else "disabled"}")
                refresh()
            },
            onTest = { cfg -> testGateway(cfg, view) },
            onEdit = { cfg -> showEditDialog(cfg) },
            onDelete = { cfg -> confirmDelete(cfg) },
            onClearLogs = { cfg -> clearLogsForGateway(cfg, view) }
        )
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        view.findViewById<View>(R.id.fabAdd).setOnClickListener { showAddDialog() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    fun refresh() {
        val list = prefs.getBackends()
        adapter.update(list)
        tvCount?.text = "${list.size} gateways • ${list.count { it.enabled }} active"
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        rv.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun clearLogsForGateway(cfg: BackendConfig, root: View) {
        // ponytail: remove logs containing gateway name — minimal per-connection clear
        val before = LogStore.getAll().size
        LogStore.removeLogsContaining(cfg.name)
        // also remove logs containing its baseUrl host
        try {
            val host = cfg.baseUrl.substringAfter("https://").substringBefore("/")
            if (host.isNotBlank()) LogStore.removeLogsContaining(host)
        } catch (_: Exception) {}
        val after = LogStore.getAll().size
        val removed = before - after
        LogStore.add("Cleared $removed logs for ${cfg.name}")
        // remove the just added log if we want only that gateway's? Keep it.
        Snackbar.make(root, "Cleared $removed logs for ${cfg.name}", Snackbar.LENGTH_SHORT).show()
    }

    private fun showAddDialog() {
        showGatewayDialog(null)
    }

    private fun showEditDialog(cfg: BackendConfig) {
        showGatewayDialog(cfg)
    }

    private fun showGatewayDialog(existing: BackendConfig?) {
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_gateway, null)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etName)
        val etUrl = dialogView.findViewById<TextInputEditText>(R.id.etUrl)
        val etToken = dialogView.findViewById<TextInputEditText>(R.id.etToken)
        val switchEnabled = dialogView.findViewById<SwitchMaterial>(R.id.switchEnabled)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        val btnTest = dialogView.findViewById<MaterialButton>(R.id.btnTest)

        if (existing != null) {
            tvTitle.text = "Edit Gateway"
            etName.setText(existing.name)
            etUrl.setText(existing.baseUrl)
            etToken.setText(existing.token)
            switchEnabled.isChecked = existing.enabled
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        btnTest.setOnClickListener {
            val url = etUrl.text?.toString()?.trim() ?: ""
            val token = etToken.text?.toString()?.trim() ?: ""
            val name = etName.text?.toString()?.trim() ?: "Test"
            if (!url.startsWith("https://")) {
                etUrl.error = "Must start with https://"
                return@setOnClickListener
            }
            if (token.isBlank()) {
                etToken.error = "Token required"
                return@setOnClickListener
            }
            btnTest.isEnabled = false
            btnTest.text = "Testing..."
            val testCfg = BackendConfig(
                id = existing?.id ?: "test-temp",
                name = name.ifBlank { "Test" },
                baseUrl = url,
                token = token,
                enabled = true
            )
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val api = ApiClient.forConfig(testCfg)
                    val pending = api.getPending(limit = 1)
                    withContext(Dispatchers.Main) {
                        btnTest.isEnabled = true
                        btnTest.text = "Test Connection"
                        Snackbar.make(dialogView, "✓ ${testCfg.name} OK — pending: ${pending.messages.size}", Snackbar.LENGTH_LONG).show()
                        if (existing != null) {
                            prefs.setBackendStatus(existing.id, "ok")
                            refresh()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        btnTest.isEnabled = true
                        btnTest.text = "Test Connection"
                        Snackbar.make(dialogView, "✗ Failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                        if (existing != null) {
                            prefs.setBackendStatus(existing.id, "fail")
                            refresh()
                        }
                    }
                }
            }
        }

        dialogView.findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            val name = etName.text?.toString()?.trim() ?: ""
            val url = etUrl.text?.toString()?.trim() ?: ""
            val token = etToken.text?.toString()?.trim() ?: ""
            if (name.isBlank()) { etName.error = "Required"; return@setOnClickListener }
            if (!url.startsWith("https://")) { etUrl.error = "Must start with https://"; Toast.makeText(requireContext(), getString(R.string.msg_need_https), Toast.LENGTH_LONG).show(); return@setOnClickListener }
            if (token.isBlank()) { etToken.error = "Required"; return@setOnClickListener }
            try {
                if (existing != null) {
                    val updated = existing.copy(name = name, baseUrl = url.trim().trimEnd('/'), token = token, enabled = switchEnabled.isChecked)
                    prefs.updateBackend(updated)
                    LogStore.add("Updated gateway: $name")
                } else {
                    val cfg = BackendConfig(name = name, baseUrl = url.trim().trimEnd('/'), token = token, enabled = switchEnabled.isChecked)
                    prefs.addBackend(cfg)
                    LogStore.add("Added gateway: $name")
                }
                dialog.dismiss()
                refresh()
            } catch (e: IllegalArgumentException) {
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
            }
        }

        dialog.show()
    }

    private fun confirmDelete(cfg: BackendConfig) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete ${cfg.name}?")
            .setMessage("Remove gateway ${cfg.baseUrl}? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                prefs.deleteBackend(cfg.id)
                LogStore.add("Deleted gateway: ${cfg.name}")
                refresh()
                Snackbar.make(requireView(), "Deleted ${cfg.name}", Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun testGateway(cfg: BackendConfig, root: View) {
        Snackbar.make(root, "Testing ${cfg.name}…", Snackbar.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val api = ApiClient.forConfig(cfg)
                val pending = api.getPending(limit = 1)
                prefs.setBackendStatus(cfg.id, "ok")
                withContext(Dispatchers.Main) {
                    Snackbar.make(root, "✓ ${cfg.name} OK — pending: ${pending.messages.size}", Snackbar.LENGTH_LONG).show()
                    refresh()
                }
            } catch (e: Exception) {
                prefs.setBackendStatus(cfg.id, "fail")
                withContext(Dispatchers.Main) {
                    Snackbar.make(root, "✗ ${cfg.name} failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                    refresh()
                }
            }
        }
    }
}
