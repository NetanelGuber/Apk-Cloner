package com.yourname.apkcloner.engine

import com.android.apksig.ApkSigner
import com.yourname.apkcloner.util.KeystoreUtils
import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate

class ApkSignerModule {

	fun sign(
		unsignedApk: File,
		signedApk: File,
		keystoreFile: File,
		alias: String = "apkcloner",
		password: String = "apkcloner_pw"
	) {
		val ks = KeystoreUtils.getOrCreateKeystore(keystoreFile, alias, password)
		val privateKey = ks.getKey(alias, password.toCharArray()) as PrivateKey
		val cert = ks.getCertificate(alias) as X509Certificate

		val signerConfig = ApkSigner.SignerConfig.Builder(
			"CERT",
			privateKey,
			listOf(cert)
		).build()

		ApkSigner.Builder(listOf(signerConfig))
			.setInputApk(unsignedApk)
			.setOutputApk(signedApk)
			.setV1SigningEnabled(true)
			.setV2SigningEnabled(true)
			.setV3SigningEnabled(false)
			.setMinSdkVersion(21)
			.build()
			.sign()
	}
}
