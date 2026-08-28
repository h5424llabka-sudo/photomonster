package com.photomonster

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

                // Photo Picker ランチャー（複数選択対応）
                val photoPickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.PickMultipleVisualMedia()
                ) { uris ->
                    viewModel.processSelectedUris(uris)
                }

                MapScreen(
                    uiState = uiState,
                    onPickPhotos = {
                        // PickVisualMediaRequest で画像のみを指定して起動
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(PickVisualMedia.ImageOnly)
                        )
                    },
                    onSelectPhoto = { viewModel.selectPhoto(it) },
                    onClearPhotos = { viewModel.clearPhotos() }
                )
            }
        }
    }
}
