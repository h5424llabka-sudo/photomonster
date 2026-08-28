package com.photomonster.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import com.photomonster.model.PhotoLocation
import com.photomonster.viewmodel.MapUiState
import com.photomonster.viewmodel.MapViewModel
import kotlinx.coroutines.launch

/**
 * メイン画面 Composable
 * ・Google Maps にマーカーを表示
 * ・下部サムネイル横スクロール一覧
 * ・マーカー / サムネイルタップで情報カード表示
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    uiState: MapUiState,
    onPickPhotos: () -> Unit,
    onSelectPhoto: (PhotoLocation?) -> Unit,
    onClearPhotos: () -> Unit
) {
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(35.6812, 139.7671), 5f) // 東京
    }
    val scope = rememberCoroutineScope()

    // 写真が追加されたらカメラをフィット
    LaunchedEffect(uiState.photos.size) {
        if (uiState.photos.isNotEmpty()) {
            val bounds = buildBounds(uiState.photos.map { it.latLng })
            scope.launch {
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngBounds(bounds, 120),
                    durationMs = 800
                )
            }
        }
    }

    // 選択写真にフォーカス
    LaunchedEffect(uiState.selectedPhoto) {
        uiState.selectedPhoto?.let { photo ->
            scope.launch {
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngZoom(photo.latLng, 15f),
                    durationMs = 600
                )
            }
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
            onMapClick = { onSelectPhoto(null) }
        ) {
            uiState.photos.forEach { photo ->
                val isSelected = photo.id == uiState.selectedPhoto?.id
                Marker(
                    state = MarkerState(position = photo.latLng),
                    title = photo.formattedTimestamp,
                    snippet = photo.address ?: "住所を取得中...",
                    icon = BitmapDescriptorFactory.defaultMarker(
                        if (isSelected) BitmapDescriptorFactory.HUE_AZURE
                        else BitmapDescriptorFactory.HUE_RED
                    ),
                    onClick = {
                        onSelectPhoto(photo)
                        false
                    }
                )
            }
        }

        // ── 上部ツールバー ────────────────────────────────────────────────────
        TopBar(
            photoCount = uiState.photos.size,
            onPickPhotos = onPickPhotos,
            onClearPhotos = onClearPhotos,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        )

        // ── 全体にフィット FAB ──────────────────────────────────────────────
        if (uiState.photos.isNotEmpty()) {
            SmallFloatingActionButton(
                onClick = {
                    val bounds = buildBounds(uiState.photos.map { it.latLng })
                    scope.launch {
                        cameraPositionState.animate(
                            CameraUpdateFactory.newLatLngBounds(bounds, 120),
                            durationMs = 600
                        )
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 180.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "全体表示")
            }
        }

        // ── 選択中写真の詳細カード ────────────────────────────────────────────
        AnimatedVisibility(
            visible = uiState.selectedPhoto != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 150.dp)
        ) {
            uiState.selectedPhoto?.let { photo ->
                PhotoDetailCard(
                    photo = photo,
                    onClose = { onSelectPhoto(null) }
                )
            }
        }

        // ── 下部サムネイル一覧 ────────────────────────────────────────────────
        AnimatedVisibility(
            visible = uiState.photos.isNotEmpty() && uiState.selectedPhoto == null,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        ) {
            PhotoThumbnailRow(
                photos = uiState.photos,
                selectedId = uiState.selectedPhoto?.id,
                onPhotoClick = onSelectPhoto
            )
        }

        // ── ローディングオーバーレイ ──────────────────────────────────────────
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
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

        // ── エラー Snackbar ────────────────────────────────────────────────────
        uiState.errorMessage?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) {
                Text(msg)
            }
        }
    }
}

// ── 上部ツールバー ─────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    photoCount: Int,
    onPickPhotos: () -> Unit,
    onClearPhotos: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp))
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    "PhotoMonster",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (photoCount > 0) {
                    Text(
                        "${photoCount} 枚の位置情報",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (photoCount > 0) {
                IconButton(onClick = onClearPhotos) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = "クリア",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            FilledTonalButton(
                onClick = onPickPhotos,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    Icons.Default.AddPhotoAlternate,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("写真を選択", fontSize = 13.sp)
            }
        }
    }
}

// ── 写真詳細カード ─────────────────────────────────────────────────────────────

@Composable
private fun PhotoDetailCard(
    photo: PhotoLocation,
    onClose: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            // サムネイル
            AsyncImage(
                model = photo.uri,
                contentDescription = "写真",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
            // テキスト情報
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    photo.formattedTimestamp,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "緯度: %.6f".format(photo.latLng.latitude),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "経度: %.6f".format(photo.latLng.longitude),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                photo.address?.let { addr ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        addr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // 閉じるボタン
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape
                    )
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "閉じる",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ── 下部サムネイル横スクロール一覧 ────────────────────────────────────────────

@Composable
private fun PhotoThumbnailRow(
    photos: List<PhotoLocation>,
    selectedId: Int?,
    onPhotoClick: (PhotoLocation) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text(
                "撮影地点一覧",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(photos, key = { it.id }) { photo ->
                    val isSelected = photo.id == selectedId
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPhotoClick(photo) }
                            .then(
                                if (isSelected) Modifier.background(
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(12.dp)
                                ) else Modifier
                            )
                            .padding(if (isSelected) 3.dp else 0.dp)
                    ) {
                        AsyncImage(
                            model = photo.uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(if (isSelected) 10.dp else 12.dp))
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ── ユーティリティ ─────────────────────────────────────────────────────────────

/** 写真の緯度経度リストから LatLngBounds を構築 */
private fun buildBounds(points: List<LatLng>): LatLngBounds {
    val builder = LatLngBounds.Builder()
    points.forEach { builder.include(it) }
    return builder.build()
}
