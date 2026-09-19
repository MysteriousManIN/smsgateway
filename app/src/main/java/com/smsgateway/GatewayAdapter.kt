package com.smsgateway

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class GatewayAdapter(
    private var items: List<BackendConfig>,
    private val onToggle: (BackendConfig, Boolean) -> Unit,
    private val onTest: (BackendConfig) -> Unit,
    private val onEdit: (BackendConfig) -> Unit,
    private val onDelete: (BackendConfig) -> Unit,
    private val onClearLogs: (BackendConfig) -> Unit
) : RecyclerView.Adapter<GatewayAdapter.VH>() {

    fun update(newItems: List<BackendConfig>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_gateway, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        holder.tvName.text = c.name
        holder.tvUrl.text = c.baseUrl
        holder.tvToken.text = c.maskedToken()
        val statusText = when (c.lastStatus) {
            "ok" -> "online"
            "fail" -> "offline"
            else -> "idle"
        }
        holder.tvStatus.text = statusText
        holder.dot.setBackgroundResource(
            when (c.lastStatus) {
                "ok" -> R.drawable.bg_dot_online
                "fail" -> R.drawable.bg_dot_offline
                else -> R.drawable.bg_dot_offline
            }
        )
        if (c.lastStatus == null) {
            holder.dot.background.setTint(ContextCompat.getColor(holder.itemView.context, R.color.dot_idle))
        } else {
            holder.dot.background.clearColorFilter()
        }

        // mute left bar not used — keep hidden; handle alpha for status
        holder.leftBar.alpha = if (c.enabled) 1f else 0.3f

        holder.switchEnabled.setOnCheckedChangeListener(null)
        holder.switchEnabled.isChecked = c.enabled
        holder.switchEnabled.setOnCheckedChangeListener { _, checked ->
            onToggle(c, checked)
        }

        // ponytail: Test Send removed from card — only Connection remains
        holder.btnEdit.setOnClickListener { onEdit(c) }
        holder.btnDelete.setOnClickListener { onDelete(c) }
        holder.btnClearLogs.setOnClickListener { onClearLogs(c) }
    }

    override fun getItemCount(): Int = items.size

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvUrl: TextView = view.findViewById(R.id.tvUrl)
        val tvTokenMasked: TextView = view.findViewById(R.id.tvTokenMasked)
        val tvToken: TextView = tvTokenMasked
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        val dot: View = view.findViewById(R.id.dotStatus)
        val leftBar: View = view.findViewById(R.id.leftBar)
        val switchEnabled: SwitchMaterial = view.findViewById(R.id.switchEnabled)
        val btnEdit: MaterialButton = view.findViewById(R.id.btnEdit)
        val btnDelete: MaterialButton = view.findViewById(R.id.btnDelete)
        val btnClearLogs: MaterialButton = view.findViewById(R.id.btnClearLogs)
    }
}
