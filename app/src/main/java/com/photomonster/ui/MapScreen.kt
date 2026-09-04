package com.photomonster.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import androidx.core.graphics.drawable.toBitmap
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import com.photomonster.model.PhotoSpot
import com.photomonster.model.PhotoLocation
import com.photomonster.viewmodel.MapUiState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    uiState: MapUiState,
    onPickPhotos: () -> Unit,
    onSelectSpot: (PhotoSpot?) -> Unit,
    onCollectItem: (Int) -> Unit,
    onEnterHuntMode: (PhotoSpot) -> Unit,
    onClearPhotos: () -> Unit
) {
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(35.6812, 139.7671), 5f)
    }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val context = LocalContext.current

    // スポット追加時にカメラをフィット
    LaunchedEffect(uiState.photoSpots.size) {
        if (uiState.photoSpots.isNotEmpty()) {
            try {
                val bounds = buildBounds(uiState.photoSpots.map { it.centerLatLng })
                cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 120), 800)
            } catch (_: Exception) { }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                myLocationButtonEnabled = false,
                mapToolbarEnabled = false
            ),
            onMapClick = { onSelectSpot(null) }
        ) {
            // 写真スポットのみ表示（モンスターピンは非表示）
            uiState.photoSpots.forEach { spot ->
                SpotMarker(spot = spot, context = context) {
                    onSelectSpot(spot)
                }
            }
        }

        // 上部ツールバー
        TopBar(
            spotCount = uiState.photoSpots.size,
            wildCount = uiState.wildMonsters.size,
            captureCubes = uiState.captureCubes,
            caughtCount = uiState.caughtMonsters.size,
            onPickPhotos = onPickPhotos,
            onClearPhotos = onClearPhotos,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        )

        // 全体フィットFAB
        if (uiState.photoSpots.isNotEmpty()) {
            SmallFloatingActionButton(
                onClick = {
                    try {
                        val bounds = buildBounds(uiState.photoSpots.map { it.centerLatLng })
                        scope.launch { cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 120), 600) }
                    } catch (_: Exception) { }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "全体表示")
            }
        }

        // ローディング
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Row(
                        modifier = Modifier.padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("写真を解析中...", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        // エラー
        uiState.errorMessage?.let { msg ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) { Text(msg) }
        }
    }

    // ボトムシート（写真一覧 + モンスター探索ボタン）
    val selectedSpot = uiState.selectedSpot
    if (selectedSpot != null) {
        ModalBottomSheet(
            onDismissRequest = { onSelectSpot(null) },
            sheetState = sheetState,
        ) {
            SpotBottomSheetContent(
                spot = selectedSpot,
                monsterCount = uiState.wildMonsters.count { it.spotId == selectedSpot.id },
                onCollectItem = onCollectItem,
                onEnterHuntMode = { onEnterHuntMode(selectedSpot) }
            )
        }
    }
}

// ─── スポットマーカー（Coilで事前ロード → BitmapDescriptor） ───────────────────

@Composable
private fun SpotMarker(
    spot: PhotoSpot,
    context: android.content.Context,
    onClick: () -> Unit
) {
    val uri = spot.representativePhoto.uri
    val canCollect = spot.canCollectItems
    val count = spot.photos.size

    // Coil で同期的に画像をロードしてBitmapDescriptorに変換
    var descriptor by remember(uri.toString(), canCollect) {
        mutableStateOf<BitmapDescriptor?>(null)
    }

    LaunchedEffect(uri.toString(), canCollect) {
        try {
            val loader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(uri)
                .size(128, 128)
                .allowHardware(false) // ソフトウェアレンダリング（Bitmap操作に必要）
                .build()
            val result = loader.execute(request)
            if (result is SuccessResult) {
                val raw = result.drawable.toBitmap(128, 128, Bitmap.Config.ARGB_8888)
                val icon = makeCircularBitmap(raw, canCollect, count)
                descriptor = BitmapDescriptorFactory.fromBitmap(icon)
            }
        } catch (_: Exception) {
            // フォールバック: デフォルトマーカー
            descriptor = BitmapDescriptorFactory.defaultMarker(
                if (canCollect) BitmapDescriptorFactory.HUE_YELLOW
                else BitmapDescriptorFactory.HUE_AZURE
            )
        }
    }

    // 画像がロードされたらカスタムアイコン、それまでデフォルト表示
    Marker(
        state = MarkerState(position = spot.centerLatLng),
        icon = descriptor ?: BitmapDescriptorFactory.defaultMarker(
            if (canCollect) BitmapDescriptorFactory.HUE_YELLOW else BitmapDescriptorFactory.HUE_AZURE
        ),
        title = spot.representativePhoto.formattedTimestamp,
        snippet = "${spot.photos.size}枚の写真",
        onClick = { onClick(); true }
    )
}

