package com.guber.apkcloner.util

import android.content.Context
import com.guber.apkcloner.engine.CloneSettings
import org.json.JSONObject
import java.io.File

class CloneSettingsRepository(private val context: Context) {

    private val dir = File(context.filesDir, "clone_settings")

    fun save(settings: CloneSettings) {
        dir.mkdirs()
        val json = JSONObject().apply {
            put("sourcePackageName", settings.sourcePackageName)
            put("newPackageName", settings.newPackageName)
            put("cloneLabel", settings.cloneLabel)
            put("deepClone", settings.deepClone)
            put("dualDex", settings.dualDex)
            put("patchNativeLibs", settings.patchNativeLibs)
            put("pkgShim", settings.pkgShim)
            put("overrideMinSdk", settings.overrideMinSdk ?: JSONObject.NULL)
            put("overrideTargetSdk", settings.overrideTargetSdk ?: JSONObject.NULL)
            put("iconHue", settings.iconHue.toDouble())
            put("iconSaturation", settings.iconSaturation.toDouble())
            put("iconContrast", settings.iconContrast.toDouble())
            put("saveToStorage", settings.saveToStorage)
            put("installAfterBuild", settings.installAfterBuild)
            put("saveLocationUri", settings.saveLocationUri ?: JSONObject.NULL)
        }
        File(dir, "${settings.newPackageName}.json").writeText(json.toString())
    }

    fun load(packageName: String): CloneSettings? {
        val file = File(dir, "$packageName.json")
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            CloneSettings(
                sourcePackageName = json.getString("sourcePackageName"),
                newPackageName = json.getString("newPackageName"),
                cloneLabel = json.getString("cloneLabel"),
                deepClone = json.getBoolean("deepClone"),
                dualDex = json.getBoolean("dualDex"),
                patchNativeLibs = json.getBoolean("patchNativeLibs"),
                pkgShim = json.getBoolean("pkgShim"),
                overrideMinSdk = if (json.isNull("overrideMinSdk")) null else json.getInt("overrideMinSdk"),
                overrideTargetSdk = if (json.isNull("overrideTargetSdk")) null else json.getInt("overrideTargetSdk"),
                iconHue = json.getDouble("iconHue").toFloat(),
                iconSaturation = json.getDouble("iconSaturation").toFloat(),
                iconContrast = json.getDouble("iconContrast").toFloat(),
                saveToStorage = json.getBoolean("saveToStorage"),
                installAfterBuild = json.getBoolean("installAfterBuild"),
                saveLocationUri = if (json.isNull("saveLocationUri")) null else json.getString("saveLocationUri")
            )
        } catch (_: Exception) {
            null
        }
    }
}
