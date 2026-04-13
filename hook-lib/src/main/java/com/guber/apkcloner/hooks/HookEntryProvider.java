package com.guber.apkcloner.hooks;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

/**
 * ContentProvider that acts as the injection entry point for the signature-spoofing hook.
 *
 * Android starts ContentProviders before Application.onCreate(), making this the earliest
 * reliable hook point that doesn't require modifying the app's own Application class.
 *
 * The provider is injected into the clone's AndroidManifest.xml with:
 *   android:initOrder="Integer.MAX_VALUE"   — runs before any other provider
 *   android:exported="false"               — not accessible outside the process
 *   android:authorities="<newPkg>.cloner.hook"
 *
 * This class's fully-qualified name (com.guber.apkcloner.hooks.HookEntryProvider)
 * is referenced literally in ManifestPatcher.injectSpoofingNodes().
 */
public class HookEntryProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        try {
            SignatureSpoofing.install(getContext());
        } catch (Throwable t) {
            Log.e("SigSpoof", "HookEntryProvider.onCreate failed", t);
        }
        return true;
    }

    // ── Stub implementations of abstract ContentProvider methods ─────────────

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
