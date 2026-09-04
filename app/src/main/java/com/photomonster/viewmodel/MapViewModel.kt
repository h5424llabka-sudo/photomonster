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
import com.photomonster.data.GameRepository
import com.photomonster.model.Monster
import com.photomonster.model.MonsterType
import com.photomonster.model.PhotoLocation
import com.photomonster.model.PhotoSpot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.*

// ─── バトル状態 ────────────────────────────────────────────────────────────────

enum class BattleResult { NONE, WIN, LOSE }

data class BattleState(
    val playerParty: List<Monster> = emptyList(),
    val enemyParty: List<Monster> = emptyList(),
    val activePlayerIndex: Int = 0,
    val activeEnemyIndex: Int = 0,
    val playerCurrentHp: Int = 0,
    val enemyCurrentHp: Int = 0,
    val playerGauge: Float = 0f,
    val battleLog: String = "バトル開始！",
    val result: BattleResult = BattleResult.NONE
)

// ─── UI 状態 ──────────────────────────────────────────────────────────────────

data class MapUiState(
    val photos: List<PhotoLocation> = emptyList(),
    val photoSpots: List<PhotoSpot> = emptyList(),
    val selectedSpotId: String? = null,          // 選択中スポットのID
    val huntingSpotId: String? = null,            // モンスター探索モードのスポットID
    val wildMonsters: List<Monster> = emptyList(),
    val encounteringMonster: Monster? = null,
    val caughtMonsters: List<Monster> = emptyList(),
    val isBattleMode: Boolean = false,
    val battleState: BattleState = BattleState(),
    val isLoading: Boolean = false,
    val skippedCount: Int = 0,
    val errorMessage: String? = null,
    val captureCubes: Int = 0
) {
    /** 選択中のスポット（IDから引き直し、常に最新データを参照） */
    val selectedSpot: PhotoSpot? get() = photoSpots.firstOrNull { it.id == selectedSpotId }

    /** 探索中スポット */
    val huntingSpot: PhotoSpot? get() = photoSpots.firstOrNull { it.id == huntingSpotId }

    /** 探索中スポットに紐づくモンスター */
    val huntingMonsters: List<Monster> get() =
        if (huntingSpotId != null) wildMonsters.filter { it.spotId == huntingSpotId }
        else emptyList()
}

