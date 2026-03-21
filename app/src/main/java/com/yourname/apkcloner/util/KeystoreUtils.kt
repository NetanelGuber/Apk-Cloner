package com.yourname.apkcloner.util

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date

object KeystoreUtils {

	private const val DEFAULT_ALIAS = "apkcloner"
	private const val DEFAULT_PASSWORD = "apkcloner_pw"
	private const val VALIDITY_YEARS = 25L

	fun getOrCreateKeystore(
		keystoreFile: File,
		alias: String = DEFAULT_ALIAS,
		password: String = DEFAULT_PASSWORD
	): KeyStore {
		if (keystoreFile.exists()) {
			val ks = KeyStore.getInstance("PKCS12")
			keystoreFile.inputStream().use { ks.load(it, password.toCharArray()) }
			return ks
		}
		return createKeystore(keystoreFile, alias, password)
	}

	private fun createKeystore(
		keystoreFile: File,
		alias: String,
		password: String
	): KeyStore {
		keystoreFile.parentFile?.mkdirs()

		val kpg = KeyPairGenerator.getInstance("RSA")
		kpg.initialize(2048)
		val keyPair = kpg.generateKeyPair()

		val now = Date()
		val expiry = Date(System.currentTimeMillis() + VALIDITY_YEARS * 365L * 24 * 60 * 60 * 1000)
		val issuer = X500Name("CN=APKCloner, O=APKCloner, C=US")

		val certBuilder = JcaX509v3CertificateBuilder(
			issuer,
			BigInteger.valueOf(System.currentTimeMillis()),
			now,
			expiry,
			issuer,
			keyPair.public
		)
		val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
		val cert = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))

		val ks = KeyStore.getInstance("PKCS12")
		ks.load(null, password.toCharArray())
		ks.setKeyEntry(alias, keyPair.private, password.toCharArray(), arrayOf(cert))
		keystoreFile.outputStream().use { ks.store(it, password.toCharArray()) }
		return ks
	}

	fun getKeystoreDir(context: android.content.Context): File {
		return File(context.filesDir, "keystores").also { it.mkdirs() }
	}
}
