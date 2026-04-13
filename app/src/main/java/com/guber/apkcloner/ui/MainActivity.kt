package com.guber.apkcloner.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputLayout
import com.guber.apkcloner.BuildConfig
import com.guber.apkcloner.R
import com.guber.apkcloner.databinding.ActivityMainBinding
import com.guber.apkcloner.engine.ApkInstaller
import com.guber.apkcloner.engine.CloneSettings
import com.guber.apkcloner.engine.FileApkParser
import com.guber.apkcloner.util.CloneSettingsRepository
import com.guber.apkcloner.util.PackageUtils
import com.guber.apkcloner.util.ReleaseInfo
import com.guber.apkcloner.util.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.sin

private const val DEFAULT_SAVE_LOCATION_LABEL = "Downloads/APK Cloner (default)"

class MainActivity : AppCompatActivity() {

	companion object {
		private const val REQUEST_STORAGE_PERMISSION = 100
	}

	private val filePickerLauncher = registerForActivityResult(
		ActivityResultContracts.StartActivityForResult()
	) { result ->
		val uri = result.data?.data
		if (uri != null) onFileSelected(uri)
	}

	/** Folder selected by the user for saving the cloned APK/ZIP. Null = use default Downloads dir. */
	private var pendingSaveUri: Uri? = null

	/** Reference to the location label inside the currently-open clone dialog, updated when the user picks a folder. */
	private var pendingSaveLocationView: TextView? = null

	private val saveLocationLauncher = registerForActivityResult(
		ActivityResultContracts.StartActivityForResult()
	) { result ->
		if (result.resultCode == RESULT_OK) {
			val uri = result.data?.data ?: return@registerForActivityResult
			try {
				contentResolver.takePersistableUriPermission(
					uri,
					Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
				)
			} catch (_: Exception) {}
			pendingSaveUri = uri
			pendingSaveLocationView?.text = "Save to: ${getFolderDisplayName(uri)}"
		}
	}

