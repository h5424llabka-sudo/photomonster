package com.photomonster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photomonster.model.Monster
import com.photomonster.model.PhotoSpot

/**
 * モンスター探索画面
 * スポットをタップ→「モンスター探索」→ここに遷移
 * スポット周辺に出現しているモンスターを一覧表示し、タップで捕獲画面へ
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonsterHuntScreen(
    spot: PhotoSpot,
    monsters: List<Monster>,
    onEncounterMonster: (Monster) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1A1A2E), Color(0xFF16213E), Color(0xFF0F3460))
                )
            )
    ) {
        // トップバー
        TopAppBar(
            title = {
                Column {
                    Text("モンスター探索", color = Color.White)
                    Text(
                        spot.representativePhoto.address ?: spot.representativePhoto.formattedTimestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "戻る", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )

        if (monsters.isEmpty()) {
            // モンスターなし
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("👁️", fontSize = 64.sp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "このエリアにはモンスターがいません",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "写真を追加するとモンスターが出現します",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            Text(
                text = "${monsters.size} 匹のモンスターが潜んでいる！",
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium
            )

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(monsters) { monster ->
                    MonsterEncounterCard(
                        monster = monster,
                        onTap = { onEncounterMonster(monster) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MonsterEncounterCard(
    monster: Monster,
    onTap: () -> Unit
) {
    val cp = monster.attack + monster.defense

    Card(
        onClick = onTap,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // モンスターアイコン
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.2f), Color.Transparent)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(monster.type.emoji, fontSize = 44.sp)
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    monster.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = Color.White
                )
                Text(
                    "${monster.type.emoji} ${monster.type.displayName}",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatBadge("HP", monster.hp, Color(0xFF4CAF50))
                    StatBadge("攻", monster.attack, Color(0xFFF44336))
                    StatBadge("防", monster.defense, Color(0xFF2196F3))
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("CP", fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f))
                Text(
                    "$cp",
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = Color(0xFFFFD700)
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onTap,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("遭遇！", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun StatBadge(label: String, value: Int, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.25f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text("$label:$value", fontSize = 12.sp, color = color, fontWeight = FontWeight.Bold)
    }
}
