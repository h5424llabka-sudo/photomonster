package com.photomonster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import com.photomonster.viewmodel.BattleResult
import com.photomonster.viewmodel.BattleState
import com.photomonster.viewmodel.MapViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BattleScreen(
    battleState: BattleState,
    onActivateSkill: () -> Unit,
    onSwitchPlayer: (Int) -> Unit,
    onExit: () -> Unit
) {
    val activePlayer = battleState.playerParty.getOrNull(battleState.activePlayerIndex)
    val activeEnemy = battleState.enemyParty.getOrNull(battleState.activeEnemyIndex)
    val isBattleOver = battleState.result != BattleResult.NONE

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
                .background(Color(0xFF1A1A2E))
        ) {
            if (activeEnemy == null || activePlayer == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("手持ちモンスターがいません", color = Color.White, fontSize = 20.sp)
                }
                return@Scaffold
            }

            // ── 敵エリア ─────────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${activeEnemy.name} ${activeEnemy.type.emoji}",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        "HP: ${battleState.enemyCurrentHp} / ${activeEnemy.hp}",
                        fontSize = 12.sp,
                        color = Color.LightGray
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (battleState.enemyCurrentHp.toFloat() / activeEnemy.hp).coerceIn(0f, 1f) },
                        color = Color.Red,
                        trackColor = Color.DarkGray,
                        modifier = Modifier.width(180.dp).height(14.dp).clip(RoundedCornerShape(7.dp))
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("${battleState.activeEnemyIndex + 1} / ${battleState.enemyParty.size} 体目", fontSize = 11.sp, color = Color.Gray)
                }
            }

            // ── ログエリア ────────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .background(
                        if (isBattleOver && battleState.result == BattleResult.WIN) Color(0xFF1B5E20)
                        else if (isBattleOver) Color(0xFF7F0000)
                        else Color.Black.copy(alpha = 0.75f)
                    )
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = battleState.battleLog,
                    color = Color.White,
                    fontSize = 17.sp,
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
                    Text(
                        "${activePlayer.name} ${activePlayer.type.emoji}",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        "HP: ${battleState.playerCurrentHp} / ${activePlayer.hp}",
                        fontSize = 12.sp,
                        color = Color.LightGray
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (battleState.playerCurrentHp.toFloat() / activePlayer.hp).coerceIn(0f, 1f) },
                        color = Color(0xFF66BB6A),
                        trackColor = Color.DarkGray,
                        modifier = Modifier.width(180.dp).height(14.dp).clip(RoundedCornerShape(7.dp))
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SP ", fontSize = 12.sp, color = Color.Cyan)
                        LinearProgressIndicator(
                            progress = { battleState.playerGauge },
                            color = Color.Cyan,
                            trackColor = Color.DarkGray,
                            modifier = Modifier.width(100.dp).height(8.dp).clip(RoundedCornerShape(4.dp))
                        )
                    }
                }
            }

            // ── バトル結果のボタン ─────────────────────────────────────────────────
            if (isBattleOver) {
                Button(
                    onClick = onExit,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (battleState.result == BattleResult.WIN) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                ) {
                    Text("マップに戻る", fontSize = 18.sp)
                }
                return@Scaffold
            }

            // ── コントロールパネル ──────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D0D1A))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = onActivateSkill,
                    enabled = battleState.playerGauge >= 1f,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                    modifier = Modifier.weight(1f).padding(end = 6.dp)
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("スキル", fontSize = 14.sp)
                }

                // 交代ボタン（他のモンスターを選択）
                val otherPlayers = battleState.playerParty.indices.filter { it != battleState.activePlayerIndex }
                if (otherPlayers.isNotEmpty()) {
                    Button(
                        onClick = { onSwitchPlayer(otherPlayers.first()) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                        modifier = Modifier.weight(1f).padding(start = 6.dp)
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("交代", fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
