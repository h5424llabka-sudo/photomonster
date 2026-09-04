package com.photomonster.ui

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
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import com.google.maps.android.compose.clustering.Clustering
import com.photomonster.model.Monster
import com.photomonster.model.PhotoLocation
import com.photomonster.viewmodel.MapUiState
import kotlinx.coroutines.launch

/**
 * マップ画面 Composable
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    uiState: MapUiState,
    onPickPhotos: () -> Unit,
    onSelectCluster: (List<PhotoLocation>?) -> Unit,
    onCollectItem: (Int) -> Unit,
    onEncounterMonster: (Monster) -> Unit,
    onClearPhotos: () -> Unit
) {
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(35.6812, 139.7671), 5f)
    }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    // 写真が追加されたらカメラをフィット
    LaunchedEffect(uiState.photos.size) {
        if (uiState.photos.isNotEmpty()) {
            try {
                val bounds = buildBounds(uiState.photos.map { it.latLng })
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
            properties = MapProperties(mapType = MapType.NORMAL),
            onMapClick = { onSelectCluster(null) }
        ) {
            if (uiState.photos.isNotEmpty()) {
                Clustering(
                    items = uiState.photos,
                    onClusterClick = { cluster ->
                        onSelectCluster(cluster.items.toList())
                        false
                    },
                    onClusterItemClick = { item ->
                        onSelectCluster(listOf(item))
                        false
                    },
                    clusterContent = { cluster ->
                        val representative = cluster.items.firstOrNull()
                        ClusterIcon(photo = representative, count = cluster.size)
                    },
                    clusterItemContent = { item ->
                        ClusterIcon(photo = item, count = null)
                    }
                )
            }

            // 野生モンスターの表示
            uiState.wildMonsters.forEach { monster ->
                MarkerInfoWindow(
                    state = MarkerState(position = monster.latLng),
                    title = monster.name,
                    snippet = "${monster.type.emoji} ${monster.type.displayName} | CP: ${monster.attack + monster.defense}",
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE),
                    onClick = {
                        onEncounterMonster(monster)
                        true
                    }
                )
            }
        }

        // ── 上部ツールバー ────────────────────────────────────────────────────
        TopBar(
            photoCount = uiState.photos.size,
            captureCubes = uiState.captureCubes,
            caughtMonstersCount = uiState.caughtMonsters.size,
            wildMonsterCount = uiState.wildMonsters.size,
            onPickPhotos = onPickPhotos,
            onClearPhotos = onClearPhotos,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        )

        // ── 全体フィット FAB ────────────────────────────────────────────────
        if (uiState.photos.isNotEmpty()) {
            SmallFloatingActionButton(
                onClick = {
                    try {
                        val bounds = buildBounds(uiState.photos.map { it.latLng })
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

        // ── ローディングオーバーレイ ──────────────────────────────────────────
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
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

        // ── エラー Snackbar ─────────────────────────────────────────────────
        uiState.errorMessage?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) { Text(msg) }
        }
    }

    // ── 選択クラスターのボトムシート ──────────────────────────────────────────
    if (uiState.selectedCluster != null) {
        ModalBottomSheet(
            onDismissRequest = { onSelectCluster(null) },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                val photos = uiState.selectedCluster
                if (photos.isNotEmpty()) {
                    val date = photos.first().formattedTimestamp.split(" ").firstOrNull() ?: "日時不明"
                    Text(
                        text = date,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    photos.firstOrNull()?.address?.let { addr ->
                        Text(
                            text = addr,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp)
                        )
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 500.dp)
                    ) {
                        items(photos) { photo ->
                            Box(modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))) {
                                AsyncImage(
                                    model = photo.uri,
                                    contentDescription = "写真",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                // アイテム回収可能なオーバーレイ
                                if (photo.canCollectItems) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.4f))
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
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

// ── マップ上の写真アイコン ──────────────────────────────────────────────────────

@Composable
private fun ClusterIcon(photo: PhotoLocation?, count: Int?) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(72.dp)
    ) {
        Surface(
            modifier = Modifier.size(62.dp),
            shape = CircleShape,
            color = Color.LightGray,
            border = BorderStroke(
                width = 3.dp,
                color = if (photo?.canCollectItems == true) Color.Yellow else Color.White
            ),
            shadowElevation = 6.dp
        ) {
            if (photo != null) {
                AsyncImage(
                    model = photo.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        if (count != null && count > 1) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-4).dp, y = 4.dp)
                    .size(22.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape)
            ) {
                Text(
                    text = if (count > 99) "99+" else count.toString(),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ── 上部ツールバー ─────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    photoCount: Int,
    captureCubes: Int,
    caughtMonstersCount: Int,
    wildMonsterCount: Int,
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
            if (photoCount > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("📍$photoCount", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text("👾$wildMonsterCount", style = MaterialTheme.typography.labelSmall)
                    Text("📦$captureCubes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    Text("🐾$caughtMonstersCount", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (photoCount > 0) {
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