	private val installPermissionLauncher = registerForActivityResult(
		ActivityResultContracts.StartActivityForResult()
	) { _ ->
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

	private lateinit var binding: ActivityMainBinding
	private lateinit var adapter: AppListAdapter
	private var allApps: List<PackageUtils.AppInfo> = emptyList()
	private var pendingCloneSettings: CloneSettings? = null
	private var showOnlyClones = false
	private var currentQuery = ""

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		binding = ActivityMainBinding.inflate(layoutInflater)
		setContentView(binding.root)

		setSupportActionBar(binding.toolbar)
		supportActionBar?.title = "APK Cloner"

		adapter = AppListAdapter(
			onAppClick = { appInfo -> onAppSelected(appInfo) },
			onUpdateClick = { appInfo -> onUpdateClick(appInfo) }
		)
		binding.recyclerView.layoutManager = LinearLayoutManager(this)
		binding.recyclerView.adapter = adapter

		binding.openFileButton.setOnClickListener {
			openFilePicker()
		}

		binding.tabLayout.addTab(binding.tabLayout.newTab().setText("All Apps"))
		binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Clones"))
		binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
			override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
				showOnlyClones = tab.position == 1
				filterApps()
			}
			override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
			override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
		})

		checkPermissionsAndLoad()
		checkForAppUpdate()
	}

	private var isFirstResume = true

	override fun onResume() {
		super.onResume()
		if (isFirstResume) {
			isFirstResume = false
		} else {
			loadApps()
		}
	}

	override fun onPrepareOptionsMenu(menu: Menu): Boolean {
		val isDark = AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES
		val themeItem = menu.findItem(R.id.action_toggle_dark_mode)
		themeItem?.icon = ContextCompat.getDrawable(
			this,
			if (isDark) R.drawable.ic_light_mode else R.drawable.ic_dark_mode
		)
		themeItem?.title = if (isDark) "Switch to Light Mode" else "Switch to Dark Mode"
		return super.onPrepareOptionsMenu(menu)
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(R.menu.menu_main, menu)
		val searchItem = menu.findItem(R.id.action_search)
		val searchView = searchItem.actionView as SearchView
		searchView.queryHint = "Search apps..."
		searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
			override fun onQueryTextSubmit(query: String?) = false
			override fun onQueryTextChange(newText: String?): Boolean {
				currentQuery = newText ?: ""
				filterApps()
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
			R.id.action_toggle_dark_mode -> {
				val current = AppCompatDelegate.getDefaultNightMode()
				val newMode = if (current == AppCompatDelegate.MODE_NIGHT_YES)
					AppCompatDelegate.MODE_NIGHT_NO
				else
					AppCompatDelegate.MODE_NIGHT_YES
				getSharedPreferences("app_prefs", MODE_PRIVATE)
					.edit().putInt("night_mode", newMode).apply()
				AppCompatDelegate.setDefaultNightMode(newMode)
				true
			}
			else -> super.onOptionsItemSelected(item)
		}
	}

	private fun filterApps() {
		var list = allApps
		if (showOnlyClones) list = list.filter { it.isClone }
		if (currentQuery.isNotEmpty()) list = list.filter {
			it.label.contains(currentQuery, ignoreCase = true) ||
				it.packageName.contains(currentQuery, ignoreCase = true)
		}
		adapter.submitList(list)
	}

	private fun openFilePicker() {
		val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
			addCategory(Intent.CATEGORY_OPENABLE)
			type = "*/*"
		}
		filePickerLauncher.launch(intent)
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

			// Second pass: check for available updates on cloned apps
			val repo = CloneSettingsRepository(this@MainActivity)
			val appsWithUpdates = apps.map { app ->
				if (app.isClone) {
					val saved = repo.load(app.packageName)
					if (saved != null) {
						try {
							val updateAvailable = compareVersionNames(
								getVersionName(saved.sourcePackageName),
								getVersionName(app.packageName)
							) > 0
							app.copy(updateAvailable = updateAvailable)
						} catch (_: Exception) { app }
					} else app
				} else app
			}

			withContext(Dispatchers.Main) {
				allApps = appsWithUpdates
				filterApps()
				binding.progressBar.visibility = View.GONE
				if (appsWithUpdates.isEmpty()) {
					binding.emptyView.visibility = View.VISIBLE
				} else {
					binding.recyclerView.visibility = View.VISIBLE
				}
			}
		}
	}

	private fun getVersionName(packageName: String): String {
		return try {
			packageManager.getPackageInfo(packageName, 0).versionName ?: ""
		} catch (_: Exception) { "" }
	}

	/**
	 * Compares two version name strings segment-by-segment numerically.
	 * e.g. "1.260420.20" > "1.260330.27" because segment 1: 260420 > 260330.
	 * Returns positive if [a] is newer, negative if older, 0 if equal.
	 */
	private fun compareVersionNames(a: String, b: String): Int {
		val partsA = a.split(".")
		val partsB = b.split(".")
		val len = maxOf(partsA.size, partsB.size)
		for (i in 0 until len) {
			val segA = partsA.getOrNull(i)?.toLongOrNull() ?: 0L
			val segB = partsB.getOrNull(i)?.toLongOrNull() ?: 0L
			val cmp = segA.compareTo(segB)
			if (cmp != 0) return cmp
		}
		return 0
	}

	private fun onAppSelected(appInfo: PackageUtils.AppInfo) {
		val (minSdk, targetSdk) = readAppSdkInfo(appInfo.packageName, null)
		showCloneDialog("Clone ${appInfo.label}", appInfo.packageName, appInfo.label, null, minSdk, targetSdk)
	}

	private fun onUpdateClick(appInfo: PackageUtils.AppInfo) {
		val saved = CloneSettingsRepository(this).load(appInfo.packageName) ?: run {
			Toast.makeText(this, "No saved settings found for this clone.", Toast.LENGTH_SHORT).show()
			return
		}
		val (minSdk, targetSdk) = readAppSdkInfo(saved.sourcePackageName, null)
		showCloneDialog(
			"Update ${appInfo.label}",
			saved.sourcePackageName,
			appInfo.label,
			null,
			minSdk,
			targetSdk,
			savedSettings = saved
		)
	}

	private fun onFileSelected(uri: Uri) {
		val progressDialog = AlertDialog.Builder(this)
			.setTitle("Analyzing file...")
			.setMessage("Please wait...")
			.setCancelable(false)
			.show()

		lifecycleScope.launch(Dispatchers.IO) {
			try {
				val stagingDir = File(cacheDir, "import_staging")
				val fileInfo = FileApkParser(this@MainActivity).parse(uri, stagingDir)
				val (minSdk, targetSdk) = readAppSdkInfo(fileInfo.packageName, listOf(fileInfo.baseApkPath))
				withContext(Dispatchers.Main) {
					progressDialog.dismiss()
					showCloneDialog(
						"Clone ${fileInfo.appLabel}",
						fileInfo.packageName,
						fileInfo.appLabel,
						listOf(fileInfo.baseApkPath) + fileInfo.splitApkPaths,
						minSdk,
						targetSdk
					)
				}
			} catch (e: Exception) {
				withContext(Dispatchers.Main) {
					progressDialog.dismiss()
					AlertDialog.Builder(this@MainActivity)
						.setTitle("Error")
						.setMessage("Could not read file: ${e.message}")
						.setPositiveButton("OK", null)
						.show()
				}
			}
		}
	}

	/** Reads min/target SDK from an installed package or a staged APK file. Safe to call from any thread. */
	private fun readAppSdkInfo(packageName: String, sourceApkPaths: List<String>?): Pair<String, String> {
		return try {
			if (sourceApkPaths != null) {
				@Suppress("DEPRECATION")
				val info = packageManager.getPackageArchiveInfo(sourceApkPaths[0], 0)
				val appInfo = info?.applicationInfo
				val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
					appInfo?.minSdkVersion?.toString() ?: "?"
				} else "?"
				val targetSdk = appInfo?.targetSdkVersion?.toString() ?: "?"
				Pair(minSdk, targetSdk)
			} else {
				val appInfo = packageManager.getApplicationInfo(packageName, 0)
				val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
					appInfo.minSdkVersion.toString()
				} else "?"
				val targetSdk = appInfo.targetSdkVersion.toString()
				Pair(minSdk, targetSdk)
			}
		} catch (_: Exception) {
			Pair("?", "?")
		}
	}

	/**
	 * Converts a folder tree URI (from ACTION_OPEN_DOCUMENT_TREE) into a short human-readable label.
	 */
	private fun getFolderDisplayName(uri: Uri): String {
		return try {
			val segment = uri.lastPathSegment ?: return "Selected folder"
			segment.substringAfter(':').ifEmpty { "Selected folder" }
		} catch (_: Exception) {
			"Selected folder"
		}
	}

	/** Returns true if the string is a valid Android package name (at least two dot-separated segments). */
	private fun isValidPackageName(name: String): Boolean {
		return name.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))
	}

	private fun showCloneDialog(
		title: String,
		packageName: String,
		appLabel: String,
		sourceApkPaths: List<String>?,
		appMinSdk: String = "?",
		appTargetSdk: String = "?",
		savedSettings: CloneSettings? = null
	) {
		val dialogView = layoutInflater.inflate(R.layout.dialog_clone_settings, null)
		val labelEditText = dialogView.findViewById<EditText>(R.id.labelEditText)
		val deepCloneCheckbox = dialogView.findViewById<MaterialCheckBox>(R.id.deepCloneCheckbox)
		val patchNativeCheckbox = dialogView.findViewById<MaterialCheckBox>(R.id.patchNativeCheckbox)
		val dualDexCheckbox = dialogView.findViewById<MaterialCheckBox>(R.id.dualDexCheckbox)
		val pkgShimCheckbox = dialogView.findViewById<MaterialCheckBox>(R.id.pkgShimCheckbox)
		val spoofSignaturesCheckbox = dialogView.findViewById<MaterialCheckBox>(R.id.spoofSignaturesCheckbox)
		val minSdkEditText = dialogView.findViewById<EditText>(R.id.minSdkEditText)
		val targetSdkEditText = dialogView.findViewById<EditText>(R.id.targetSdkEditText)
		val customPackageEditText = dialogView.findViewById<EditText>(R.id.customPackageEditText)
		val actionRadioGroup = dialogView.findViewById<RadioGroup>(R.id.actionRadioGroup)
		val minSdkLayout = dialogView.findViewById<TextInputLayout>(R.id.minSdkLayout)
		val targetSdkLayout = dialogView.findViewById<TextInputLayout>(R.id.targetSdkLayout)

		val saveLocationSection = dialogView.findViewById<LinearLayout>(R.id.saveLocationSection)
		val saveLocationText = dialogView.findViewById<TextView>(R.id.saveLocationText)
		val browseButton = dialogView.findViewById<View>(R.id.browseButton)

		val compatibilityHeader = dialogView.findViewById<LinearLayout>(R.id.compatibilityHeader)
		val compatibilityContent = dialogView.findViewById<LinearLayout>(R.id.compatibilityContent)
		val compatibilityChevron = dialogView.findViewById<TextView>(R.id.compatibilityChevron)
		val manifestHeader = dialogView.findViewById<LinearLayout>(R.id.manifestHeader)
		val manifestContent = dialogView.findViewById<LinearLayout>(R.id.manifestContent)
		val manifestChevron = dialogView.findViewById<TextView>(R.id.manifestChevron)

		// Icon section
		val iconHeader = dialogView.findViewById<LinearLayout>(R.id.iconHeader)
		val iconContent = dialogView.findViewById<LinearLayout>(R.id.iconContent)
		val iconChevron = dialogView.findViewById<TextView>(R.id.iconChevron)
		val iconPreview = dialogView.findViewById<ImageView>(R.id.iconPreview)
		val hueSlider = dialogView.findViewById<Slider>(R.id.hueSlider)
		val saturationSlider = dialogView.findViewById<Slider>(R.id.saturationSlider)
		val contrastSlider = dialogView.findViewById<Slider>(R.id.contrastSlider)
		val hueLabel = dialogView.findViewById<TextView>(R.id.hueLabel)
		val saturationLabel = dialogView.findViewById<TextView>(R.id.saturationLabel)
		val contrastLabel = dialogView.findViewById<TextView>(R.id.contrastLabel)

		// Reset save-location state for this fresh dialog
		pendingSaveUri = null
		pendingSaveLocationView = saveLocationText

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

		// ── Icon section ──────────────────────────────────────────────────────

		// Load the app icon for the preview (use sourcePackageName when updating)
		val iconPackage = savedSettings?.sourcePackageName ?: packageName
		val originalIcon: Drawable? = loadAppIcon(iconPackage, sourceApkPaths)
		iconPreview.setImageDrawable(originalIcon)

		iconHeader.setOnClickListener {
			val expanded = iconContent.visibility == View.VISIBLE
			iconContent.visibility = if (expanded) View.GONE else View.VISIBLE
			iconChevron.text = if (expanded) "▸" else "▾"
		}

		/** Redraws the preview ImageView with the current slider values applied. */
		fun refreshIconPreview() {
			val hue = hueSlider.value
			val sat = saturationSlider.value / 100f
			val con = contrastSlider.value / 100f

			hueLabel.text = "Hue: ${hue.toInt()}°"
			saturationLabel.text = "Saturation: ${saturationSlider.value.toInt()}"
			contrastLabel.text = "Contrast: ${contrastSlider.value.toInt()}"

			if (originalIcon == null) return

			if (hue == 0f && sat == 0f && con == 0f) {
				iconPreview.colorFilter = null
				iconPreview.setImageDrawable(originalIcon)
				return
			}

			iconPreview.colorFilter = buildIconColorFilter(hue, sat, con)
		}

		val sliderListener = Slider.OnChangeListener { _, _, _ -> refreshIconPreview() }
		hueSlider.addOnChangeListener(sliderListener)
		saturationSlider.addOnChangeListener(sliderListener)
		contrastSlider.addOnChangeListener(sliderListener)

		// ── Rest of dialog ────────────────────────────────────────────────────

		// Show/hide the save-location row based on whether a save option is selected
		actionRadioGroup.setOnCheckedChangeListener { _, checkedId ->
			val isSaveOption = checkedId == R.id.radioSave || checkedId == R.id.radioInstallAndSave
			saveLocationSection.visibility = if (isSaveOption) View.VISIBLE else View.GONE
		}

		browseButton.setOnClickListener {
			saveLocationLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
		}

		// Populate SDK helper text so the user knows what they're overriding
		minSdkLayout.helperText = "Current: $appMinSdk — leave blank to keep"
		targetSdkLayout.helperText = "Current: $appTargetSdk — leave blank to keep"

		deepCloneCheckbox.setOnCheckedChangeListener { _, isChecked ->
			if (isChecked) dualDexCheckbox.isChecked = false
		}
		dualDexCheckbox.setOnCheckedChangeListener { _, isChecked ->
			if (isChecked) deepCloneCheckbox.isChecked = false
		}

		// ── Pre-fill from saved settings (used when updating a clone) ─────────
		if (savedSettings != null) {
			labelEditText.setText(savedSettings.cloneLabel)
			deepCloneCheckbox.isChecked = savedSettings.deepClone
			dualDexCheckbox.isChecked = savedSettings.dualDex
			patchNativeCheckbox.isChecked = savedSettings.patchNativeLibs
			pkgShimCheckbox.isChecked = savedSettings.pkgShim
			spoofSignaturesCheckbox.isChecked = savedSettings.spoofSignatures
			minSdkEditText.setText(savedSettings.overrideMinSdk?.toString() ?: "")
			targetSdkEditText.setText(savedSettings.overrideTargetSdk?.toString() ?: "")
			customPackageEditText.setText(savedSettings.newPackageName)
			hueSlider.value = savedSettings.iconHue
			saturationSlider.value = savedSettings.iconSaturation * 100f
			contrastSlider.value = savedSettings.iconContrast * 100f

			// Expand sections that have non-default values
			val hasCompatOptions = savedSettings.deepClone || savedSettings.dualDex ||
				savedSettings.patchNativeLibs || savedSettings.pkgShim || savedSettings.spoofSignatures
			if (hasCompatOptions) {
				compatibilityContent.visibility = View.VISIBLE
				compatibilityChevron.text = "▾"
			}
			val hasManifestOptions = savedSettings.overrideMinSdk != null ||
				savedSettings.overrideTargetSdk != null
			if (hasManifestOptions) {
				manifestContent.visibility = View.VISIBLE
				manifestChevron.text = "▾"
			}
			val hasIconOptions = savedSettings.iconHue != 0f ||
				savedSettings.iconSaturation != 0f || savedSettings.iconContrast != 0f
			if (hasIconOptions) {
				iconContent.visibility = View.VISIBLE
				iconChevron.text = "▾"
				refreshIconPreview()
			}

			// Restore radio selection
			when {
				savedSettings.saveToStorage && !savedSettings.installAfterBuild ->
					actionRadioGroup.check(R.id.radioSave)
				savedSettings.saveToStorage && savedSettings.installAfterBuild ->
					actionRadioGroup.check(R.id.radioInstallAndSave)
				else -> actionRadioGroup.check(R.id.radioInstall)
			}
		} else {
			labelEditText.setText("$appLabel Clone")
		}

		var cloneStarted = false
		AlertDialog.Builder(this)
			.setTitle(title)
			.setView(dialogView)
			.setPositiveButton("Clone") { _, _ ->
				cloneStarted = true

				// Resolve new package name — use custom if provided and valid
				val customPkg = customPackageEditText.text.toString().trim()
				val newPkg = when {
					customPkg.isNotEmpty() && isValidPackageName(customPkg) -> customPkg
					customPkg.isNotEmpty() -> {
						Toast.makeText(
							this,
							"Invalid package name — using auto-generated",
							Toast.LENGTH_SHORT
						).show()
						generateUniquePackageName(packageName)
					}
					else -> generateUniquePackageName(packageName)
				}

				// Resolve save/install action from radio group
				val (saveToStorage, installAfterBuild) = when (actionRadioGroup.checkedRadioButtonId) {
					R.id.radioSave -> Pair(true, false)
					R.id.radioInstallAndSave -> Pair(true, true)
					else -> Pair(false, true)
				}

				val settings = CloneSettings(
					sourcePackageName = packageName,
					newPackageName = newPkg,
					cloneLabel = labelEditText.text.toString().trim()
						.takeIf { it.isNotEmpty() } ?: "$appLabel Clone",
					deepClone = deepCloneCheckbox.isChecked,
					dualDex = dualDexCheckbox.isChecked,
					patchNativeLibs = patchNativeCheckbox.isChecked,
					pkgShim = pkgShimCheckbox.isChecked,
					overrideMinSdk = minSdkEditText.text.toString().trim().toIntOrNull(),
					overrideTargetSdk = targetSdkEditText.text.toString().trim().toIntOrNull(),
					sourceApkPaths = sourceApkPaths,
					saveToStorage = saveToStorage,
					installAfterBuild = installAfterBuild,
					saveLocationUri = pendingSaveUri?.toString(),
					iconHue = hueSlider.value,
					iconSaturation = saturationSlider.value / 100f,
					iconContrast = contrastSlider.value / 100f,
					spoofSignatures = spoofSignaturesCheckbox.isChecked
				)

				// Persist settings so they can be recalled for future updates
				CloneSettingsRepository(this).save(settings)

				startCloning(settings)
			}
			.setNegativeButton("Cancel", null)
			.setOnDismissListener {
				pendingSaveLocationView = null  // avoid stale reference after dialog closes
				if (!cloneStarted && sourceApkPaths != null) {
					try {
						sourceApkPaths.forEach { File(it).delete() }
						sourceApkPaths.firstOrNull()?.let { File(it).parentFile?.delete() }
					} catch (_: Exception) {}
				}
			}
			.show()
	}

	/** Loads the launcher icon for an installed package or the first path in sourceApkPaths. */
	private fun loadAppIcon(packageName: String, sourceApkPaths: List<String>?): Drawable? {
		return try {
			if (sourceApkPaths != null) {
				@Suppress("DEPRECATION")
				val info = packageManager.getPackageArchiveInfo(sourceApkPaths[0], 0)
				info?.applicationInfo?.apply {
					sourceDir = sourceApkPaths[0]
					publicSourceDir = sourceApkPaths[0]
				}?.loadIcon(packageManager)
			} else {
				packageManager.getApplicationIcon(packageName)
			}
		} catch (_: Exception) { null }
	}

	/**
	 * Builds a ColorMatrixColorFilter combining hue-rotation, saturation delta,
	 * and contrast delta — mirrors the logic in IconPatcher so the preview matches
	 * the actual patched icon.
	 */
	private fun buildIconColorFilter(hue: Float, saturation: Float, contrast: Float): ColorMatrixColorFilter {
		val result = ColorMatrix()

		if (hue != 0f) {
			val rad = Math.toRadians(hue.toDouble())
			val c = cos(rad).toFloat()
			val s = sin(rad).toFloat()
			val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
			val hueValues = floatArrayOf(
				lr + c * (1f - lr) + s * (-lr),    lg + c * (-lg) + s * (-lg),        lb + c * (-lb) + s * (1f - lb), 0f, 0f,
				lr + c * (-lr) + s * (0.143f),      lg + c * (1f - lg) + s * (0.140f), lb + c * (-lb) + s * (-0.283f), 0f, 0f,
				lr + c * (-lr) + s * (-(1f - lr)),  lg + c * (-lg) + s * (lg),         lb + c * (1f - lb) + s * (lb),  0f, 0f,
				0f, 0f, 0f, 1f, 0f
			)
			result.postConcat(ColorMatrix(hueValues))
		}

		if (saturation != 0f) {
			val sat = ColorMatrix()
			sat.setSaturation(1f + saturation)
			result.postConcat(sat)
		}

		if (contrast != 0f) {
			val scale = 1f + contrast
			val translate = (-0.5f * scale + 0.5f) * 255f
			val contrastValues = floatArrayOf(
				scale, 0f,    0f,    0f, translate,
				0f,    scale, 0f,    0f, translate,
				0f,    0f,    scale, 0f, translate,
				0f,    0f,    0f,    1f, 0f
			)
			result.postConcat(ColorMatrix(contrastValues))
		}

		return ColorMatrixColorFilter(result)
	}

	private fun generateUniquePackageName(source: String): String {
		val base = CloneSettings.generateNewPackageName(source)
		if (!isPackageInstalled(base)) return base
		var n = 2
		while (n <= 100) {
			val candidate = "${base}${n}"
			if (!isPackageInstalled(candidate)) return candidate
			n++
		}
		return base
	}

	private fun isPackageInstalled(packageName: String): Boolean {
		return try {
			packageManager.getApplicationInfo(packageName, 0)
			true
		} catch (_: PackageManager.NameNotFoundException) {
			false
		}
	}

	private fun startCloning(settings: CloneSettings) {
		// Only need install-packages permission when actually installing
		if (settings.installAfterBuild) {
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
					installPermissionLauncher.launch(permIntent)
					return
				}
			}
		}

		startActivity(
			Intent(this, CloneProgressActivity::class.java)
				.putExtra("settings", settings)
		)
	}

	// ── Self-update (Feature 4) ───────────────────────────────────────────────

	private fun isNetworkConnected(): Boolean {
		val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
			caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
				caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
		} else {
			@Suppress("DEPRECATION")
			cm.activeNetworkInfo?.isConnected == true
		}
	}

	private fun checkForAppUpdate() {
		if (!isNetworkConnected()) return
		lifecycleScope.launch(Dispatchers.IO) {
			val info = UpdateChecker.fetchLatestRelease() ?: return@launch
			if (!UpdateChecker.isNewerVersion(info.tagName, BuildConfig.VERSION_NAME)) return@launch
			val skipped = getSharedPreferences("app_prefs", MODE_PRIVATE)
				.getString("skipped_update_version", null)
			if (skipped == info.tagName) return@launch
			withContext(Dispatchers.Main) {
				showSelfUpdateDialog(info)
			}
		}
	}

	private fun showSelfUpdateDialog(info: ReleaseInfo) {
		AlertDialog.Builder(this)
			.setTitle("Update available — ${info.tagName}")
			.setMessage(info.changelog.ifBlank { "A new version is available." })
			.setPositiveButton("Update") { _, _ ->
				val url = info.apkUrl
				if (url != null) {
					downloadAndInstallUpdate(url)
				} else {
					Toast.makeText(this, "No APK found in this release.", Toast.LENGTH_LONG).show()
				}
			}
			.setNegativeButton("Skip") { _, _ ->
				getSharedPreferences("app_prefs", MODE_PRIVATE)
					.edit().putString("skipped_update_version", info.tagName).apply()
			}
			.show()
	}

	private fun downloadAndInstallUpdate(url: String) {
		val progressDialog = AlertDialog.Builder(this)
			.setTitle("Downloading update…")
			.setMessage("Please wait.")
			.setCancelable(false)
			.show()

		lifecycleScope.launch(Dispatchers.IO) {
			try {
				val updateDir = File(cacheDir, "update")
				updateDir.mkdirs()
				val updateFile = File(updateDir, "app-update.apk")

				val conn = URL(url).openConnection() as HttpURLConnection
				conn.connectTimeout = 15_000
				conn.readTimeout = 60_000
				conn.connect()
				conn.inputStream.use { input ->
					FileOutputStream(updateFile).use { output ->
						input.copyTo(output)
					}
				}
				conn.disconnect()

				withContext(Dispatchers.Main) {
					progressDialog.dismiss()
					val uri = FileProvider.getUriForFile(
						this@MainActivity,
						"${packageName}.fileprovider",
						updateFile
					)
					val intent = Intent(Intent.ACTION_VIEW).apply {
						setDataAndType(uri, "application/vnd.android.package-archive")
						addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
						addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
					}
					startActivity(intent)
				}
			} catch (e: Exception) {
				withContext(Dispatchers.Main) {
					progressDialog.dismiss()
					Toast.makeText(
						this@MainActivity,
						"Download failed: ${e.message}",
						Toast.LENGTH_LONG
					).show()
				}
			}
		}
	}
}
