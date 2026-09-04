package com.photomonster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photomonster.model.Monster

/**
 * 手持ちモンスター一覧画面
 */
@Composable
fun PartyScreen(
    monsters: List<Monster>
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Text(
            "🐾 手持ちモンスター",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            "${monsters.size} 体捕獲済み",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (monsters.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("😴", fontSize = 64.sp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "まだモンスターがいません",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "マップ上のオレンジのピンをタップして捕獲しよう！",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(monsters) { monster ->
                    MonsterCard(monster = monster)
                }
            }
        }
    }
}

@Composable
private fun MonsterCard(monster: Monster) {
    val cp = monster.attack + monster.defense

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // アイコン（絵文字）
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(monster.type.emoji, fontSize = 36.sp)
            }

            Spacer(Modifier.width(12.dp))

            // ステータス
            Column(modifier = Modifier.weight(1f)) {
                Text(monster.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    "${monster.type.emoji} ${monster.type.displayName}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatChip("HP", monster.hp, Color(0xFF2E7D32))
                    StatChip("攻", monster.attack, Color(0xFFC62828))
                    StatChip("防", monster.defense, Color(0xFF1565C0))
                }
            }

            // CP
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("CP", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "$cp",
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: Int, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text("$label:$value", fontSize = 12.sp, color = color, fontWeight = FontWeight.Bold)
    }
}
