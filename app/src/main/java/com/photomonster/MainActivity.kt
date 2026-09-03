package com.photomonster

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photomonster.ui.MapScreen
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

                // ── 写真選択ランチャー ────────────────────────────────────────────
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

                // ── ACCESS_MEDIA_LOCATION 権限リクエスト ─────────────────────────
                // GPS 取得に必須の dangerous permission（runtime 許可が必要）
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    // 許可結果に関わらず写真選択を開始
                    // (granted=false でも GPS なし写真としてスキップするだけ)
                    getContentLauncher.launch("image/*")
                }

                MapScreen(
                    uiState = uiState,
                    onPickPhotos = {
                        val permission = Manifest.permission.ACCESS_MEDIA_LOCATION
                        if (ContextCompat.checkSelfPermission(this, permission)
                            == PackageManager.PERMISSION_GRANTED
                        ) {
                            // 既に許可済み → そのまま写真選択へ
                            getContentLauncher.launch("image/*")
                        } else {
                            // 未許可 → ダイアログ表示後、結果に関わらず写真選択へ
                            permissionLauncher.launch(permission)
                        }
                    },
                    onSelectCluster = { viewModel.selectCluster(it) },
                    onClearPhotos = { viewModel.clearPhotos() }
                )
            }
        }
    }
}
