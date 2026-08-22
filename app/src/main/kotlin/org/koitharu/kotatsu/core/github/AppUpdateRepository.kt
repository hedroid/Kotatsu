package org.koitharu.kotatsu.core.github

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.os.AppValidator
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.asArrayList
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.util.suspendlazy.getOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val CONTENT_TYPE_APK = "application/vnd.android.package-archive"
private const val BUILD_TYPE_RELEASE = "release"

@Singleton
class AppUpdateRepository @Inject constructor(
	private val appValidator: AppValidator,
	private val settings: AppSettings,
	@BaseHttpClient private val okHttp: OkHttpClient,
	@ApplicationContext context: Context,
) {

	private val availableUpdate = MutableStateFlow<AppVersion?>(null)
	private val updatesRepo = context.getString(R.string.github_updates_repo)
	private val releasesUrl = buildString {
		append("https://api.github.com/repos/")
		append(updatesRepo)
		append("/releases?page=1&per_page=10")
	}
	private val latestReleaseUrl = "https://github.com/$updatesRepo/releases/latest"

	val isUpdateAvailable: Boolean
		get() = availableUpdate.value != null

	fun observeAvailableUpdate() = availableUpdate.asStateFlow()

	suspend fun getAvailableVersions(): List<AppVersion> {
		android.util.Log.d("UPDATE_DEBUG", "=== Getting available versions from: $releasesUrl ===")
		val request = Request.Builder()
			.get()
			.url(releasesUrl)
		val jsonArray = okHttp.newCall(request.build()).await().parseJsonArray()
		android.util.Log.d("UPDATE_DEBUG", "GitHub API returned ${jsonArray.length()} releases")

		return jsonArray.mapJSONNotNull { json ->
			val releaseName = json.getString("name")
			val releaseTag = json.getString("tag_name")
			android.util.Log.d("UPDATE_DEBUG", "Processing release: '$releaseName' (tag: '$releaseTag')")

			val assets = json.optJSONArray("assets")
			android.util.Log.d("UPDATE_DEBUG", "  Assets found: ${assets?.length() ?: 0}")

			if (assets != null) {
				for (i in 0 until assets.length()) {
					val assetObj = assets.getJSONObject(i)
					val assetName = assetObj.optString("name", "unknown")
					val contentType = assetObj.optString("content_type", "unknown")
					android.util.Log.d("UPDATE_DEBUG", "    Asset $i: '$assetName' (content_type: '$contentType')")
				}
			}

			val asset = assets?.find { jo ->
				val contentType = jo.optString("content_type")
				val matches = contentType == CONTENT_TYPE_APK
				android.util.Log.d("UPDATE_DEBUG", "  Checking asset content_type: '$contentType' == '$CONTENT_TYPE_APK' -> $matches")
				matches
			}

			if (asset == null) {
				android.util.Log.d("UPDATE_DEBUG", "  No valid APK asset found for release '$releaseName'")
				return@mapJSONNotNull null
			}

			val versionName = releaseTag.removePrefix("v")
			android.util.Log.d("UPDATE_DEBUG", "  Creating AppVersion: name='$versionName' (from tag='$releaseTag'), versionId=${VersionId(versionName)}")

			AppVersion(
				id = json.getLong("id"),
				url = json.getString("html_url"),
				name = versionName,
				apkSize = asset.getLong("size"),
				apkUrl = asset.getString("browser_download_url"),
				description = json.getString("body"),
			)
		}
	}

	private suspend fun getLatestVersion(): AppVersion {
		val releaseResponse = okHttp.newCall(
			Request.Builder()
				.head()
				.url(latestReleaseUrl)
				.build(),
		).await()
		val releaseUrl = releaseResponse.use { response ->
			check(response.isSuccessful) { "GitHub latest release request failed: ${response.code}" }
			response.request.url
		}
		val tagPrefix = "/releases/tag/"
		val tag = releaseUrl.encodedPath.substringAfter(tagPrefix, "")
		check(tag.isNotEmpty()) { "Invalid GitHub latest release URL: $releaseUrl" }
		val versionName = tag.removePrefix("v")
		val apkUrl = "https://github.com/$updatesRepo/releases/download/$tag/Kotatsu-$versionName.apk"
		val apkSize = okHttp.newCall(
			Request.Builder()
				.head()
				.url(apkUrl)
				.build(),
		).await().use { response ->
			check(response.isSuccessful) { "GitHub release APK request failed: ${response.code}" }
			response.body.contentLength().coerceAtLeast(0L)
		}
		return AppVersion(
			id = versionName.hashCode().toLong(),
			url = releaseUrl.toString(),
			name = versionName,
			apkSize = apkSize,
			apkUrl = apkUrl,
			description = "",
		)
	}

	suspend fun checkForUpdate(): AppVersion? = withContext(Dispatchers.Default) {
		if (!isUpdateSupported()) {
			return@withContext null
		}
		val currentVersion = VersionId(BuildConfig.VERSION_NAME)
		val available = if (currentVersion.isStable && !settings.isUnstableUpdatesAllowed) {
			arrayListOf(getLatestVersion())
		} else {
			getAvailableVersions().asArrayList()
		}
		val result = available.maxByOrNull { it.versionId }
			?.takeIf { it.versionId > currentVersion }
		availableUpdate.value = result
		result
	}

	suspend fun fetchUpdate(): AppVersion? = runCatchingCancellable {
		checkForUpdate()
	}.onFailure {
			android.util.Log.e("UPDATE_DEBUG", "Error during update check", it)
			it.printStackTraceDebug()
	}.getOrNull()

	@Suppress("KotlinConstantConditions")
    fun isUpdateSupported(): Boolean {
		return true
	}

	private inline fun JSONArray.find(predicate: (JSONObject) -> Boolean): JSONObject? {
		val size = length()
		for (i in 0 until size) {
			val jo = getJSONObject(i)
			if (predicate(jo)) {
				return jo
			}
		}
		return null
	}
}
