package com.guber.apkcloner.util

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tagName: String,
    val changelog: String,
    val apkUrl: String?
)

object UpdateChecker {

    private const val API_URL =
        "https://api.github.com/repos/NetanelGuber/Apk-Cloner/releases/latest"

    /** Fetches the latest GitHub release. Returns null on network error or if no release found. */
    fun fetchLatestRelease(): ReleaseInfo? {
        return try {
            val conn = URL(API_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.connect()
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            parseRelease(body)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRelease(json: String): ReleaseInfo? {
        return try {
            val obj = JSONObject(json)
            val tagName = obj.getString("tag_name")
            val changelog = obj.optString("body", "")
            val assets = obj.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.getString("browser_download_url")
                        break
                    }
                }
            }
            ReleaseInfo(tagName = tagName, changelog = changelog, apkUrl = apkUrl)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns true if [remoteTag] (e.g. "v0.4.0") is strictly newer than [currentVersion]
     * (e.g. "0.3.0"). Comparison is numeric per semver segment.
     */
    fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
        val remote = parseVersion(remoteTag)
        val current = parseVersion(currentVersion)
        return compareVersionTriple(remote, current) > 0
    }

    private fun parseVersion(version: String): Triple<Int, Int, Int> {
        val clean = version.trimStart('v', 'V')
        val parts = clean.split(".")
        return Triple(
            parts.getOrNull(0)?.toIntOrNull() ?: 0,
            parts.getOrNull(1)?.toIntOrNull() ?: 0,
            parts.getOrNull(2)?.toIntOrNull() ?: 0
        )
    }

    private fun compareVersionTriple(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
        val major = a.first.compareTo(b.first)
        if (major != 0) return major
        val minor = a.second.compareTo(b.second)
        if (minor != 0) return minor
        return a.third.compareTo(b.third)
    }
}
