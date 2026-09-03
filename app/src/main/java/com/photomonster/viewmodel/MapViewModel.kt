package com.photomonster.viewmodel

import android.app.Application
import android.content.Context
import android.location.Geocoder
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.photomonster.model.PhotoLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** アプリの UI 状態 */
data class MapUiState(
    val photos: List<PhotoLocation> = emptyList(),
    val selectedCluster: List<PhotoLocation>? = null,
    val isLoading: Boolean = false,
    val skippedCount: Int = 0,
    val errorMessage: String? = null
)

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    fun processSelectedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val context: Context = getApplication()
            val results = mutableListOf<PhotoLocation>()
            var skipped = 0

            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    // EXIF データを1回のストリームで一括取得
                    val exifData = readExifData(context, uri)
                    if (exifData.latLng == null) {
                        skipped++
                        continue
                    }
                    val address = reverseGeocode(context, exifData.latLng)
                    results.add(
                        PhotoLocation(
                            id = uri.hashCode(),
                            uri = uri,
                            latLng = exifData.latLng,
                            timestamp = exifData.timestamp,
                            address = address
                        )
                    )
                }
            }

            _uiState.update { current ->
                current.copy(
                    photos = current.photos + results,
                    isLoading = false,
                    skippedCount = current.skippedCount + skipped,
                    errorMessage = when {
                        skipped > 0 && results.isEmpty() ->
                            "選択した写真に位置情報が含まれていません（${skipped}枚スキップ）"
                        skipped > 0 ->
                            "${skipped}枚は位置情報なしのためスキップしました"
                        else -> null
                    }
                )
            }
        }
    }

    fun selectCluster(photos: List<PhotoLocation>?) {
        _uiState.update { it.copy(selectedCluster = photos) }
    }

    fun clearPhotos() {
        _uiState.update { MapUiState() }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private data class ExifData(val latLng: LatLng?, val timestamp: String?)

    /**
     * EXIF データを読み取る（GPS + 撮影日時を1回のストリームで取得）
     *
     * Android 10+ では MediaStore が GPS を自動的に隠す（プライバシー保護）。
     * [MediaStore.setRequireOriginal] で隠蔽を解除する。
     * ACCESS_MEDIA_LOCATION 権限が AndroidManifest.xml に必要。
     */
    private fun readExifData(context: Context, uri: Uri): ExifData {
        // setRequireOriginal で GPS の隠蔽を解除した URI を取得
        val unredactedUri: Uri = try {
            MediaStore.setRequireOriginal(uri)
        } catch (e: Exception) {
            uri  // クラウド写真など非対応の場合は元の URI を使用
        }

        // まず隠蔽解除 URI で試みる
        readExifFromUri(context, unredactedUri)?.let { return it }

        // 失敗した場合（SecurityException など）は元の URI でリトライ
        return readExifFromUri(context, uri) ?: ExifData(null, null)
    }

    /** URI から InputStream を開いて ExifInterface で読み取る */
    private fun readExifFromUri(context: Context, uri: Uri): ExifData? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val latLng = exif.latLong?.let { LatLng(it[0], it[1]) }
                val timestamp = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                ExifData(latLng, timestamp)
            }
        } catch (e: SecurityException) {
            // ACCESS_MEDIA_LOCATION 未許可時、または setRequireOriginal 非対応
            null
        } catch (e: Exception) {
            null
        }
    }

    /** Geocoder で LatLng → 住所文字列に変換する */
    private fun reverseGeocode(context: Context, latLng: LatLng): String? {
        return try {
            val geocoder = Geocoder(context, Locale.JAPANESE)
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
            addresses?.firstOrNull()?.let { address ->
                buildString {
                    for (i in 0..address.maxAddressLineIndex) {
                        if (isNotEmpty()) append(" ")
                        append(address.getAddressLine(i))
                    }
                }.ifBlank { null }
            }
        } catch (e: Exception) {
            null
        }
    }
}
