package com.photomonster.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.LatLng
import com.photomonster.model.Monster
import com.photomonster.model.MonsterType
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BattleScreen(
    playerParty: List<Monster>,
    onExit: () -> Unit
) {
    // 敵パーティーの自動生成
    val enemyParty = remember {
        List(3) {
            val randomType = MonsterType.values().random()
            Monster(
                name = "野生の敵",
                type = randomType,
                latLng = LatLng(0.0, 0.0),
                hp = 150,
                attack = 60,
                defense = 50
            )
        }
    }

    var activePlayerIndex by remember { mutableStateOf(0) }
    var activeEnemyIndex by remember { mutableStateOf(0) }

    var playerHp by remember { mutableStateOf(playerParty[0].hp) }
    var enemyHp by remember { mutableStateOf(enemyParty[0].hp) }
    
    var playerGauge by remember { mutableStateOf(0f) }
    var battleLog by remember { mutableStateOf("バトル開始！") }
    var isBattleOver by remember { mutableStateOf(false) }

    val activePlayer = playerParty.getOrNull(activePlayerIndex)
    val activeEnemy = enemyParty.getOrNull(activeEnemyIndex)

    // オートアタックロジック
    LaunchedEffect(activePlayer, activeEnemy, isBattleOver) {
        if (isBattleOver || activePlayer == null || activeEnemy == null) return@LaunchedEffect

        while (playerHp > 0 && enemyHp > 0) {
            delay(2000) // 2秒おきに攻撃
            
            // プレイヤーの攻撃
            val damageToEnemy = maxOf(1, activePlayer.attack - (activeEnemy.defense / 2))
            enemyHp -= damageToEnemy
            playerGauge = (playerGauge + 0.2f).coerceAtMost(1f)
            battleLog = "${activePlayer.name} の攻撃！ $damageToEnemy ダメージ！"
            
            if (enemyHp <= 0) {
                battleLog = "${activeEnemy.name} を倒した！"
                delay(1000)
                if (activeEnemyIndex < enemyParty.size - 1) {
                    activeEnemyIndex++
                    enemyHp = enemyParty[activeEnemyIndex].hp
                } else {
                    battleLog = "勝利！！"
                    isBattleOver = true
                    break
                }
                continue
            }

            delay(1000)
            
            // 敵の攻撃
            val damageToPlayer = maxOf(1, activeEnemy.attack - (activePlayer.defense / 2))
            playerHp -= damageToPlayer
            battleLog = "${activeEnemy.name} の反撃！ $damageToPlayer ダメージ！"

            if (playerHp <= 0) {
                battleLog = "${activePlayer.name} は倒れた..."
                delay(1000)
                // 次の生存しているモンスターを探す
                val nextIndex = playerParty.indices.firstOrNull { it > activePlayerIndex && playerParty[it].hp > 0 }
                if (nextIndex != null) {
                    activePlayerIndex = nextIndex
                    playerHp = playerParty[nextIndex].hp
                } else {
                    battleLog = "敗北..."
                    isBattleOver = true
                    break
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("バトル") },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFFAFAFA))
        ) {
            if (activeEnemy != null && activePlayer != null) {
                // ── 敵エリア ────────────────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(16.dp),
                    contentAlignment = Alignment.TopEnd
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${activeEnemy.name} ${activeEnemy.type.emoji}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        LinearProgressIndicator(
                            progress = (enemyHp.toFloat() / activeEnemy.hp).coerceIn(0f, 1f),
                            color = Color.Red,
                            modifier = Modifier.width(150.dp).height(12.dp).clip(RoundedCornerShape(6.dp))
                        )
                        Text("HP: $enemyHp / ${activeEnemy.hp}", fontSize = 12.sp)
                    }
                }

                // ── ログエリア ────────────────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = battleLog,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // ── 味方エリア ────────────────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(16.dp),
                    contentAlignment = Alignment.BottomStart
                ) {
                    Column {
                        Text("${activePlayer.name} ${activePlayer.type.emoji}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        LinearProgressIndicator(
                            progress = (playerHp.toFloat() / activePlayer.hp).coerceIn(0f, 1f),
                            color = Color.Green,
                            modifier = Modifier.width(150.dp).height(12.dp).clip(RoundedCornerShape(6.dp))
                        )
                        Text("HP: $playerHp / ${activePlayer.hp}", fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        // スキルゲージ
                        LinearProgressIndicator(
                            progress = playerGauge,
                            color = Color.Cyan,
                            modifier = Modifier.width(100.dp).height(8.dp).clip(RoundedCornerShape(4.dp))
                        )
                        Text("SP", fontSize = 10.sp, color = Color.Blue)
                    }
                }

                // ── コントロールパネル ────────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = {
                            if (playerGauge >= 1f && !isBattleOver) {
                                playerGauge = 0f
                                enemyHp -= (activePlayer.attack * 2)
                                battleLog = "${activePlayer.name} の必殺技！！"
                            }
                        },
                        enabled = playerGauge >= 1f && !isBattleOver,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Blue)
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null)
                        Text("スキル発動")
                    }
                    
                    Button(
                        onClick = {
                            val nextIndex = playerParty.indices.firstOrNull { it > activePlayerIndex && playerParty[it].hp > 0 }
                                ?: playerParty.indices.firstOrNull { it < activePlayerIndex && playerParty[it].hp > 0 }
                            
                            if (nextIndex != null && !isBattleOver) {
                                activePlayerIndex = nextIndex
                                playerHp = playerParty[nextIndex].hp
                                playerGauge = 0f
                                battleLog = "${playerParty[nextIndex].name} に交代した！"
                            }
                        },
                        enabled = !isBattleOver && playerParty.count { it.hp > 0 } > 1,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null)
                        Text("交代")
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("手持ちのモンスターがいません", fontSize = 20.sp)
                }
            }
        }
    }
}
