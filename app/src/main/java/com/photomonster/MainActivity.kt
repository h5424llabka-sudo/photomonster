package com.photomonster

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.SportsKabaddi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photomonster.ui.BattleScreen
import com.photomonster.ui.CaptureScreen
import com.photomonster.ui.InventoryScreen
import com.photomonster.ui.MapScreen
import com.photomonster.ui.PartyScreen
import com.photomonster.ui.theme.PhotoMonsterTheme
import com.photomonster.viewmodel.MapViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PhotoMonsterTheme {
                val viewModel: MapViewModel = viewModel()
                val uiState by viewModel.uiState.collectAsState()

                // ── 写真選択ランチャー ─────────────────────────────────────────────
                // GetMultipleContents を使う理由:
                //   PickMultipleVisualMedia (Photo Picker) が返す URI は
                //   content://com.android.providers.media.photopicker/... 形式で
                //   MediaStore.setRequireOriginal() に非対応。
                //   GetMultipleContents は content://media/external/... の
                //   通常の MediaStore URI を返すため GPS 取得が可能。
                val getContentLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetMultipleContents()
                ) { uris ->
                    viewModel.processSelectedUris(uris)
                }

                // ── ACCESS_MEDIA_LOCATION 権限リクエスト ──────────────────────────
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { _ ->
                    getContentLauncher.launch("image/*")
                }

                val onPickPhotos = {
                    val permission = Manifest.permission.ACCESS_MEDIA_LOCATION
                    if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                        getContentLauncher.launch("image/*")
                    } else {
                        permissionLauncher.launch(permission)
                    }
                }

                // ── 画面ルーティング ──────────────────────────────────────────────
                when {
                    uiState.isBattleMode -> {
                        BattleScreen(
                            battleState = uiState.battleState,
                            onActivateSkill = { viewModel.activateSkill() },
                            onSwitchPlayer = { viewModel.switchPlayer(it) },
                            onExit = { viewModel.exitBattleMode() }
                        )
                    }
                    uiState.encounteringMonster != null -> {
                        CaptureScreen(
                            monster = uiState.encounteringMonster!!,
                            captureCubes = uiState.captureCubes,
                            onAttemptCapture = { viewModel.attemptCapture() },
                            onFlee = { viewModel.fleeEncounter() }
                        )
                    }
                    else -> {
                        // ── 下部ナビゲーション付きメイン画面 ─────────────────────────
                        var selectedTab by remember { mutableStateOf(0) }

                        Scaffold(
                            bottomBar = {
                                NavigationBar {
                                    NavigationBarItem(
                                        selected = selectedTab == 0,
                                        onClick = { selectedTab = 0 },
                                        icon = { Icon(Icons.Default.Map, contentDescription = "マップ") },
                                        label = { Text("マップ") }
                                    )
                                    NavigationBarItem(
                                        selected = selectedTab == 1,
                                        onClick = { selectedTab = 1 },
                                        icon = { Icon(Icons.Default.Inventory, contentDescription = "道具") },
                                        label = { Text("道具") }
                                    )
                                    NavigationBarItem(
                                        selected = selectedTab == 2,
                                        onClick = { selectedTab = 2 },
                                        icon = { Icon(Icons.Default.Pets, contentDescription = "モンスター") },
                                        label = { Text("モンスター") }
                                    )
                                    NavigationBarItem(
                                        selected = false,
                                        enabled = uiState.caughtMonsters.isNotEmpty(),
                                        onClick = { viewModel.enterBattleMode() },
                                        icon = { Icon(Icons.Default.SportsKabaddi, contentDescription = "バトル") },
                                        label = { Text("バトル") }
                                    )
                                }
                            }
                        ) { innerPadding ->
                            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                                when (selectedTab) {
                                    0 -> MapScreen(
                                        uiState = uiState,
                                        onPickPhotos = onPickPhotos,
                                        onSelectCluster = { viewModel.selectCluster(it) },
                                        onCollectItem = { viewModel.collectItemFromSpot(it) },
                                        onEncounterMonster = { viewModel.encounterMonster(it) },
                                        onClearPhotos = { viewModel.clearPhotos() }
                                    )
                                    1 -> InventoryScreen(
                                        captureCubes = uiState.captureCubes
                                    )
                                    2 -> PartyScreen(
                                        monsters = uiState.caughtMonsters
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
