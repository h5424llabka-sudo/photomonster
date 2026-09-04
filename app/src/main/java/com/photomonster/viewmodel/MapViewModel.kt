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
import com.google.maps.android.SphericalUtil
import com.photomonster.data.GameRepository
import com.photomonster.model.Monster
import com.photomonster.model.MonsterType
import com.photomonster.model.PhotoLocation
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

// バトル画面の状態
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
    val result: BattleResult = BattleResult.NONE,
    val isProcessing: Boolean = false
)

/** アプリの UI 状態 */
data class MapUiState(
    val photos: List<PhotoLocation> = emptyList(),
    val selectedCluster: List<PhotoLocation>? = null,
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

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = GameRepository(application)
    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    /** 起動時にセーブデータを一括読み込む */
    init {
        viewModelScope.launch {
            val photos = withContext(Dispatchers.IO) { repository.photos.first() }
            val wild   = withContext(Dispatchers.IO) { repository.wildMonsters.first() }
            val caught = withContext(Dispatchers.IO) { repository.caughtMonsters.first() }
            val cubes  = withContext(Dispatchers.IO) { repository.captureCubes.first() }
            _uiState.update {
                it.copy(photos = photos, wildMonsters = wild, caughtMonsters = caught, captureCubes = cubes)
            }
        }
    }

    // ─── バッチ処理（クラッシュ修正） ────────────────────────────────────────────

    fun processSelectedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val context: Context = getApplication()
            val allResults = mutableListOf<PhotoLocation>()
            val allNewMonsters = mutableListOf<Monster>()
            var totalSkipped = 0

            // 重複チェック用 set
            val existingIds = _uiState.value.photos.map { it.id }.toHashSet()

            // バッチ処理: 10枚ずつに分割して処理
            val batchSize = 10
            for (batch in uris.chunked(batchSize)) {
                val batchResults = mutableListOf<PhotoLocation>()
                val batchMonsters = mutableListOf<Monster>()
                var batchSkipped = 0

                withContext(Dispatchers.IO) {
                    for (uri in batch) {
                        // 重複スキップ
                        if (uri.hashCode() in existingIds) continue

                        try {
                            val exifData = readExifData(context, uri)
                            if (exifData.latLng == null) {
                                batchSkipped++
                                continue
                            }

                            // 近接スポットへの統合チェック (100m以内)
                            val existingNearby = findNearbyPhoto(
                                _uiState.value.photos + allResults,
                                exifData.latLng,
                                radiusMeters = 100.0
                            )

                            if (existingNearby == null) {
                                // 新しいスポットとして追加
                                val address = reverseGeocode(context, exifData.latLng)
                                val newPhoto = PhotoLocation(
                                    id = uri.hashCode(),
                                    uri = uri,
                                    latLng = exifData.latLng,
                                    timestamp = exifData.timestamp,
                                    address = address
                                )
                                batchResults.add(newPhoto)
                                existingIds.add(uri.hashCode())
                            } else {
                                // 既存スポットに統合（IDだけ記録してスキップ）
                                existingIds.add(uri.hashCode())
                                // 近くのスポットにこの写真も紐付けるが、
                                // ClusterItemとしては1つのピンにまとまる
                            }

                            // モンスター生成
                            generateMonster(exifData)?.let { batchMonsters.add(it) }

                        } catch (e: Exception) {
                            batchSkipped++
                        }
                    }
                }

                allResults.addAll(batchResults)
                allNewMonsters.addAll(batchMonsters)
                totalSkipped += batchSkipped

                // バッチごとに中間結果をUIへ反映（ユーザーが待たされる感を減らす）
                val currentPhotos = _uiState.value.photos + allResults
                val currentMonsters = _uiState.value.wildMonsters + allNewMonsters
                _uiState.update { current ->
                    current.copy(
                        photos = currentPhotos,
                        wildMonsters = currentMonsters
                    )
                }
            }

            // 最終状態でエラーメッセージをセットして完了
            _uiState.update { current ->
                current.copy(
                    isLoading = false,
                    skippedCount = current.skippedCount + totalSkipped,
                    errorMessage = when {
                        totalSkipped > 0 && allResults.isEmpty() ->
                            "選択した写真に位置情報が含まれていません（${totalSkipped}枚スキップ）"
                        totalSkipped > 0 ->
                            "${totalSkipped}枚は位置情報なしのためスキップしました"
                        else -> null
                    }
                )
            }

            // 永続保存
            repository.savePhotos(_uiState.value.photos)
            repository.saveWildMonsters(_uiState.value.wildMonsters)
        }
    }

    /** 半径radiusMeters以内の既存PhotoLocationを返す（なければnull） */
    private fun findNearbyPhoto(
        existingPhotos: List<PhotoLocation>,
        latLng: LatLng,
        radiusMeters: Double
    ): PhotoLocation? {
        return existingPhotos.firstOrNull { existing ->
            SphericalUtil.computeDistanceBetween(existing.latLng, latLng) <= radiusMeters
        }
    }

    // ─── クラスター・選択 ──────────────────────────────────────────────────────

    fun selectCluster(photos: List<PhotoLocation>?) {
        _uiState.update { it.copy(selectedCluster = photos) }
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

        val cp = monster.attack + monster.defense
        val catchRate = (80 - (cp / 5.0)).coerceIn(10.0, 90.0)
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
            // 永続保存
            repository.saveCaptureCubes(_uiState.value.captureCubes)
            if (success) {
                repository.saveCaughtMonsters(_uiState.value.caughtMonsters)
                repository.saveWildMonsters(_uiState.value.wildMonsters)
            }
        }
        return success
    }

    // ─── アイテム回収 ──────────────────────────────────────────────────────────

    fun collectItemFromSpot(photoId: Int) {
        viewModelScope.launch {
            val current = _uiState.value
            val targetPhoto = current.photos.firstOrNull { it.id == photoId } ?: return@launch
            if (!targetPhoto.canCollectItems) return@launch

            val gainedCubes = (1..3).random()
            val updatedPhotos = current.photos.map { photo ->
                if (photo.id == photoId) photo.copy(lastCollectedTime = System.currentTimeMillis())
                else photo
            }
            _uiState.update { state ->
                state.copy(
                    photos = updatedPhotos,
                    captureCubes = state.captureCubes + gainedCubes,
                    selectedCluster = state.selectedCluster?.map { p ->
                        if (p.id == photoId) p.copy(lastCollectedTime = System.currentTimeMillis()) else p
                    }
                )
            }
            repository.savePhotos(_uiState.value.photos)
            repository.saveCaptureCubes(_uiState.value.captureCubes)
        }
    }

    // ─── バトルシステム（ViewModel管理） ──────────────────────────────────────────

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
                val state = _uiState.value
                val battle = state.battleState

                if (battle.result != BattleResult.NONE || battle.isProcessing) break

                delay(2000)

                val currentBattle = _uiState.value.battleState
                if (currentBattle.result != BattleResult.NONE) break

                val player = currentBattle.playerParty.getOrNull(currentBattle.activePlayerIndex) ?: break
                val enemy = currentBattle.enemyParty.getOrNull(currentBattle.activeEnemyIndex) ?: break

                // プレイヤーの攻撃
                val dmgToEnemy = maxOf(1, player.attack - enemy.defense / 2)
                val newEnemyHp = currentBattle.enemyCurrentHp - dmgToEnemy
                val newGauge = (currentBattle.playerGauge + 0.25f).coerceAtMost(1f)

                if (newEnemyHp <= 0) {
                    // 敵を倒した
                    val nextEnemyIndex = currentBattle.activeEnemyIndex + 1
                    if (nextEnemyIndex >= currentBattle.enemyParty.size) {
                        // 全敵を倒した → 勝利
                        _uiState.update { s ->
                            s.copy(battleState = s.battleState.copy(
                                enemyCurrentHp = 0,
                                playerGauge = newGauge,
                                battleLog = "全員倒した！勝利！！🎉",
                                result = BattleResult.WIN
                            ))
                        }
                        break
                    } else {
                        // 次の敵へ
                        val nextEnemy = currentBattle.enemyParty[nextEnemyIndex]
                        _uiState.update { s ->
                            s.copy(battleState = s.battleState.copy(
                                activeEnemyIndex = nextEnemyIndex,
                                enemyCurrentHp = nextEnemy.hp,
                                playerGauge = newGauge,
                                battleLog = "${enemy.name} を倒した！次は ${nextEnemy.name}！"
                            ))
                        }
                        delay(1200)
                        continue
                    }
                } else {
                    _uiState.update { s ->
                        s.copy(battleState = s.battleState.copy(
                            enemyCurrentHp = newEnemyHp,
                            playerGauge = newGauge,
                            battleLog = "${player.name} の攻撃！ $dmgToEnemy ダメージ！"
                        ))
                    }
                }

                delay(1000)

                val afterPlayerAttack = _uiState.value.battleState
                if (afterPlayerAttack.result != BattleResult.NONE) break

                val enemy2 = afterPlayerAttack.enemyParty.getOrNull(afterPlayerAttack.activeEnemyIndex) ?: break
                val player2 = afterPlayerAttack.playerParty.getOrNull(afterPlayerAttack.activePlayerIndex) ?: break

                // 敵の攻撃
                val dmgToPlayer = maxOf(1, enemy2.attack - player2.defense / 2)
                val newPlayerHp = afterPlayerAttack.playerCurrentHp - dmgToPlayer

                if (newPlayerHp <= 0) {
                    // 味方を倒された
                    val nextPlayerIdx = afterPlayerAttack.playerParty.indices
                        .firstOrNull { it > afterPlayerAttack.activePlayerIndex }
                    if (nextPlayerIdx == null) {
                        // 全員倒れた → 敗北
                        _uiState.update { s ->
                            s.copy(battleState = s.battleState.copy(
                                playerCurrentHp = 0,
                                battleLog = "全員倒れた...敗北...",
                                result = BattleResult.LOSE
                            ))
                        }
                        break
                    } else {
                        val nextPlayer = afterPlayerAttack.playerParty[nextPlayerIdx]
                        _uiState.update { s ->
                            s.copy(battleState = s.battleState.copy(
                                activePlayerIndex = nextPlayerIdx,
                                playerCurrentHp = nextPlayer.hp,
                                playerGauge = 0f,
                                battleLog = "${player2.name} が倒れた！${nextPlayer.name} が登場！"
                            ))
                        }
                        delay(1200)
                        continue
                    }
                } else {
                    _uiState.update { s ->
                        s.copy(battleState = s.battleState.copy(
                            playerCurrentHp = newPlayerHp,
                            battleLog = "${enemy2.name} の攻撃！ $dmgToPlayer ダメージ！"
                        ))
                    }
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
            val nextEnemyIndex = battle.activeEnemyIndex + 1
            if (nextEnemyIndex >= battle.enemyParty.size) {
                _uiState.update { s ->
                    s.copy(battleState = s.battleState.copy(
                        enemyCurrentHp = 0,
                        playerGauge = 0f,
                        battleLog = "${player.name} の必殺技！全員倒した！勝利！🎉",
                        result = BattleResult.WIN
                    ))
                }
            } else {
                val nextEnemy = battle.enemyParty[nextEnemyIndex]
                _uiState.update { s ->
                    s.copy(battleState = s.battleState.copy(
                        activeEnemyIndex = nextEnemyIndex,
                        enemyCurrentHp = nextEnemy.hp,
                        playerGauge = 0f,
                        battleLog = "${player.name} の必殺技！${battle.enemyParty[battle.activeEnemyIndex].name} を倒した！"
                    ))
                }
            }
        } else {
            _uiState.update { s ->
                s.copy(battleState = s.battleState.copy(
                    enemyCurrentHp = newEnemyHp,
                    playerGauge = 0f,
                    battleLog = "${player.name} の必殺技！ $dmg ダメージ！"
                ))
            }
        }
    }

    fun switchPlayer(newIndex: Int) {
        val battle = _uiState.value.battleState
        val newPlayer = battle.playerParty.getOrNull(newIndex) ?: return
        _uiState.update { s ->
            s.copy(battleState = s.battleState.copy(
                activePlayerIndex = newIndex,
                playerCurrentHp = newPlayer.hp,
                playerGauge = 0f,
                battleLog = "${newPlayer.name} に交代した！"
            ))
        }
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

    // ─── Private helpers ──────────────────────────────────────────────────────

    private data class ExifData(val latLng: LatLng?, val timestamp: String?, val aperture: Double?)

    private fun readExifData(context: Context, uri: Uri): ExifData {
        val unredactedUri: Uri = try {
            MediaStore.setRequireOriginal(uri)
        } catch (e: Exception) { uri }

        readExifFromUri(context, unredactedUri)?.let { return it }
        return readExifFromUri(context, uri) ?: ExifData(null, null, null)
    }

    private fun readExifFromUri(context: Context, uri: Uri): ExifData? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val latLng = exif.latLong?.let { LatLng(it[0], it[1]) }
                val timestamp = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                val aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0).let {
                    if (it > 0) it else null
                }
                ExifData(latLng, timestamp, aperture)
            }
        } catch (e: SecurityException) { null } catch (e: Exception) { null }
    }

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
        } catch (e: Exception) { null }
    }

    private fun generateMonster(exif: ExifData): Monster? {
        if (exif.latLng == null) return null

        val offsetLat = (Math.random() - 0.5) * 0.001
        val offsetLng = (Math.random() - 0.5) * 0.001
        val spawnLatLng = LatLng(exif.latLng.latitude + offsetLat, exif.latLng.longitude + offsetLng)

        val type = determineMonsterType(exif.timestamp)
        val fNumber = exif.aperture ?: 2.8
        val baseStat = 50
        val attackBonus = if (fNumber < 4.0) 20 else 0
        val defenseBonus = if (fNumber >= 8.0) 20 else 0

        val names = listOf("ピクセモン", "レンズスライム", "シャッタース", "フラッシュビー", "ミラードン", "ズームゴン")
        return Monster(
            name = names.random(),
            type = type,
            latLng = spawnLatLng,
            hp = 80 + (Math.random() * 40).toInt(),
            attack = baseStat + attackBonus + (Math.random() * 10).toInt(),
            defense = baseStat + defenseBonus + (Math.random() * 10).toInt()
        )
    }

    private fun determineMonsterType(timestamp: String?): MonsterType {
        if (timestamp == null) return MonsterType.NORMAL
        val timePart = timestamp.substringAfter(" ")
        val hour = timePart.substringBefore(":").toIntOrNull() ?: 12
        return when (hour) {
            in 5..10 -> listOf(MonsterType.LIGHT, MonsterType.GRASS).random()
            in 11..16 -> listOf(MonsterType.FIRE, MonsterType.NORMAL).random()
            else -> listOf(MonsterType.DARK, MonsterType.WATER).random()
        }
    }
}