// ─── ViewModel ────────────────────────────────────────────────────────────────

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = GameRepository(application)
    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    companion object {
        const val SPOT_RADIUS_METERS = 200.0
    }

    /** 起動時にセーブデータを読み込む */
    init {
        viewModelScope.launch {
            try {
                val photos  = withContext(Dispatchers.IO) { repository.photos.first() }
                val wild    = withContext(Dispatchers.IO) { repository.wildMonsters.first() }
                val caught  = withContext(Dispatchers.IO) { repository.caughtMonsters.first() }
                val cubes   = withContext(Dispatchers.IO) { repository.captureCubes.first() }
                val spots   = buildPhotoSpots(photos)
                _uiState.update {
                    it.copy(
                        photos = photos, photoSpots = spots,
                        wildMonsters = wild, caughtMonsters = caught, captureCubes = cubes
                    )
                }
            } catch (_: Exception) { /* 空の状態で起動 */ }
        }
    }

    // ─── 写真処理 ──────────────────────────────────────────────────────────────

    fun processSelectedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val context: Context = getApplication()
            val newPhotos   = mutableListOf<PhotoLocation>()
            val newMonsters = mutableListOf<Monster>()
            var skipped     = 0

            // URI文字列で重複チェック（安定）
            val existingUris = _uiState.value.photos.map { it.uriString }.toHashSet()

            for (batch in uris.chunked(5)) {
                withContext(Dispatchers.IO) {
                    for (uri in batch) {
                        if (uri.toString() in existingUris) continue
                        try {
                            val exif = readExifSafely(context, uri)
                            if (exif.latLng == null) { skipped++; continue }

                            val address = try { reverseGeocode(context, exif.latLng) } catch (_: Exception) { null }
                            val photo = PhotoLocation(
                                id    = uri.toString().hashCode(), // URI文字列ベースの安定ID
                                uri   = uri,
                                latLng = exif.latLng,
                                timestamp = exif.timestamp,
                                address   = address
                            )
                            newPhotos.add(photo)
                            existingUris.add(uri.toString())

                            // モンスター生成（spotIdは後でスポット構築時に設定）
                            generateMonsterRaw(exif)?.let { newMonsters.add(it) }

                        } catch (_: Exception) { skipped++ }
                    }
                }

                if (newPhotos.isNotEmpty()) {
                    val merged = _uiState.value.photos + newPhotos
                    val spots  = buildPhotoSpots(merged)

                    // モンスターのspotIdをスポットIDに紐付け
                    val taggedMonsters = tagMonstersWithSpots(newMonsters, spots)

                    _uiState.update { cur ->
                        cur.copy(
                            photos = merged,
                            photoSpots = spots,
                            wildMonsters = cur.wildMonsters + taggedMonsters
                        )
                    }
                    repository.savePhotos(_uiState.value.photos)
                    repository.saveWildMonsters(_uiState.value.wildMonsters)
                }
            }

            _uiState.update { cur ->
                cur.copy(
                    isLoading = false,
                    skippedCount = cur.skippedCount + skipped,
                    errorMessage = when {
                        skipped > 0 && newPhotos.isEmpty() ->
                            "選択した写真に位置情報が含まれていません（${skipped}枚スキップ）"
                        skipped > 0 -> "${skipped}枚は位置情報なしのためスキップしました"
                        else -> null
                    }
                )
            }
        }
    }

    /** 各モンスターに最寄りのスポットIDを付与する */
    private fun tagMonstersWithSpots(monsters: List<Monster>, spots: List<PhotoSpot>): List<Monster> {
        return monsters.map { monster ->
            val nearestSpot = spots.minByOrNull { spot ->
                distanceMeters(spot.centerLatLng, monster.latLng)
            }
            if (nearestSpot != null) monster.copy(spotId = nearestSpot.id) else monster
        }
    }

    // ─── PhotoSpot 構築（200m固定クラスタリング） ───────────────────────────────

    private fun buildPhotoSpots(photos: List<PhotoLocation>): List<PhotoSpot> {
        val groups = mutableListOf<MutableList<PhotoLocation>>()
        for (photo in photos) {
            val nearest = groups.firstOrNull { group ->
                distanceMeters(groupCenter(group), photo.latLng) <= SPOT_RADIUS_METERS
            }
            if (nearest != null) nearest.add(photo) else groups.add(mutableListOf(photo))
        }
        return groups.mapIndexed { i, group ->
            PhotoSpot(id = "spot_$i", centerLatLng = groupCenter(group), photos = group.toList())
        }
    }

    private fun groupCenter(photos: List<PhotoLocation>): LatLng {
        val lat = photos.sumOf { it.latLng.latitude }  / photos.size
        val lng = photos.sumOf { it.latLng.longitude } / photos.size
        return LatLng(lat, lng)
    }

    /** Haversine公式（外部ライブラリ不要） */
    private fun distanceMeters(a: LatLng, b: LatLng): Double {
        val r = 6371000.0
        val lat1 = Math.toRadians(a.latitude);  val lat2 = Math.toRadians(b.latitude)
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    // ─── スポット選択 ──────────────────────────────────────────────────────────

    fun selectSpot(spot: PhotoSpot?) {
        _uiState.update { it.copy(selectedSpotId = spot?.id) }
    }

    // ─── モンスター探索モード ──────────────────────────────────────────────────

    fun enterHuntMode(spot: PhotoSpot) {
        _uiState.update { it.copy(selectedSpotId = null, huntingSpotId = spot.id) }
    }

    fun exitHuntMode() {
        _uiState.update { it.copy(huntingSpotId = null) }
    }

    // ─── アイテム回収 ──────────────────────────────────────────────────────────

    fun collectItemFromSpot(photoId: Int) {
        viewModelScope.launch {
            val cur = _uiState.value
            val target = cur.photos.firstOrNull { it.id == photoId } ?: return@launch
            if (!target.canCollectItems) return@launch

            val gained = (1..3).random()
            val now = System.currentTimeMillis()
            val updatedPhotos = cur.photos.map { p ->
                if (p.id == photoId) p.copy(lastCollectedTime = now) else p
            }
            val updatedSpots = buildPhotoSpots(updatedPhotos)

            _uiState.update { state ->
                state.copy(
                    photos = updatedPhotos,
                    photoSpots = updatedSpots,
                    captureCubes = state.captureCubes + gained
                    // selectedSpotId はそのまま保持 → selectedSpot computed property が最新データを自動参照
                )
            }
            repository.savePhotos(_uiState.value.photos)
            repository.saveCaptureCubes(_uiState.value.captureCubes)
        }
    }

    // ─── モンスター遭遇・捕獲 ──────────────────────────────────────────────────

    fun encounterMonster(monster: Monster) {
        _uiState.update { it.copy(encounteringMonster = monster) }
    }

    fun fleeEncounter() {
        _uiState.update { it.copy(encounteringMonster = null) }
    }

    fun attemptCapture(): Boolean {
        val cur = _uiState.value
        val monster = cur.encounteringMonster ?: return false
        if (cur.captureCubes <= 0) return false

        val catchRate = (80 - ((monster.attack + monster.defense) / 5.0)).coerceIn(10.0, 90.0)
        val success   = (Math.random() * 100) <= catchRate

        viewModelScope.launch {
            _uiState.update { state ->
                if (success) state.copy(
                    captureCubes     = state.captureCubes - 1,
                    encounteringMonster = null,
                    wildMonsters     = state.wildMonsters.filter { it.id != monster.id },
                    caughtMonsters   = state.caughtMonsters + monster
                ) else state.copy(captureCubes = state.captureCubes - 1)
            }
            repository.saveCaptureCubes(_uiState.value.captureCubes)
            if (success) {
                repository.saveCaughtMonsters(_uiState.value.caughtMonsters)
                repository.saveWildMonsters(_uiState.value.wildMonsters)
            }
        }
        return success
    }

    // ─── バトル ────────────────────────────────────────────────────────────────

    fun enterBattleMode() {
        val caught = _uiState.value.caughtMonsters
        if (caught.isEmpty()) return

        val enemyParty = List(minOf(3, caught.size + 1)) {
            val t = MonsterType.values().random()
            Monster(
                name = listOf("ヤミキング","フレアゴン","アクアドン","リーフボス","ライトリオン").random(),
                type = t, latLng = LatLng(0.0, 0.0),
                hp = 120 + (Math.random() * 60).toInt(),
                attack = 55 + (Math.random() * 20).toInt(),
                defense = 45 + (Math.random() * 20).toInt()
            )
        }

        _uiState.update { it.copy(
            isBattleMode = true,
            battleState = BattleState(
                playerParty = caught, enemyParty = enemyParty,
                playerCurrentHp = caught[0].hp, enemyCurrentHp = enemyParty[0].hp
            )
        )}
        startBattleLoop()
    }

    private fun startBattleLoop() {
        viewModelScope.launch {
            while (true) {
                if (_uiState.value.battleState.result != BattleResult.NONE) break
                delay(2000)

                val b = _uiState.value.battleState
                if (b.result != BattleResult.NONE) break

                val player = b.playerParty.getOrNull(b.activePlayerIndex) ?: break
                val enemy  = b.enemyParty.getOrNull(b.activeEnemyIndex)  ?: break

                val dmgE = maxOf(1, player.attack - enemy.defense / 2)
                val newEHp = b.enemyCurrentHp - dmgE
                val newGauge = (b.playerGauge + 0.25f).coerceAtMost(1f)

                if (newEHp <= 0) {
                    val nextEIdx = b.activeEnemyIndex + 1
                    if (nextEIdx >= b.enemyParty.size) {
                        _uiState.update { s -> s.copy(battleState = s.battleState.copy(
                            enemyCurrentHp = 0, playerGauge = newGauge,
                            battleLog = "全員倒した！勝利！🎉", result = BattleResult.WIN
                        ))}; break
                    } else {
                        val ne = b.enemyParty[nextEIdx]
                        _uiState.update { s -> s.copy(battleState = s.battleState.copy(
                            activeEnemyIndex = nextEIdx, enemyCurrentHp = ne.hp,
                            playerGauge = newGauge, battleLog = "${enemy.name} を倒した！次は ${ne.name}！"
                        ))}; delay(1200); continue
                    }
                } else {
                    _uiState.update { s -> s.copy(battleState = s.battleState.copy(
                        enemyCurrentHp = newEHp, playerGauge = newGauge,
                        battleLog = "${player.name} の攻撃！ $dmgE ダメージ！"
                    ))}
                }

                delay(1000)
                val b2 = _uiState.value.battleState
                if (b2.result != BattleResult.NONE) break

                val e2 = b2.enemyParty.getOrNull(b2.activeEnemyIndex)  ?: break
                val p2 = b2.playerParty.getOrNull(b2.activePlayerIndex) ?: break
                val dmgP = maxOf(1, e2.attack - p2.defense / 2)
                val newPHp = b2.playerCurrentHp - dmgP

                if (newPHp <= 0) {
                    val nextPIdx = b2.playerParty.indices.firstOrNull { it > b2.activePlayerIndex }
                    if (nextPIdx == null) {
                        _uiState.update { s -> s.copy(battleState = s.battleState.copy(
                            playerCurrentHp = 0, battleLog = "全員倒れた...敗北...", result = BattleResult.LOSE
                        ))}; break
                    } else {
                        val np = b2.playerParty[nextPIdx]
                        _uiState.update { s -> s.copy(battleState = s.battleState.copy(
                            activePlayerIndex = nextPIdx, playerCurrentHp = np.hp,
                            playerGauge = 0f, battleLog = "${p2.name} が倒れた！${np.name} が登場！"
                        ))}; delay(1200); continue
                    }
                } else {
                    _uiState.update { s -> s.copy(battleState = s.battleState.copy(
                        playerCurrentHp = newPHp, battleLog = "${e2.name} の攻撃！ $dmgP ダメージ！"
                    ))}
                }
            }
        }
    }

    fun activateSkill() {
        val b = _uiState.value.battleState
        if (b.playerGauge < 1f || b.result != BattleResult.NONE) return
        val player = b.playerParty.getOrNull(b.activePlayerIndex) ?: return
        val dmg = player.attack * 2
        val newEHp = b.enemyCurrentHp - dmg
        if (newEHp <= 0) {
            val nextIdx = b.activeEnemyIndex + 1
            if (nextIdx >= b.enemyParty.size) {
                _uiState.update { s -> s.copy(battleState = s.battleState.copy(
                    enemyCurrentHp = 0, playerGauge = 0f,
                    battleLog = "${player.name} の必殺技！勝利！🎉", result = BattleResult.WIN
                ))}
            } else {
                val ne = b.enemyParty[nextIdx]
                _uiState.update { s -> s.copy(battleState = s.battleState.copy(
                    activeEnemyIndex = nextIdx, enemyCurrentHp = ne.hp, playerGauge = 0f,
                    battleLog = "${player.name} の必殺技！${b.enemyParty[b.activeEnemyIndex].name} を倒した！"
                ))}
            }
        } else {
            _uiState.update { s -> s.copy(battleState = s.battleState.copy(
                enemyCurrentHp = newEHp, playerGauge = 0f,
                battleLog = "${player.name} の必殺技！ $dmg ダメージ！"
            ))}
        }
    }

    fun switchPlayer(newIndex: Int) {
        val np = _uiState.value.battleState.playerParty.getOrNull(newIndex) ?: return
        _uiState.update { s -> s.copy(battleState = s.battleState.copy(
            activePlayerIndex = newIndex, playerCurrentHp = np.hp,
            playerGauge = 0f, battleLog = "${np.name} に交代した！"
        ))}
    }

    fun exitBattleMode() {
        _uiState.update { it.copy(isBattleMode = false, battleState = BattleState()) }
    }

    // ─── その他 ────────────────────────────────────────────────────────────────

    fun clearPhotos() {
        viewModelScope.launch {
            _uiState.update { MapUiState() }
            repository.clearAll()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // ─── EXIF ────────────────────────────────────────────────────────────────

    private data class ExifData(val latLng: LatLng?, val timestamp: String?, val aperture: Double?)

    private fun readExifSafely(context: Context, uri: Uri): ExifData {
        val unredacted = try { MediaStore.setRequireOriginal(uri) } catch (_: Exception) { uri }
        return tryReadExif(context, unredacted) ?: tryReadExif(context, uri) ?: ExifData(null, null, null)
    }

    private fun tryReadExif(context: Context, uri: Uri): ExifData? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            ExifData(
                latLng    = exif.latLong?.let { LatLng(it[0], it[1]) },
                timestamp = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME),
                aperture  = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0).takeIf { it > 0 }
            )
        }
    } catch (_: Exception) { null }

    private fun reverseGeocode(context: Context, latLng: LatLng): String? = try {
        val geocoder = Geocoder(context, Locale.JAPANESE)
        @Suppress("DEPRECATION")
        geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
            ?.firstOrNull()
            ?.let { addr ->
                buildString {
                    for (i in 0..addr.maxAddressLineIndex) {
                        if (isNotEmpty()) append(" ")
                        append(addr.getAddressLine(i))
                    }
                }.ifBlank { null }
            }
    } catch (_: Exception) { null }

    // ─── モンスター生成（spotIdなし版）────────────────────────────────────────

    private fun generateMonsterRaw(exif: ExifData): Monster? {
        val latLng = exif.latLng ?: return null
        val offset = { (Math.random() - 0.5) * 0.001 }
        val type   = exif.timestamp?.let { ts ->
            val h = ts.substringAfter(" ").substringBefore(":").toIntOrNull() ?: 12
            when (h) {
                in 5..10  -> listOf(MonsterType.LIGHT, MonsterType.GRASS).random()
                in 11..16 -> listOf(MonsterType.FIRE,  MonsterType.NORMAL).random()
                else      -> listOf(MonsterType.DARK,  MonsterType.WATER).random()
            }
        } ?: MonsterType.NORMAL
        val f   = exif.aperture ?: 2.8
        val atk = 50 + (if (f < 4.0) 20 else 0) + (Math.random() * 10).toInt()
        val def = 50 + (if (f >= 8.0) 20 else 0) + (Math.random() * 10).toInt()
        return Monster(
            name = listOf("ピクセモン","レンズスライム","シャッタース","フラッシュビー","ミラードン","ズームゴン").random(),
            type = type,
            latLng = LatLng(latLng.latitude + offset(), latLng.longitude + offset()),
            hp = 80 + (Math.random() * 40).toInt(),
            attack = atk, defense = def
        )
    }
}
