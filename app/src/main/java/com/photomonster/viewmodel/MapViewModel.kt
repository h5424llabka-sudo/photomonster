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
import java.util.UUID
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
    val photoSpots: List<PhotoSpot> = emptyList(),   // 200m以内でグループ化したスポット
    val selectedSpot: PhotoSpot? = null,
    val wildMonsters: List<Monster> = emptyList(),
    val encounteringMonster: Monster? = null,
    val caughtMonsters: List<Monster> = emptyList(),
    val isBattleMode: Boolean = false,
    val battleState: BattleState = BattleState(),
    val isLoading: Boolean = false,
    val skippedCount: Int = 0,
    val errorMessage: String? = null,
    val captureCubes: Int = 0
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = GameRepository(application)
    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    companion object {
        /** スポット統合の半径 (メートル) */
        const val SPOT_RADIUS_METERS = 200.0
    }

    /** 起動時にセーブデータを読み込む */
    init {
        viewModelScope.launch {
            try {
                val photos = withContext(Dispatchers.IO) { repository.photos.first() }
                val wild   = withContext(Dispatchers.IO) { repository.wildMonsters.first() }
                val caught = withContext(Dispatchers.IO) { repository.caughtMonsters.first() }
                val cubes  = withContext(Dispatchers.IO) { repository.captureCubes.first() }
                val spots  = buildPhotoSpots(photos)
                _uiState.update {
                    it.copy(
                        photos = photos,
                        photoSpots = spots,
                        wildMonsters = wild,
                        caughtMonsters = caught,
                        captureCubes = cubes
                    )
                }
            } catch (e: Exception) {
                // セーブデータ読み込み失敗は無視して空の状態で起動
            }
        }
    }

    // ─── 写真処理（バッチ、クラッシュ対策） ──────────────────────────────────────

    fun processSelectedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val context: Context = getApplication()
            val newPhotos = mutableListOf<PhotoLocation>()
            val newMonsters = mutableListOf<Monster>()
            var skipped = 0

            // 既存URIの重複チェック用
            val existingUriStrings = _uiState.value.photos.map { it.uri.toString() }.toHashSet()

            // バッチ処理: 5枚ずつ処理（Geocoderの負荷を抑える）
            for (batch in uris.chunked(5)) {
                withContext(Dispatchers.IO) {
                    for (uri in batch) {
                        // 重複スキップ
                        if (uri.toString() in existingUriStrings) continue

                        try {
                            val exif = readExifSafely(context, uri)
                            if (exif.latLng == null) {
                                skipped++
                                continue
                            }

                            val address = try { reverseGeocode(context, exif.latLng) } catch (e: Exception) { null }

                            newPhotos.add(
                                PhotoLocation(
                                    id = System.nanoTime().toInt() xor uri.hashCode(),
                                    uri = uri,
                                    latLng = exif.latLng,
                                    timestamp = exif.timestamp,
                                    address = address
                                )
                            )
                            existingUriStrings.add(uri.toString())

                            generateMonster(exif)?.let { newMonsters.add(it) }

                        } catch (e: Exception) {
                            skipped++
                        }
                    }
                }

                // バッチごとに中間更新
                if (newPhotos.isNotEmpty()) {
                    val mergedPhotos = _uiState.value.photos + newPhotos
                    val mergedSpots = buildPhotoSpots(mergedPhotos)
                    _uiState.update { current ->
                        current.copy(
                            photos = mergedPhotos,
                            photoSpots = mergedSpots,
                            wildMonsters = current.wildMonsters + newMonsters
                        )
                    }
                    // 中間状態でも保存
                    repository.savePhotos(_uiState.value.photos)
                    repository.saveWildMonsters(_uiState.value.wildMonsters)
                }
            }

            _uiState.update { current ->
                current.copy(
                    isLoading = false,
                    skippedCount = current.skippedCount + skipped,
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

    // ─── PhotoSpot 構築（独自クラスタリング、200m固定） ──────────────────────────

    /**
     * PhotoLocationのリストを受け取り、200m以内のものを同じPhotoSpotにまとめる
     * ズームレベルに関係なく常に同じグループになる
     */
    private fun buildPhotoSpots(photos: List<PhotoLocation>): List<PhotoSpot> {
        val spots = mutableListOf<MutableList<PhotoLocation>>()

        for (photo in photos) {
            // 既存スポットの中で、最も近いものを探す
            val nearestSpot = spots.firstOrNull { group ->
                val center = groupCenter(group)
                distanceMeters(center, photo.latLng) <= SPOT_RADIUS_METERS
            }

            if (nearestSpot != null) {
                nearestSpot.add(photo)
            } else {
                spots.add(mutableListOf(photo))
            }
        }

        return spots.mapIndexed { index, group ->
            val center = groupCenter(group)
            PhotoSpot(
                id = "spot_$index",
                centerLatLng = center,
                photos = group.toList()
            )
        }
    }

    /** グループの重心座標を計算 */
    private fun groupCenter(photos: List<PhotoLocation>): LatLng {
        val lat = photos.sumOf { it.latLng.latitude } / photos.size
        val lng = photos.sumOf { it.latLng.longitude } / photos.size
        return LatLng(lat, lng)
    }

    /**
     * Haversine公式による2点間の距離計算（メートル）
     * SphericalUtilに依存しない独自実装
     */
    private fun distanceMeters(a: LatLng, b: LatLng): Double {
        val r = 6371000.0 // 地球の半径 (m)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    // ─── スポット選択 ──────────────────────────────────────────────────────────

    fun selectSpot(spot: PhotoSpot?) {
        _uiState.update { it.copy(selectedSpot = spot) }
    }

    // ─── アイテム回収 ──────────────────────────────────────────────────────────

    fun collectItemFromSpot(photoId: Int) {
        viewModelScope.launch {
            val current = _uiState.value
            val target = current.photos.firstOrNull { it.id == photoId } ?: return@launch
            if (!target.canCollectItems) return@launch

            val gained = (1..3).random()
            val updatedPhotos = current.photos.map { p ->
                if (p.id == photoId) p.copy(lastCollectedTime = System.currentTimeMillis()) else p
            }
            val updatedSpots = buildPhotoSpots(updatedPhotos)

            _uiState.update { state ->
                state.copy(
                    photos = updatedPhotos,
                    photoSpots = updatedSpots,
                    captureCubes = state.captureCubes + gained,
                    selectedSpot = state.selectedSpot?.let { spot ->
                        spot.copy(photos = spot.photos.map { p ->
                            if (p.id == photoId) p.copy(lastCollectedTime = System.currentTimeMillis()) else p
                        })
                    }
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
        val current = _uiState.value
        val monster = current.encounteringMonster ?: return false
        if (current.captureCubes <= 0) return false

        val catchRate = (80 - ((monster.attack + monster.defense) / 5.0)).coerceIn(10.0, 90.0)
        val success = (Math.random() * 100) <= catchRate

        viewModelScope.launch {
            _uiState.update { state ->
                if (success) {
                    state.copy(
                        captureCubes = state.captureCubes - 1,
                        encounteringMonster = null,
                        wildMonsters = state.wildMonsters.filter { it.id != monster.id },
                        caughtMonsters = state.caughtMonsters + monster
                    )
                } else {
                    state.copy(captureCubes = state.captureCubes - 1)
                }
            }
            repository.saveCaptureCubes(_uiState.value.captureCubes)
            if (success) {
                repository.saveCaughtMonsters(_uiState.value.caughtMonsters)
                repository.saveWildMonsters(_uiState.value.wildMonsters)
            }
        }
        return success
    }

    // ─── バトルシステム ────────────────────────────────────────────────────────

    fun enterBattleMode() {
        val caught = _uiState.value.caughtMonsters
        if (caught.isEmpty()) return

        val enemyParty = List(minOf(3, caught.size + 1)) {
            val randomType = MonsterType.values().random()
            Monster(
                name = listOf("ヤミキング", "フレアゴン", "アクアドン", "リーフボス", "ライトリオン").random(),
                type = randomType,
                latLng = LatLng(0.0, 0.0),
                hp = 120 + (Math.random() * 60).toInt(),
                attack = 55 + (Math.random() * 20).toInt(),
                defense = 45 + (Math.random() * 20).toInt()
            )
        }

        val initialBattle = BattleState(
            playerParty = caught,
            enemyParty = enemyParty,
            activePlayerIndex = 0,
            activeEnemyIndex = 0,
            playerCurrentHp = caught[0].hp,
            enemyCurrentHp = enemyParty[0].hp,
            playerGauge = 0f,
            battleLog = "バトル開始！",
            result = BattleResult.NONE
        )

        _uiState.update { it.copy(isBattleMode = true, battleState = initialBattle) }
        startBattleLoop()
    }

    private fun startBattleLoop() {
        viewModelScope.launch {
            while (true) {
                val battle = _uiState.value.battleState
                if (battle.result != BattleResult.NONE) break

                delay(2000)

                val b = _uiState.value.battleState
                if (b.result != BattleResult.NONE) break

                val player = b.playerParty.getOrNull(b.activePlayerIndex) ?: break
                val enemy  = b.enemyParty.getOrNull(b.activeEnemyIndex)  ?: break

                // プレイヤー攻撃
                val dmgToEnemy = maxOf(1, player.attack - enemy.defense / 2)
                val newEnemyHp = b.enemyCurrentHp - dmgToEnemy
                val newGauge   = (b.playerGauge + 0.25f).coerceAtMost(1f)

                if (newEnemyHp <= 0) {
                    val nextEnemyIdx = b.activeEnemyIndex + 1
                    if (nextEnemyIdx >= b.enemyParty.size) {
                        _uiState.update { s -> s.copy(battleState = s.battleState.copy(
                            enemyCurrentHp = 0, playerGauge = newGauge,
                            battleLog = "全員倒した！勝利！🎉", result = BattleResult.WIN
                        ))}
                        break
                    } else {
                        val nextEnemy = b.enemyParty[nextEnemyIdx]
                        _uiState.update { s -> s.copy(battleState = s.battleState.copy(
                            activeEnemyIndex = nextEnemyIdx, enemyCurrentHp = nextEnemy.hp,
                            playerGauge = newGauge,
                            battleLog = "${enemy.name} を倒した！次は ${nextEnemy.name}！"
                        ))}
                        delay(1200)
                        continue
                    }
                } else {
                    _uiState.update { s -> s.copy(battleState = s.battleState.copy(
                        enemyCurrentHp = newEnemyHp, playerGauge = newGauge,
                        battleLog = "${player.name} の攻撃！ $dmgToEnemy ダメージ！"
                    ))}
                }

                delay(1000)

                val b2 = _uiState.value.battleState
                if (b2.result != BattleResult.NONE) break

                val enemy2  = b2.enemyParty.getOrNull(b2.activeEnemyIndex)  ?: break
                val player2 = b2.playerParty.getOrNull(b2.activePlayerIndex) ?: break

                // 敵攻撃
                val dmgToPlayer = maxOf(1, enemy2.attack - player2.defense / 2)
                val newPlayerHp = b2.playerCurrentHp - dmgToPlayer

                if (newPlayerHp <= 0) {
                    val nextPlayerIdx = b2.playerParty.indices.firstOrNull { it > b2.activePlayerIndex }
                    if (nextPlayerIdx == null) {
                        _uiState.update { s -> s.copy(battleState = s.battleState.copy(
                            playerCurrentHp = 0,
                            battleLog = "全員倒れた...敗北...", result = BattleResult.LOSE
                        ))}
                        break
                    } else {
                        val nextPlayer = b2.playerParty[nextPlayerIdx]
                        _uiState.update { s -> s.copy(battleState = s.battleState.copy(
                            activePlayerIndex = nextPlayerIdx, playerCurrentHp = nextPlayer.hp,
                            playerGauge = 0f,
                            battleLog = "${player2.name} が倒れた！${nextPlayer.name} が登場！"
                        ))}
                        delay(1200)
                        continue
                    }
                } else {
                    _uiState.update { s -> s.copy(battleState = s.battleState.copy(
                        playerCurrentHp = newPlayerHp,
                        battleLog = "${enemy2.name} の攻撃！ $dmgToPlayer ダメージ！"
                    ))}
                }
            }
        }
    }

    fun activateSkill() {
        val battle = _uiState.value.battleState
        if (battle.playerGauge < 1f || battle.result != BattleResult.NONE) return

        val player = battle.playerParty.getOrNull(battle.activePlayerIndex) ?: return
        val dmg = player.attack * 2
        val newEnemyHp = battle.enemyCurrentHp - dmg

        if (newEnemyHp <= 0) {
            val nextIdx = battle.activeEnemyIndex + 1
            if (nextIdx >= battle.enemyParty.size) {
                _uiState.update { s -> s.copy(battleState = s.battleState.copy(
                    enemyCurrentHp = 0, playerGauge = 0f,
                    battleLog = "${player.name} の必殺技！勝利！🎉", result = BattleResult.WIN
                ))}
            } else {
                val nextEnemy = battle.enemyParty[nextIdx]
                _uiState.update { s -> s.copy(battleState = s.battleState.copy(
                    activeEnemyIndex = nextIdx, enemyCurrentHp = nextEnemy.hp,
                    playerGauge = 0f,
                    battleLog = "${player.name} の必殺技！${battle.enemyParty[battle.activeEnemyIndex].name} を倒した！"
                ))}
            }
        } else {
            _uiState.update { s -> s.copy(battleState = s.battleState.copy(
                enemyCurrentHp = newEnemyHp, playerGauge = 0f,
                battleLog = "${player.name} の必殺技！ $dmg ダメージ！"
            ))}
        }
    }

    fun switchPlayer(newIndex: Int) {
        val newPlayer = _uiState.value.battleState.playerParty.getOrNull(newIndex) ?: return
        _uiState.update { s -> s.copy(battleState = s.battleState.copy(
            activePlayerIndex = newIndex, playerCurrentHp = newPlayer.hp,
            playerGauge = 0f, battleLog = "${newPlayer.name} に交代した！"
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

    // ─── EXIF読み取り ──────────────────────────────────────────────────────────

    private data class ExifData(val latLng: LatLng?, val timestamp: String?, val aperture: Double?)

    /**
     * EXIFをsafelyに読み取る。あらゆる例外を吸収してnullを返す。
     * Android 10+ のGPS隠蔽対策として setRequireOriginal を試みる。
     */
    private fun readExifSafely(context: Context, uri: Uri): ExifData {
        // setRequireOriginal で GPS 隠蔽解除を試みる
        val unredacted = try { MediaStore.setRequireOriginal(uri) } catch (e: Exception) { uri }

        return tryReadExif(context, unredacted)
            ?: tryReadExif(context, uri)
            ?: ExifData(null, null, null)
    }

    private fun tryReadExif(context: Context, uri: Uri): ExifData? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val latlng = exif.latLong?.let { LatLng(it[0], it[1]) }
                val ts     = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                val aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0)
                    .takeIf { it > 0 }
                ExifData(latlng, ts, aperture)
            }
        } catch (e: Exception) { null }
    }

    private fun reverseGeocode(context: Context, latLng: LatLng): String? {
        return try {
            val geocoder = Geocoder(context, Locale.JAPANESE)
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
            addresses?.firstOrNull()?.let { addr ->
                buildString {
                    for (i in 0..addr.maxAddressLineIndex) {
                        if (isNotEmpty()) append(" ")
                        append(addr.getAddressLine(i))
                    }
                }.ifBlank { null }
            }
        } catch (e: Exception) { null }
    }

    // ─── モンスター生成 ────────────────────────────────────────────────────────

    private fun generateMonster(exif: ExifData): Monster? {
        val latLng = exif.latLng ?: return null

        val offsetLat = (Math.random() - 0.5) * 0.001
        val offsetLng = (Math.random() - 0.5) * 0.001
        val spawnLatLng = LatLng(latLng.latitude + offsetLat, latLng.longitude + offsetLng)

        val type = exif.timestamp?.let { determineType(it) } ?: MonsterType.NORMAL
        val f    = exif.aperture ?: 2.8
        val base = 50
        val atkBonus = if (f < 4.0) 20 else 0
        val defBonus = if (f >= 8.0) 20 else 0

        return Monster(
            name = listOf("ピクセモン", "レンズスライム", "シャッタース", "フラッシュビー", "ミラードン", "ズームゴン").random(),
            type = type,
            latLng = spawnLatLng,
            hp = 80 + (Math.random() * 40).toInt(),
            attack = base + atkBonus + (Math.random() * 10).toInt(),
            defense = base + defBonus + (Math.random() * 10).toInt()
        )
    }

    private fun determineType(timestamp: String): MonsterType {
        val hour = timestamp.substringAfter(" ").substringBefore(":").toIntOrNull() ?: 12
        return when (hour) {
            in 5..10 -> listOf(MonsterType.LIGHT, MonsterType.GRASS).random()
            in 11..16 -> listOf(MonsterType.FIRE, MonsterType.NORMAL).random()
            else -> listOf(MonsterType.DARK, MonsterType.WATER).random()
        }
    }
}
