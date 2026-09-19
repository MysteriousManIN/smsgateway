package com.smsgateway

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var tvOffline: TextView
    private lateinit var drawerLayout: DrawerLayout
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val homeFragment = HomeFragment()
    private val gatewaysFragment = GatewaysFragment()
    private val settingsFragment = SettingsFragment()
    private var currentTab = 0

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val smsGranted = grants[Manifest.permission.SEND_SMS] == true
        if (smsGranted) LogStore.add("SMS permission granted") else {
            LogStore.add("SMS permission denied")
            Toast.makeText(this, "SMS permission denied — cannot send", Toast.LENGTH_LONG).show()
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val notifGranted = grants[Manifest.permission.POST_NOTIFICATIONS] == true
            if (!notifGranted) LogStore.add("Notification permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val mode = when (Prefs.getThemeMode(this)) {
            Prefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            Prefs.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs.getInstance(this)
        tvOffline = findViewById(R.id.tvOffline)
        drawerLayout = findViewById(R.id.drawerLayout)

        // drawer clicks
        findViewById<View>(R.id.drawerItemHome).setOnClickListener { switchTab(0); closeDrawer() }
        findViewById<View>(R.id.drawerItemGateway).setOnClickListener { switchTab(1); closeDrawer() }
        findViewById<View>(R.id.drawerItemSettings).setOnClickListener { switchTab(2); closeDrawer() }
        findViewById<View>(R.id.btnHamburger).setOnClickListener { openDrawer() }

        // back press handles drawer
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    closeDrawer()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (savedInstanceState == null) {
            switchTab(0)
        }

        checkPermissions()
        observeNetwork()
        maybeLogBatteryHint()
        LogStore.add("App started — v1.0.0 multi-backend")
    }

    private fun openDrawer() {
        drawerLayout.openDrawer(GravityCompat.START)
    }

    private fun closeDrawer() {
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    fun switchTab(index: Int) {
        currentTab = index
        val fragment: Fragment = when (index) {
            0 -> homeFragment
            1 -> gatewaysFragment
            2 -> settingsFragment
            else -> homeFragment
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()

        updateDrawerSelection(index)
    }

    private fun updateDrawerSelection(index: Int) {
        val homeItem = findViewById<View>(R.id.drawerItemHome)
        val gwItem = findViewById<View>(R.id.drawerItemGateway)
        val setItem = findViewById<View>(R.id.drawerItemSettings)
        val homeIcon = findViewById<ImageView>(R.id.drawerIconHome)
        val gwIcon = findViewById<ImageView>(R.id.drawerIconGateway)
        val setIcon = findViewById<ImageView>(R.id.drawerIconSettings)
        val homeLabel = findViewById<TextView>(R.id.drawerLabelHome)
        val gwLabel = findViewById<TextView>(R.id.drawerLabelGateway)
        val setLabel = findViewById<TextView>(R.id.drawerLabelSettings)

        fun style(item: View, icon: ImageView, label: TextView, selected: Boolean) {
            if (selected) {
                item.setBackgroundResource(R.drawable.bg_drawer_selected_6)
                icon.setColorFilter(ContextCompat.getColor(this, R.color.text_dark))
                label.setTextColor(ContextCompat.getColor(this, R.color.text_dark))
                label.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                item.setBackgroundResource(0)
                item.setBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent))
                icon.setColorFilter(ContextCompat.getColor(this, R.color.text_muted))
                label.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                label.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
        style(homeItem, homeIcon, homeLabel, index == 0)
        style(gwItem, gwIcon, gwLabel, index == 1)
        style(setItem, setIcon, setLabel, index == 2)
    }

    fun navigateToGateways() = switchTab(1)

    fun checkPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.isEmpty()) return
        val showRationale = needed.any { shouldShowRequestPermissionRationale(it) }
        if (showRationale) {
            AlertDialog.Builder(this)
                .setTitle("Permissions needed")
                .setMessage(if (needed.contains(Manifest.permission.SEND_SMS)) getString(R.string.permission_sms_rationale) else getString(R.string.permission_notif_rationale))
                .setPositiveButton("Grant") { _, _ -> requestPermissionLauncher.launch(needed.toTypedArray()) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            requestPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already unrestricted", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.battery_title))
            .setMessage(getString(R.string.battery_msg))
            .setPositiveButton(getString(R.string.battery_allow)) { _, _ ->
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try { startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {
                        Toast.makeText(this, "Open Settings > Battery > Unrestricted", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.battery_skip), null)
            .show()
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val nw = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(nw) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun observeNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { runOnUiThread { tvOffline.visibility = View.GONE } }
            override fun onLost(network: Network) { runOnUiThread { tvOffline.visibility = View.VISIBLE } }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                runOnUiThread { tvOffline.visibility = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) View.GONE else View.VISIBLE }
            }
        }
        try { cm.registerNetworkCallback(req, networkCallback!!) } catch (_: Exception) {}
        tvOffline.visibility = if (isOnline()) View.GONE else View.VISIBLE
    }

    private fun maybeLogBatteryHint() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            LogStore.add("Tip: Disable battery optimization for reliable polling")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let {
            try { (getSystemService(ConnectivityManager::class.java)).unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
    }
}
