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
    val wildMonsters: List<com.photomonster.model.Monster> = emptyList(), // ゲーム仕様: 野生モンスター
    val encounteringMonster: com.photomonster.model.Monster? = null, // ゲーム仕様: 遭遇中（捕獲画面）のモンスター
    val caughtMonsters: List<com.photomonster.model.Monster> = emptyList(), // ゲーム仕様: 捕獲済みのモンスター（手持ち/図鑑）
    val isBattleMode: Boolean = false, // ゲーム仕様: バトル画面表示フラグ
    val isLoading: Boolean = false,
    val skippedCount: Int = 0,
    val errorMessage: String? = null,
    val captureCubes: Int = 0 // ゲーム仕様: キャプチャキューブ（モンスター捕獲アイテム）の所持数
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
            val newMonsters = mutableListOf<com.photomonster.model.Monster>()
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
                    
                    // ゲーム仕様: 写真の周辺にEXIFをもとにモンスターを生成
                    val monster = generateMonster(exifData)
                    if (monster != null) {
                        newMonsters.add(monster)
                    }
                }
            }

            _uiState.update { current ->
                current.copy(
                    photos = current.photos + results,
                    wildMonsters = current.wildMonsters + newMonsters,
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

    /**
     * モンスターとの遭遇（マップでタップした時）
     */
    fun encounterMonster(monster: com.photomonster.model.Monster) {
        _uiState.update { it.copy(encounteringMonster = monster) }
    }

    /**
     * 捕獲画面から逃げる（マップに戻る）
     */
    fun fleeEncounter() {
        _uiState.update { it.copy(encounteringMonster = null) }
    }

    /**
     * 捕獲を試みる
     * @return 捕獲成功なら true
     */
    fun attemptCapture(): Boolean {
        val current = _uiState.value
        val monster = current.encounteringMonster ?: return false
        
        if (current.captureCubes <= 0) return false // アイテム不足

        // 捕獲確率計算 (仮: CPが低いほど捕まりやすい、ベース50%)
        val cp = monster.attack + monster.defense
        val catchRate = (80 - (cp / 5.0)).coerceIn(10.0, 100.0)
        val success = (Math.random() * 100) <= catchRate

        _uiState.update { state ->
            if (success) {
                state.copy(
                    captureCubes = state.captureCubes - 1,
                    encounteringMonster = null,
                    wildMonsters = state.wildMonsters.filter { it.id != monster.id }, // マップから消す
                    caughtMonsters = state.caughtMonsters + monster
                )
            } else {
                state.copy(captureCubes = state.captureCubes - 1)
            }
        }
        
        return success
    }

    /**
     * フォトスポット（写真ピン）からアイテムを回収する
     */
    fun collectItemFromSpot(photoId: Int) {
        _uiState.update { current ->
            val updatedPhotos = current.photos.map { photo ->
                if (photo.id == photoId && photo.canCollectItems) {
                    photo.copy(lastCollectedTime = System.currentTimeMillis())
                } else {
                    photo
                }
            }
            
            val isCollected = current.photos.any { it.id == photoId && it.canCollectItems }
            
            if (isCollected) {
                // キャプチャキューブを1〜3個ランダムで獲得
                val gainedCubes = (1..3).random()
                current.copy(
                    photos = updatedPhotos,
                    captureCubes = current.captureCubes + gainedCubes,
                    // 選択中のクラスターにも反映
                    selectedCluster = current.selectedCluster?.map { p -> 
                        if (p.id == photoId) p.copy(lastCollectedTime = System.currentTimeMillis()) else p 
                    }
                )
            } else {
                current
            }
        }
    }

    /**
     * バトル画面への遷移
     */
    fun enterBattleMode() {
        if (_uiState.value.caughtMonsters.size >= 1) {
            _uiState.update { it.copy(isBattleMode = true) }
        }
    }

    /**
     * バトル画面から戻る
     */
    fun exitBattleMode() {
        _uiState.update { it.copy(isBattleMode = false) }
    }

    fun clearPhotos() {
        _uiState.update { MapUiState() }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private data class ExifData(val latLng: LatLng?, val timestamp: String?, val aperture: Double?)

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
        return readExifFromUri(context, uri) ?: ExifData(null, null, null)
    }

    /** URI から InputStream を開いて ExifInterface で読み取る */
    private fun readExifFromUri(context: Context, uri: Uri): ExifData? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val latLng = exif.latLong?.let { LatLng(it[0], it[1]) }
                val timestamp = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                val aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0).let { if (it > 0) it else null }
                ExifData(latLng, timestamp, aperture)
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

    /**
     * EXIFデータからモンスターを生成するロジック
     */
    private fun generateMonster(exif: ExifData): com.photomonster.model.Monster? {
        if (exif.latLng == null) return null

        // 1. スポーン位置を写真の座標から少しずらす (ランダムに10m〜50m程度)
        val offsetLat = (Math.random() - 0.5) * 0.001
        val offsetLng = (Math.random() - 0.5) * 0.001
        val spawnLatLng = LatLng(exif.latLng.latitude + offsetLat, exif.latLng.longitude + offsetLng)

        // 2. 撮影時間帯で属性を決定
        val type = determineMonsterType(exif.timestamp)
        
        // 3. F値で個体値（ステータス）を決定
        // F値が小さい（ボケている）= 攻撃寄り、F値が大きい（パンフォーカス）= 防御寄り
        val fNumber = exif.aperture ?: 2.8 // 不明な場合は標準の2.8扱い
        val baseStat = 50
        val attackBonus = if (fNumber < 4.0) 20 else 0
        val defenseBonus = if (fNumber >= 8.0) 20 else 0

        val names = listOf("ピクセモン", "レンズスライム", "シャッタース", "フラッシュビー")
        val randomName = names.random()

        return com.photomonster.model.Monster(
            name = randomName,
            type = type,
            latLng = spawnLatLng,
            hp = 100 + (Math.random() * 20).toInt(),
            attack = baseStat + attackBonus + (Math.random() * 10).toInt(),
            defense = baseStat + defenseBonus + (Math.random() * 10).toInt()
        )
    }

    private fun determineMonsterType(timestamp: String?): com.photomonster.model.MonsterType {
        if (timestamp == null) return com.photomonster.model.MonsterType.NORMAL
        
        // "2025:01:15 14:30:00" 形式を想定して時(hour)を抽出
        val timePart = timestamp.substringAfter(" ")
        val hour = timePart.substringBefore(":").toIntOrNull() ?: 12

        return when (hour) {
            in 5..10 -> listOf(com.photomonster.model.MonsterType.LIGHT, com.photomonster.model.MonsterType.GRASS).random() // 朝
            in 11..16 -> listOf(com.photomonster.model.MonsterType.FIRE, com.photomonster.model.MonsterType.NORMAL).random() // 昼
            in 17..23, in 0..4 -> listOf(com.photomonster.model.MonsterType.DARK, com.photomonster.model.MonsterType.WATER).random() // 夜
            else -> com.photomonster.model.MonsterType.NORMAL
        }
    }
}
