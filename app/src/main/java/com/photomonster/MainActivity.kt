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

                // ── 写真選択ランチャー ──────────────────────────────────────────────
                // GetMultipleContents を使う理由:
                //   MediaStore URI (content://media/external/...) を返すため
                //   setRequireOriginal() でGPS隠蔽解除が可能。
                //   PickMultipleVisualMedia (Photo Picker) のURI形式では不可。
                val getContentLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetMultipleContents()
                ) { uris ->
                    viewModel.processSelectedUris(uris)
                }

                // ── 権限リクエストランチャー (複数権限を同時に要求) ──────────────────
                val permissionsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { _ ->
                    // 権限結果に関わらず写真選択を起動
                    // (権限なしの場合はGPS情報だけ取れないのでスキップ扱いになる)
                    getContentLauncher.launch("image/*")
                }

                val onPickPhotos: () -> Unit = {
                    // 必要な権限を収集
                    val neededPermissions = mutableListOf<String>()

                    // Android 13+ は READ_MEDIA_IMAGES (画像の読み込みに必要)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                            != PackageManager.PERMISSION_GRANTED) {
                            neededPermissions.add(Manifest.permission.READ_MEDIA_IMAGES)
                        }
                    } else {
                        // Android 12以下は READ_EXTERNAL_STORAGE
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                            != PackageManager.PERMISSION_GRANTED) {
                            neededPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    }

                    // ACCESS_MEDIA_LOCATION (GPS読み取り用)
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                        neededPermissions.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
                    }

                    if (neededPermissions.isEmpty()) {
                        getContentLauncher.launch("image/*")
                    } else {
                        permissionsLauncher.launch(neededPermissions.toTypedArray())
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
                                        onSelectSpot = { viewModel.selectSpot(it) },
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