/**
 * 画像を円形に切り抜き、枠線と枚数バッジを追加してBitmapを作る
 */
private fun makeCircularBitmap(src: Bitmap, canCollect: Boolean, count: Int): Bitmap {
    val size = 160
    val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint  = Paint(Paint.ANTI_ALIAS_FLAG)

    // 円形にクリップして画像を描画
    val centerX = size / 2f
    val centerY = size / 2f
    val radius  = size / 2f - 8f

    canvas.drawCircle(centerX, centerY, radius, paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    val scaled = Bitmap.createScaledBitmap(src, (radius * 2).toInt(), (radius * 2).toInt(), true)
    canvas.drawBitmap(scaled, centerX - radius, centerY - radius, paint)
    paint.xfermode = null

    // 枠線
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = if (canCollect) AndroidColor.YELLOW else AndroidColor.WHITE
    }
    canvas.drawCircle(centerX, centerY, radius, borderPaint)

    // 枚数バッジ（2枚以上）
    if (count > 1) {
        val badgeRadius = 22f
        val bx = size - badgeRadius - 4f
        val by = badgeRadius + 4f
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.RED
        }
        canvas.drawCircle(bx, by, badgeRadius, badgePaint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textSize = 22f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val text = if (count > 99) "99+" else "$count"
        canvas.drawText(text, bx, by + 8f, textPaint)
    }

    return output
}

// ─── ボトムシートコンテンツ ───────────────────────────────────────────────────

@Composable
private fun SpotBottomSheetContent(
    spot: PhotoSpot,
    monsterCount: Int,
    onCollectItem: (Int) -> Unit,
    onEnterHuntMode: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        // ヘッダー
        Text(
            text = spot.representativePhoto.formattedTimestamp.split(" ").firstOrNull() ?: "",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        spot.representativePhoto.address?.let { addr ->
            Text(
                text = addr,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp)
            )
        }
        Text(
            text = "${spot.photos.size} 枚の写真",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp)
        )

        // モンスター探索ボタン
        Button(
            onClick = onEnterHuntMode,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A237E)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.Search, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                "モンスター探索${if (monsterCount > 0) "（$monsterCount 匹）" else ""}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(12.dp))

        // 写真グリッド
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 450.dp)
        ) {
            items(
                items = spot.photos,
                key = { photo -> photo.uriString }  // 安定したキー
            ) { photo ->
                Box(modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(photo.uri)
                            .crossfade(true)
                            .build(),
                        contentDescription = "写真",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (photo.canCollectItems) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.45f))
                                .clickable { onCollectItem(photo.id) },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("📦", fontSize = 28.sp)
                                Text("タップで回収", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ─── 上部ツールバー ────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    spotCount: Int,
    wildCount: Int,
    captureCubes: Int,
    caughtCount: Int,
    onPickPhotos: () -> Unit,
    onClearPhotos: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val versionName = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" }
        catch (_: Exception) { "?" }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.93f), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("PhotoMonster", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(5.dp))
                Text("v$versionName", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 1.dp))
            }
            if (spotCount > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("📍$spotCount", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text("👾$wildCount", style = MaterialTheme.typography.labelSmall)
                    Text("📦$captureCubes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    Text("🐾$caughtCount", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (spotCount > 0) {
                IconButton(onClick = onClearPhotos) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "クリア", tint = MaterialTheme.colorScheme.error)
                }
            }
            FilledTonalButton(onClick = onPickPhotos, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("写真を選択", fontSize = 13.sp)
            }
        }
    }
}

// ─── ユーティリティ ────────────────────────────────────────────────────────────

private fun buildBounds(points: List<LatLng>): LatLngBounds {
    val builder = LatLngBounds.Builder()
    points.forEach { builder.include(it) }
    return builder.build()
}
