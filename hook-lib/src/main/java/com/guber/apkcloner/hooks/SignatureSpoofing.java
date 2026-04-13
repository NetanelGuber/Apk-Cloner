package com.guber.apkcloner.hooks;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Base64;
import android.util.Log;

import java.lang.reflect.Method;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

/**
 * Installs a Pine hook on PackageManager.getPackageInfo() that replaces the clone's signing
 * certificates with the original app's signing certificates in the returned PackageInfo.
 *
 * This prevents tamper-detection code that calls:
 *   pm.getPackageInfo(context.getPackageName(), GET_SIGNATURES)
 * from detecting that the APK was re-signed with a new key.
 *
 * The original certificates are stored as Base64-encoded Parcel bytes in meta-data entries
 * inside the clone's AndroidManifest.xml, injected by ManifestPatcher during cloning.
 */
public class SignatureSpoofing {

    private static final String TAG = "SigSpoof";
    private static final String META_ORIG_PKG   = "spoofing.originalPackageName";
    private static final String META_SIGS        = "spoofing.originalSignatures";
    private static final String META_SIGNING_INFO = "spoofing.originalSigningInfo";

    public static void install(Context context) {
        try {
            ApplicationInfo ai = context.getPackageManager()
                .getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            Bundle meta = ai.metaData;
            if (meta == null) return;

            String clonePkg   = context.getPackageName();
            String origPkg    = meta.getString(META_ORIG_PKG);
            String sigsB64    = meta.getString(META_SIGS);
            String sigInfoB64 = meta.getString(META_SIGNING_INFO);

            if (origPkg == null || sigsB64 == null) return;

            Signature[] origSigs = deserializeSigs(sigsB64);
            if (origSigs == null || origSigs.length == 0) return;

            SigningInfo origSigInfo = null;
            if (Build.VERSION.SDK_INT >= 28 && sigInfoB64 != null) {
                origSigInfo = deserializeSigningInfo(sigInfoB64);
            }

            hookGetPackageInfo(clonePkg, origSigs, origSigInfo);
            Log.i(TAG, "Hook installed for " + clonePkg + " → " + origPkg
                    + " (" + origSigs.length + " cert(s))");

        } catch (Throwable t) {
            Log.e(TAG, "install failed", t);
        }
    }

    // ── Hooking ───────────────────────────────────────────────────────────────

    private static void hookGetPackageInfo(
            final String clonePkg,
            final Signature[] origSigs,
            final SigningInfo origSigInfo) throws Exception {

        // Primary overload: getPackageInfo(String packageName, int flags)
        // Present on all API levels.
        Method m1 = Class.forName("android.app.ApplicationPackageManager")
            .getDeclaredMethod("getPackageInfo", String.class, int.class);

        Pine.hook(m1, new MethodHook() {
            @Override
            public void afterCall(Pine.CallFrame f) throws Throwable {
                PackageInfo pi = (PackageInfo) f.getResult();
                if (pi == null || !clonePkg.equals(pi.packageName)) return;
                pi.signatures = origSigs;
                if (Build.VERSION.SDK_INT >= 28 && origSigInfo != null) {
                    pi.signingInfo = origSigInfo;
                }
                Log.d(TAG, "getPackageInfo() intercepted — signatures replaced");
            }
        });

        // Secondary overload introduced in Android 13 (API 33):
        // getPackageInfo(String packageName, PackageManager.PackageInfoFlags flags)
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                Class<?> flagsClass = Class.forName(
                    "android.content.pm.PackageManager$PackageInfoFlags");
                Method m2 = Class.forName("android.app.ApplicationPackageManager")
                    .getDeclaredMethod("getPackageInfo", String.class, flagsClass);

                Pine.hook(m2, new MethodHook() {
                    @Override
                    public void afterCall(Pine.CallFrame f) throws Throwable {
                        PackageInfo pi = (PackageInfo) f.getResult();
                        if (pi == null || !clonePkg.equals(pi.packageName)) return;
                        pi.signatures = origSigs;
                        if (origSigInfo != null) pi.signingInfo = origSigInfo;
                        Log.d(TAG, "getPackageInfo() intercepted — signatures replaced");
                    }
                });
            } catch (Throwable ignored) {
                // API 33+ overload not found — primary hook is sufficient
            }
        }
    }

    // ── Deserialization ───────────────────────────────────────────────────────

    private static Signature[] deserializeSigs(String b64) {
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            Parcel p = Parcel.obtain();
            p.unmarshall(bytes, 0, bytes.length);
            p.setDataPosition(0);
            Parcelable[] arr = p.readParcelableArray(Signature.class.getClassLoader());
            p.recycle();
            if (arr == null) return null;
            Signature[] out = new Signature[arr.length];
            System.arraycopy(arr, 0, out, 0, arr.length);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("NewApi")
    private static SigningInfo deserializeSigningInfo(String b64) {
        if (b64 == null) return null;
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            Parcel p = Parcel.obtain();
            p.unmarshall(bytes, 0, bytes.length);
            p.setDataPosition(0);
            SigningInfo info = SigningInfo.CREATOR.createFromParcel(p);
            p.recycle();
            return info;
        } catch (Throwable t) {
            return null;
        }
    }
}
