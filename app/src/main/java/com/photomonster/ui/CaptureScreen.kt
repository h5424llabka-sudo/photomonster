package com.photomonster.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CatchingPokemon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photomonster.model.Monster
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    monster: Monster,
    captureCubes: Int,
    onAttemptCapture: () -> Boolean,
    onFlee: () -> Unit,
    modifier: Modifier = Modifier
) {
    var captureMessage by remember { mutableStateOf<String?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFE8F5E9)) // 草原っぽい背景色
    ) {
        // トップバー（逃げるボタン）
        TopAppBar(
            title = { Text("野生の ${monster.name} が現れた！") },
            navigationIcon = {
                IconButton(onClick = onFlee) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "逃げる")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            )
        )

        // モンスターの表示エリア
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 100.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // モンスターグラフィック（絵文字で代用）
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = monster.type.emoji,
                    fontSize = 80.sp
                )
            }
            Spacer(Modifier.height(16.dp))
            
            // ステータス表示
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = monster.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("属性: ${monster.type.displayName}")
                    Text("HP: ${monster.hp} | 攻撃: ${monster.attack} | 防御: ${monster.defense}")
                    Text(
                        "CP: ${monster.attack + monster.defense}",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // キャプチャUI (下部)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Color.White.copy(alpha = 0.9f),
                    RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                )
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (captureMessage != null) {
                Text(
                    text = captureMessage!!,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (captureMessage!!.contains("成功")) Color.Blue else Color.Red,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            Text("所持キューブ: $captureCubes 個")
            Spacer(Modifier.height(16.dp))
            
            Button(
                onClick = {
                    if (captureCubes > 0 && !isCapturing) {
                        isCapturing = true
                        scope.launch {
                            // 投げるアニメーションの代わり（待機）
                            captureMessage = "キューブを投げた！..."
                            delay(1500)
                            val success = onAttemptCapture()
                            if (success) {
                                captureMessage = "捕獲成功！"
                                delay(1500)
                                // onAttemptCapture 内で状態が変わり、自動で画面が切り替わる
                            } else {
                                captureMessage = "捕獲失敗...ボールから抜け出した！"
                                isCapturing = false
                            }
                        }
                    } else if (captureCubes <= 0) {
                        captureMessage = "キューブがありません！"
                    }
                },
                enabled = !isCapturing,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Icon(Icons.Default.CatchingPokemon, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("キューブを投げる", fontSize = 18.sp)
            }
        }
    }
}
