package com.guber.apkcloner.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.guber.apkcloner.databinding.ActivityCloneProgressBinding
import com.guber.apkcloner.engine.ApkInstaller
import com.guber.apkcloner.engine.CloneEngine
import com.guber.apkcloner.engine.CloneSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.io.StringWriter

class CloneProgressActivity : AppCompatActivity() {

	private lateinit var binding: ActivityCloneProgressBinding
	private lateinit var viewModel: CloneProgressViewModel
	private var installStarted = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		binding = ActivityCloneProgressBinding.inflate(layoutInflater)
		setContentView(binding.root)

		supportActionBar?.setDisplayHomeAsUpEnabled(true)
		supportActionBar?.title = "Cloning..."

		viewModel = ViewModelProvider(this)[CloneProgressViewModel::class.java]

		val settings: CloneSettings = (
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				intent.getSerializableExtra("settings", CloneSettings::class.java)
			} else {
				@Suppress("DEPRECATION")
				intent.getSerializableExtra("settings") as? CloneSettings
			}
		) ?: run { finish(); return }

		viewModel.status.observe(this) { status ->
			binding.statusText.text = status
		}

		viewModel.progress.observe(this) { progress ->
			binding.progressBar.progress = progress
			if (progress >= 93 && settings.installAfterBuild) installStarted = true
		}

		viewModel.error.observe(this) { error ->
			if (error != null) {
				showErrorDialog(error)
			}
		}

		viewModel.done.observe(this) { done ->
			if (done) {
				binding.statusText.text = when {
					settings.saveToStorage && !settings.installAfterBuild -> "APK saved to Downloads!"
					settings.saveToStorage -> "Installed and saved to Downloads!"
					else -> "Clone installed successfully!"
				}
				binding.doneButton.isEnabled = true
			}
		}

		binding.doneButton.setOnClickListener {
			finish()
		}

		binding.cancelButton.setOnClickListener {
			if (installStarted) {
				AlertDialog.Builder(this)
					.setTitle("Installation in Progress")
					.setMessage("The installation is already underway and will continue in the background.")
					.setPositiveButton("OK") { _, _ -> finish() }
					.setNegativeButton("Stay") { d, _ -> d.dismiss() }
					.show()
			} else {
				finish()
			}
		}

		if (!viewModel.started) {
			viewModel.started = true
			viewModel.startCloning(settings, applicationContext)
		}
	}

	private fun showErrorDialog(error: String) {
		AlertDialog.Builder(this)
			.setTitle("Cloning Failed")
			.setMessage(error)
			.setPositiveButton("OK") { _, _ -> finish() }
			.setNeutralButton("Copy") { _, _ ->
				val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
				clipboard.setPrimaryClip(ClipData.newPlainText("Clone Error", error))
				Toast.makeText(this, "Error copied to clipboard", Toast.LENGTH_SHORT).show()
			}
			.setCancelable(false)
			.show()
	}

	override fun onSupportNavigateUp(): Boolean {
		finish()
		return true
	}
}

class CloneProgressViewModel(application: Application) : AndroidViewModel(application) {
	val status = MutableLiveData<String>()
	val progress = MutableLiveData(0)
	val error = MutableLiveData<String?>()
	val done = MutableLiveData(false)
	var started = false

	private val installReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
			when (status) {
				PackageInstaller.STATUS_SUCCESS -> done.postValue(true)
				else -> {
					val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
					error.postValue("Install failed: ${message ?: "Unknown error (status=$status)"}")
				}
			}
		}
	}

	init {
		val filter = IntentFilter(ApkInstaller.ACTION_INSTALL_STATUS)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			application.registerReceiver(installReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
		} else {
			application.registerReceiver(installReceiver, filter)
		}
	}

	override fun onCleared() {
		super.onCleared()
		try {
			getApplication<Application>().unregisterReceiver(installReceiver)
		} catch (_: Exception) {}
	}

	fun startCloning(settings: CloneSettings, context: Context) {
		val installAfterBuild = settings.installAfterBuild
		viewModelScope.launch(Dispatchers.IO) {
			try {
				CloneEngine(context).clone(settings) { step, pct ->
					withContext(Dispatchers.Main) {
						status.value = step
						progress.value = pct
					}
				}
				// Install is async (broadcast-driven). For save-only, signal done directly.
				if (!installAfterBuild) {
					withContext(Dispatchers.Main) {
						done.value = true
					}
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				val sw = StringWriter()
				e.printStackTrace(PrintWriter(sw))
				val fullError = "${e.javaClass.simpleName}: ${e.message ?: "No message"}\n\n$sw"
				withContext(Dispatchers.Main) {
					error.value = fullError
				}
			}
		}
	}
}
