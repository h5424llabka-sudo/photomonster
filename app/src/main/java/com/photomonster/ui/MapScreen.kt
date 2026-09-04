package com.photomonster.ui

import android.graphics.Bitmap
import android.graphics.Canvas
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
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
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
import coil.compose.AsyncImage
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import com.photomonster.model.Monster
import com.photomonster.model.PhotoSpot
import com.photomonster.viewmodel.MapUiState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    uiState: MapUiState,
    onPickPhotos: () -> Unit,
    onSelectSpot: (PhotoSpot?) -> Unit,
    onExploreSpot: (PhotoSpot) -> Unit,
    onCollectItem: (Int) -> Unit,
    onClearPhotos: () -> Unit
) {
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(35.6812, 139.7671), 5f)
    }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    // スポットが追加されたらカメラをフィット
    LaunchedEffect(uiState.photoSpots.size) {
        if (uiState.photoSpots.isNotEmpty()) {
            try {
                val bounds = buildBounds(uiState.photoSpots.map { it.centerLatLng })
                scope.launch {
                    cameraPositionState.animate(
                        CameraUpdateFactory.newLatLngBounds(bounds, 120),
                        durationMs = 800
                    )
                }
            } catch (_: Exception) { }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Google Maps ──────────────────────────────────────────────────────
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
            // フォトスポット（200m以内は1つのピン、ズームに関係なく固定）
            uiState.photoSpots.forEach { spot ->
                Marker(
                    state = MarkerState(position = spot.centerLatLng),
                    title = spot.representativePhoto.formattedTimestamp,
                    icon = spot.iconDescriptor ?: BitmapDescriptorFactory.defaultMarker(
                        if (spot.canCollectItems) BitmapDescriptorFactory.HUE_YELLOW
                        else BitmapDescriptorFactory.HUE_AZURE
                    ),
                    onClick = {
                        onSelectSpot(spot)
                        true
                    }
                )
            }
        }

        // ── 上部ツールバー ──────────────────────────────────────────────────
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

        // ── 全体フィット FAB ──────────────────────────────────────────────
        if (uiState.photoSpots.isNotEmpty()) {
            SmallFloatingActionButton(
                onClick = {
                    try {
                        val bounds = buildBounds(uiState.photoSpots.map { it.centerLatLng })
                        scope.launch {
                            cameraPositionState.animate(
                                CameraUpdateFactory.newLatLngBounds(bounds, 120),
                                durationMs = 600
                            )
                        }
                    } catch (_: Exception) { }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "全体表示")
            }
        }

        // ── ローディング ──────────────────────────────────────────────────
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
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

        // ── エラーメッセージ ────────────────────────────────────────────
        uiState.errorMessage?.let { msg ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) { Text(msg) }
        }
    }

    // ── 写真一覧ボトムシート ────────────────────────────────────────────────
    if (uiState.selectedSpot != null) {
        ModalBottomSheet(
            onDismissRequest = { onSelectSpot(null) },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            val spot = uiState.selectedSpot
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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${spot.photos.size} 枚の写真",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    Button(onClick = { onExploreSpot(spot) }) {
                        Text("モンスター探索")
                    }
                }

                // 写真グリッド
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 500.dp)
                ) {
                    items(spot.photos) { photo ->
                        Box(modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))) {
                            AsyncImage(
                                model = photo.uri,
                                contentDescription = "写真",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            // アイテム回収オーバーレイ
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
                                        Text(
                                            "タップで回収",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}



// ── 上部ツールバー ─────────────────────────────────────────────────────────────

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
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp))
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.93f),
                RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "PhotoMonster",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    "v$versionName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 1.dp)
                )
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
            FilledTonalButton(
                onClick = onPickPhotos,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("写真を選択", fontSize = 13.sp)
            }
        }
    }
}

// ── ユーティリティ ─────────────────────────────────────────────────────────────

private fun buildBounds(points: List<LatLng>): LatLngBounds {
    val builder = LatLngBounds.Builder()
    points.forEach { builder.include(it) }
    return builder.build()
}
