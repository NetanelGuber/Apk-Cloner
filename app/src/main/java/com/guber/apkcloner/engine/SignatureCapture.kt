package com.guber.apkcloner.engine

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Parcel
import android.util.Base64
import androidx.annotation.RequiresApi
import org.bouncycastle.cms.CMSSignedData
import java.util.zip.ZipFile

/**
 * Captures the signing certificates of either an installed app or an APK file.
 *
 * Returns a [SpoofingCertData] holding Base64-encoded Parcel-serialised Signature[]
 * (works on all API levels) and optionally a SigningInfo (API 28+).
 *
 * These values are later embedded as meta-data in the cloned APK's manifest so that
 * HookEntryProvider / SignatureSpoofing can reconstruct them at runtime and feed them
 * back to any code that calls PackageManager.getPackageInfo(..., GET_SIGNATURES).
 */
object SignatureCapture {

    data class SpoofingCertData(
        val sigsBase64: String,
        val signingInfoBase64: String?
    )

    /**
     * Captures signing certificates for the app described by [settings].
     * Call this on a background thread — it may involve disk I/O.
     */
    fun capture(context: Context, settings: CloneSettings): SpoofingCertData {
        return if (settings.sourceApkPaths == null) {
            captureFromInstalled(context, settings.sourcePackageName)
        } else {
            captureFromApkFile(settings.sourceApkPaths.first())
        }
    }

    // ── Installed app ─────────────────────────────────────────────────────────

    private fun captureFromInstalled(context: Context, packageName: String): SpoofingCertData {
        val pm = context.packageManager

        @Suppress("DEPRECATION")
        val pi = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        val sigsBase64 = serializeSignatures(pi.signatures ?: emptyArray())

        val signingInfoBase64 = if (Build.VERSION.SDK_INT >= 28) {
            val pi2 = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            serializeSigningInfo(pi2.signingInfo)
        } else null

        return SpoofingCertData(sigsBase64, signingInfoBase64)
    }

    // ── Imported APK file ─────────────────────────────────────────────────────

    private fun captureFromApkFile(apkPath: String): SpoofingCertData {
        val certBytes = extractCertFromApk(apkPath)
            ?: error("No signing certificate found in $apkPath (no META-INF/*.RSA/DSA/EC entry)")

        val sig = android.content.pm.Signature(certBytes)
        val sigsBase64 = serializeSignatures(arrayOf(sig))

        // SigningInfo cannot be reconstructed from just the raw certificate bytes without
        // the full pkcs7 chain, so we leave it null. The Signature[] hook alone covers
        // the vast majority of apps that do signature verification.
        return SpoofingCertData(sigsBase64, null)
    }

    private fun extractCertFromApk(apkPath: String): ByteArray? {
        ZipFile(apkPath).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull { e ->
                val n = e.name.uppercase()
                n.startsWith("META-INF/") &&
                    (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))
            } ?: return null

            // The file is a PKCS#7 SignedData block — parse it with BouncyCastle
            // and return the DER-encoded bytes of the first (signer) certificate.
            val pkcs7Bytes = zip.getInputStream(entry).readBytes()
            val cms = CMSSignedData(pkcs7Bytes)
            val certIt = cms.certificates.getMatches(null).iterator()
            return if (certIt.hasNext()) {
                (certIt.next() as org.bouncycastle.cert.X509CertificateHolder).encoded
            } else null
        }
    }

    // ── Serialisation helpers ─────────────────────────────────────────────────

    private fun serializeSignatures(sigs: Array<android.content.pm.Signature>): String {
        val parcel = Parcel.obtain()
        parcel.writeParcelableArray(sigs, 0)
        val bytes = parcel.marshall()
        parcel.recycle()
        return Base64.encodeToString(bytes, Base64.DEFAULT)
    }

    @RequiresApi(28)
    private fun serializeSigningInfo(info: android.content.pm.SigningInfo?): String? {
        info ?: return null
        val parcel = Parcel.obtain()
        info.writeToParcel(parcel, 0)
        val bytes = parcel.marshall()
        parcel.recycle()
        return Base64.encodeToString(bytes, Base64.DEFAULT)
    }
}
