package com.photomonster.viewmodel

import android.app.Application
import android.content.Context
import android.location.Geocoder
import android.net.Uri
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
    val selectedPhoto: PhotoLocation? = null,
    val isLoading: Boolean = false,
    val skippedCount: Int = 0,     // GPS なしでスキップした枚数
    val errorMessage: String? = null
)

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    /**
     * Photo Picker で選択した URI リストを処理する
     * ・EXIF から GPS を抽出
     * ・GPS なし写真はスキップカウントして除外
     * ・GPS あり写真は逆ジオコーディングで住所を付与
     */
    fun processSelectedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val context: Context = getApplication()
            val results = mutableListOf<PhotoLocation>()
            var skipped = 0

            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    val latLng = extractLatLng(context, uri)
                    if (latLng == null) {
                        skipped++
                        continue
                    }
                    val timestamp = extractTimestamp(context, uri)
                    val address = reverseGeocode(context, latLng)
                    results.add(
                        PhotoLocation(
                            id = uri.hashCode(),
                            uri = uri,
                            latLng = latLng,
                            timestamp = timestamp,
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
                    errorMessage = if (skipped > 0 && results.isEmpty())
                        "選択した写真に位置情報が含まれていません"
                    else null
                )
            }
        }
    }

    /** 写真を選択状態にする（マーカータップ or サムネイルタップ） */
    fun selectPhoto(photo: PhotoLocation?) {
        _uiState.update { it.copy(selectedPhoto = photo) }
    }

    /** 全写真をクリア */
    fun clearPhotos() {
        _uiState.update { MapUiState() }
    }

    /** スナックバーエラーを消去 */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /** ExifInterface で GPS 座標を取得する */
    private fun extractLatLng(context: Context, uri: Uri): LatLng? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val latLngArray = exif.latLong ?: return@use null
                LatLng(latLngArray[0], latLngArray[1])
            }
        } catch (e: Exception) {
            null
        }
    }

    /** EXIF から撮影日時文字列を取得する */
    private fun extractTimestamp(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: ExifInterface(stream).getAttribute(ExifInterface.TAG_DATETIME)
            }
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
                    // 都道府県〜番地を連結
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
