package com.photomonster.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.LatLng
import com.photomonster.model.Monster
import com.photomonster.model.PhotoSpot
import com.photomonster.viewmodel.MapUiState
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorationScreen(
    uiState: MapUiState,
    onEncounterMonster: (Monster) -> Unit,
    onBack: () -> Unit
) {
    val spot = uiState.exploringSpot ?: return
    
    // スポット周辺のモンスターを抽出（半径300m以内）
    val localMonsters = remember(uiState.wildMonsters, spot) {
        uiState.wildMonsters.filter { distanceMeters(it.latLng, spot.centerLatLng) <= 300.0 }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("モンスター探索") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF0F172A)) // ダークブルーの背景
        ) {
            if (localMonsters.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("👻", fontSize = 64.sp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "このスポットにはモンスターがいません",
                        color = Color.White,
                        fontSize = 18.sp
                    )
                }
            } else {
                Text(
                    text = "タップして捕獲しよう！",
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)
                )
                
                // モンスターを散りばめる
                localMonsters.forEachIndexed { index, monster ->
                    BouncingMonster(
                        monster = monster,
                        index = index,
                        onClick = { onEncounterMonster(monster) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BouncingMonster(monster: Monster, index: Int, onClick: () -> Unit) {
    // インデックスベースで配置を散らす（簡易的）
    val xOffset = remember { (10..80).random() / 100f }
    val yOffset = remember { (20..80).random() / 100f }
    
    val infiniteTransition = rememberInfiniteTransition()
    val bounce by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800 + (index * 100), easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(
                    x = (xOffset * 300).dp,
                    y = (yOffset * 500).dp + bounce.dp
                )
                .clickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .shadow(8.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color(0xFF1E293B)),
                contentAlignment = Alignment.Center
            ) {
                Text(monster.type.emoji, fontSize = 40.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = monster.name,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

private fun distanceMeters(a: LatLng, b: LatLng): Double {
    val r = 6371000.0
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLng = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}
