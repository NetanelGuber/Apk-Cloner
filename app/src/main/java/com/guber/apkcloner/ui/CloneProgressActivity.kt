package com.guber.apkcloner.ui

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
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.guber.apkcloner.databinding.ActivityCloneProgressBinding
import com.guber.apkcloner.engine.ApkInstaller
import com.guber.apkcloner.engine.CloneEngine
import com.guber.apkcloner.engine.CloneSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.io.StringWriter

class CloneProgressActivity : AppCompatActivity() {

	private lateinit var binding: ActivityCloneProgressBinding
	private lateinit var viewModel: CloneProgressViewModel
	private var installReceiver: BroadcastReceiver? = null
	private var installStarted = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		binding = ActivityCloneProgressBinding.inflate(layoutInflater)
		setContentView(binding.root)

		supportActionBar?.setDisplayHomeAsUpEnabled(true)
		supportActionBar?.title = "Cloning..."

		viewModel = ViewModelProvider(this)[CloneProgressViewModel::class.java]

		@Suppress("DEPRECATION")
		val settings = intent.getSerializableExtra("settings") as? CloneSettings
		if (settings == null) {
			finish()
			return
		}

		viewModel.status.observe(this) { status ->
			binding.statusText.text = status
		}

		viewModel.progress.observe(this) { progress ->
			binding.progressBar.progress = progress
			if (progress >= 93) installStarted = true
		}

		viewModel.error.observe(this) { error ->
			if (error != null) {
				showErrorDialog(error)
			}
		}

		viewModel.done.observe(this) { done ->
			if (done) {
				binding.statusText.text = "Clone installed successfully!"
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

		registerInstallReceiver()

		if (!viewModel.started) {
			viewModel.started = true
			viewModel.startCloning(settings, applicationContext)
		}
	}

	private fun registerInstallReceiver() {
		installReceiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent) {
				val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
				when (status) {
					PackageInstaller.STATUS_SUCCESS -> {
						viewModel.done.postValue(true)
					}
					else -> {
						val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
						viewModel.error.postValue("Install failed: ${message ?: "Unknown error (status=$status)"}")
					}
				}
			}
		}

		val filter = IntentFilter(ApkInstaller.ACTION_INSTALL_STATUS)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			registerReceiver(installReceiver, filter, RECEIVER_NOT_EXPORTED)
		} else {
			registerReceiver(installReceiver, filter)
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

	override fun onDestroy() {
		super.onDestroy()
		installReceiver?.let {
			try {
				unregisterReceiver(it)
			} catch (_: Exception) {
			}
		}
	}
}

class CloneProgressViewModel : ViewModel() {
	val status = MutableLiveData<String>()
	val progress = MutableLiveData(0)
	val error = MutableLiveData<String?>()
	val done = MutableLiveData(false)
	var started = false

	fun startCloning(settings: CloneSettings, context: Context) {
		viewModelScope.launch(Dispatchers.IO) {
			try {
				CloneEngine(context).clone(settings) { step, pct ->
					withContext(Dispatchers.Main) {
						status.value = step
						progress.value = pct
					}
				}
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
