package com.smsgateway

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class LogsFragment : Fragment() {

    private lateinit var rvLogs: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var btnSearchClear: ImageView
    private lateinit var btnClearLogs: MaterialButton
    private lateinit var tvEmptyLogs: TextView
    private lateinit var tvLogsCount: TextView
    private lateinit var logAdapter: LogAdapter

    private var allLogs: List<String> = emptyList()
    private var currentFilter: String = ""

    private val logStoreListener: () -> Unit = {
        val updated = LogStore.getAll()
        activity?.runOnUiThread {
            allLogs = updated
            applyLogFilter()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_logs, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        rvLogs = view.findViewById(R.id.rvLogs)
        etSearch = view.findViewById(R.id.etSearch)
        btnSearchClear = view.findViewById(R.id.btnSearchClear)
        btnClearLogs = view.findViewById(R.id.btnClearLogs)
        tvEmptyLogs = view.findViewById(R.id.tvEmptyLogs)
        tvLogsCount = view.findViewById(R.id.tvLogsCount)

        logAdapter = LogAdapter()
        rvLogs.layoutManager = LinearLayoutManager(requireContext())
        rvLogs.adapter = logAdapter

        allLogs = LogStore.getAll()
        LogStore.addListener(logStoreListener)

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentFilter = s?.toString()?.trim()?.lowercase() ?: ""
                btnSearchClear.visibility = if (currentFilter.isEmpty()) View.GONE else View.VISIBLE
                applyLogFilter()
            }
        })

        btnSearchClear.setOnClickListener {
            etSearch.text.clear()
        }

        btnClearLogs.setOnClickListener {
            showClearLogsConfirmation()
        }

        applyLogFilter()
    }

    override fun onResume() {
        super.onResume()
        allLogs = LogStore.getAll()
        applyLogFilter()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        LogStore.removeListener(logStoreListener)
    }

    private fun applyLogFilter() {
        val filtered = if (currentFilter.isEmpty()) {
            allLogs
        } else {
            allLogs.filter { it.lowercase().contains(currentFilter) }
        }

        logAdapter.update(filtered)
        tvLogsCount.text = "${allLogs.size} ENTRIES • REAL-TIME"

        if (filtered.isEmpty()) {
            tvEmptyLogs.visibility = View.VISIBLE
            tvEmptyLogs.text = if (allLogs.isEmpty()) "No logs recorded yet." else "No logs match \"$currentFilter\""
            rvLogs.visibility = View.GONE
        } else {
            tvEmptyLogs.visibility = View.GONE
            rvLogs.visibility = View.VISIBLE
        }
    }

    private fun showClearLogsConfirmation() {
        if (allLogs.isEmpty()) {
            Toast.makeText(requireContext(), "No logs to clear", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Clear All Logs")
            .setMessage("Are you sure you want to clear all activity logs? This cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                LogStore.clear()
                Toast.makeText(requireContext(), "Logs cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {
        private var items: List<String> = emptyList()

        fun update(newItems: List<String>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.tv.text = items[position]
            holder.dot.setBackgroundResource(R.drawable.bg_timeline_dot_mono)
            holder.card.setCardBackgroundColor(ContextCompat.getColor(holder.itemView.context, R.color.white))
        }

        override fun getItemCount(): Int = items.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tv: TextView = view.findViewById(R.id.tvLog)
            val dot: View = view.findViewById(R.id.dotTimeline)
            val card: MaterialCardView = view.findViewById(R.id.cardLog)
        }
    }
}
