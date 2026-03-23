package com.guber.apkcloner.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.checkbox.MaterialCheckBox
import com.guber.apkcloner.R
import com.guber.apkcloner.databinding.ActivityMainBinding
import com.guber.apkcloner.engine.ApkInstaller
import com.guber.apkcloner.engine.CloneSettings
import com.guber.apkcloner.util.PackageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

	companion object {
		private const val REQUEST_STORAGE_PERMISSION = 100
		private const val REQUEST_INSTALL_PERMISSION = 101
	}

	private lateinit var binding: ActivityMainBinding
	private lateinit var adapter: AppListAdapter
	private var allApps: List<PackageUtils.AppInfo> = emptyList()
	private var pendingCloneSettings: CloneSettings? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		binding = ActivityMainBinding.inflate(layoutInflater)
		setContentView(binding.root)

		setSupportActionBar(binding.toolbar)
		supportActionBar?.title = "APK Cloner"

		adapter = AppListAdapter { appInfo ->
			onAppSelected(appInfo)
		}
		binding.recyclerView.layoutManager = LinearLayoutManager(this)
		binding.recyclerView.adapter = adapter

		checkPermissionsAndLoad()
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(R.menu.menu_main, menu)
		val searchItem = menu.findItem(R.id.action_search)
		val searchView = searchItem.actionView as SearchView
		searchView.queryHint = "Search apps..."
		searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
			override fun onQueryTextSubmit(query: String?) = false
			override fun onQueryTextChange(newText: String?): Boolean {
				filterApps(newText ?: "")
				return true
			}
		})
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.action_refresh -> {
				loadApps()
				true
			}
			else -> super.onOptionsItemSelected(item)
		}
	}

	private fun filterApps(query: String) {
		if (query.isEmpty()) {
			adapter.submitList(allApps)
		} else {
			val filtered = allApps.filter {
				it.label.contains(query, ignoreCase = true) ||
					it.packageName.contains(query, ignoreCase = true)
			}
			adapter.submitList(filtered)
		}
	}

	private fun checkPermissionsAndLoad() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
				!= PackageManager.PERMISSION_GRANTED
			) {
				ActivityCompat.requestPermissions(
					this,
					arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
					REQUEST_STORAGE_PERMISSION
				)
				return
			}
		}
		loadApps()
	}

	override fun onRequestPermissionsResult(
		requestCode: Int,
		permissions: Array<out String>,
		grantResults: IntArray
	) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults)
		if (requestCode == REQUEST_STORAGE_PERMISSION) {
			loadApps()
		}
	}

	private fun loadApps() {
		binding.progressBar.visibility = View.VISIBLE
		binding.recyclerView.visibility = View.GONE
		binding.emptyView.visibility = View.GONE

		lifecycleScope.launch(Dispatchers.IO) {
			val apps = PackageUtils.getInstalledApps(this@MainActivity)
			withContext(Dispatchers.Main) {
				allApps = apps
				adapter.submitList(apps)
				binding.progressBar.visibility = View.GONE
				if (apps.isEmpty()) {
					binding.emptyView.visibility = View.VISIBLE
				} else {
					binding.recyclerView.visibility = View.VISIBLE
				}
			}
		}
	}

	private fun onAppSelected(appInfo: PackageUtils.AppInfo) {
		val dialogView = layoutInflater.inflate(R.layout.dialog_clone_settings, null)
		val labelEditText = dialogView.findViewById<EditText>(R.id.labelEditText)
		val deepCloneCheckbox = dialogView.findViewById<MaterialCheckBox>(R.id.deepCloneCheckbox)
		val patchNativeCheckbox = dialogView.findViewById<MaterialCheckBox>(R.id.patchNativeCheckbox)
		val dualDexCheckbox = dialogView.findViewById<MaterialCheckBox>(R.id.dualDexCheckbox)
		val minSdkEditText = dialogView.findViewById<EditText>(R.id.minSdkEditText)
		val targetSdkEditText = dialogView.findViewById<EditText>(R.id.targetSdkEditText)

		val compatibilityHeader = dialogView.findViewById<LinearLayout>(R.id.compatibilityHeader)
		val compatibilityContent = dialogView.findViewById<LinearLayout>(R.id.compatibilityContent)
		val compatibilityChevron = dialogView.findViewById<TextView>(R.id.compatibilityChevron)
		val manifestHeader = dialogView.findViewById<LinearLayout>(R.id.manifestHeader)
		val manifestContent = dialogView.findViewById<LinearLayout>(R.id.manifestContent)
		val manifestChevron = dialogView.findViewById<TextView>(R.id.manifestChevron)

		compatibilityHeader.setOnClickListener {
			val expanded = compatibilityContent.visibility == View.VISIBLE
			compatibilityContent.visibility = if (expanded) View.GONE else View.VISIBLE
			compatibilityChevron.text = if (expanded) "▸" else "▾"
		}

		manifestHeader.setOnClickListener {
			val expanded = manifestContent.visibility == View.VISIBLE
			manifestContent.visibility = if (expanded) View.GONE else View.VISIBLE
			manifestChevron.text = if (expanded) "▸" else "▾"
		}

		labelEditText.setText("Clone")

		deepCloneCheckbox.setOnCheckedChangeListener { _, isChecked ->
			if (isChecked) dualDexCheckbox.isChecked = false
		}
		dualDexCheckbox.setOnCheckedChangeListener { _, isChecked ->
			if (isChecked) deepCloneCheckbox.isChecked = false
		}

		AlertDialog.Builder(this)
			.setTitle("Clone ${appInfo.label}")
			.setView(dialogView)
			.setPositiveButton("Clone") { _, _ ->
				val settings = CloneSettings(
					sourcePackageName = appInfo.packageName,
					cloneLabel = labelEditText.text.toString().trim()
						.takeIf { it.isNotEmpty() } ?: "Clone",
					deepClone = deepCloneCheckbox.isChecked,
					dualDex = dualDexCheckbox.isChecked,
					patchNativeLibs = patchNativeCheckbox.isChecked,
					overrideMinSdk = minSdkEditText.text.toString().trim().toIntOrNull(),
					overrideTargetSdk = targetSdkEditText.text.toString().trim().toIntOrNull()
				)
				startCloning(settings)
			}
			.setNegativeButton("Cancel", null)
			.show()
	}

	private fun startCloning(settings: CloneSettings) {
		// Check install permission first
		val installer = ApkInstaller(this)
		if (!installer.canInstallPackages()) {
			val permIntent = installer.getInstallPermissionIntent()
			if (permIntent != null) {
				Toast.makeText(
					this,
					"Please enable 'Install unknown apps' for APK Cloner",
					Toast.LENGTH_LONG
				).show()
				pendingCloneSettings = settings
				startActivityForResult(permIntent, REQUEST_INSTALL_PERMISSION)
				return
			}
		}

		startActivity(
			Intent(this, CloneProgressActivity::class.java)
				.putExtra("settings", settings)
		)
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		super.onActivityResult(requestCode, resultCode, data)
		if (requestCode == REQUEST_INSTALL_PERMISSION) {
			val installer = ApkInstaller(this)
			if (installer.canInstallPackages()) {
				val pending = pendingCloneSettings
				pendingCloneSettings = null
				if (pending != null) {
					startCloning(pending)
				} else {
					Toast.makeText(this, "Permission granted. Select an app to clone.", Toast.LENGTH_SHORT).show()
				}
			}
		}
	}
}
