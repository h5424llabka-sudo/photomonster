package com.photomonster

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import com.photomonster.ui.MonsterHuntScreen
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

                // ── 写真選択ランチャー ──────────────────────────────────────────
                val getContentLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetMultipleContents()
                ) { uris -> viewModel.processSelectedUris(uris) }

                // ── 複数権限リクエスト ──────────────────────────────────────────
                val permissionsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { _ ->
                    // 権限結果に関わらず写真選択を起動
                    getContentLauncher.launch("image/*")
                }

                val onPickPhotos: () -> Unit = {
                    val needed = mutableListOf<String>()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                            != PackageManager.PERMISSION_GRANTED)
                            needed.add(Manifest.permission.READ_MEDIA_IMAGES)
                    } else {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                            != PackageManager.PERMISSION_GRANTED)
                            needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION)
                        != PackageManager.PERMISSION_GRANTED)
                        needed.add(Manifest.permission.ACCESS_MEDIA_LOCATION)

                    if (needed.isEmpty()) getContentLauncher.launch("image/*")
                    else permissionsLauncher.launch(needed.toTypedArray())
                }

                // ── 画面ルーティング ────────────────────────────────────────────
                when {
                    // バトル画面
                    uiState.isBattleMode -> {
                        BattleScreen(
                            battleState = uiState.battleState,
                            onActivateSkill = { viewModel.activateSkill() },
                            onSwitchPlayer  = { viewModel.switchPlayer(it) },
                            onExit = { viewModel.exitBattleMode() }
                        )
                    }
                    // モンスター捕獲画面
                    uiState.encounteringMonster != null -> {
                        CaptureScreen(
                            monster = uiState.encounteringMonster!!,
                            captureCubes = uiState.captureCubes,
                            onAttemptCapture = { viewModel.attemptCapture() },
                            onFlee = { viewModel.fleeEncounter() }
                        )
                    }
                    // モンスター探索画面（スポット→探索）
                    uiState.huntingSpot != null -> {
                        MonsterHuntScreen(
                            spot = uiState.huntingSpot!!,
                            monsters = uiState.huntingMonsters,
                            onEncounterMonster = { viewModel.encounterMonster(it) },
                            onBack = { viewModel.exitHuntMode() }
                        )
                    }
                    // メイン（マップ + 下部ナビ）
                    else -> {
                        var selectedTab by remember { mutableStateOf(0) }

                        Scaffold(
                            bottomBar = {
                                NavigationBar {
                                    NavigationBarItem(
                                        selected = selectedTab == 0,
                                        onClick  = { selectedTab = 0 },
                                        icon     = { Icon(Icons.Default.Map, "マップ") },
                                        label    = { Text("マップ") }
                                    )
                                    NavigationBarItem(
                                        selected = selectedTab == 1,
                                        onClick  = { selectedTab = 1 },
                                        icon     = { Icon(Icons.Default.Inventory, "道具") },
                                        label    = { Text("道具") }
                                    )
                                    NavigationBarItem(
                                        selected = selectedTab == 2,
                                        onClick  = { selectedTab = 2 },
                                        icon     = { Icon(Icons.Default.Pets, "モンスター") },
                                        label    = { Text("モンスター") }
                                    )
                                    NavigationBarItem(
                                        selected = false,
                                        enabled  = uiState.caughtMonsters.isNotEmpty(),
                                        onClick  = { viewModel.enterBattleMode() },
                                        icon     = { Icon(Icons.Default.SportsKabaddi, "バトル") },
                                        label    = { Text("バトル") }
                                    )
                                }
                            }
                        ) { innerPadding ->
                            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                                when (selectedTab) {
                                    0 -> MapScreen(
                                        uiState = uiState,
                                        onPickPhotos    = onPickPhotos,
                                        onSelectSpot    = { viewModel.selectSpot(it) },
                                        onCollectItem   = { viewModel.collectItemFromSpot(it) },
                                        onEnterHuntMode = { viewModel.enterHuntMode(it) },
                                        onClearPhotos   = { viewModel.clearPhotos() }
                                    )
                                    1 -> InventoryScreen(captureCubes = uiState.captureCubes)
                                    2 -> PartyScreen(monsters = uiState.caughtMonsters)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
