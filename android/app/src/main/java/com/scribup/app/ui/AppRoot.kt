package com.scribup.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scribup.app.MainViewModel

@Composable
fun AppRoot(vm: MainViewModel) {
    val current by vm.current.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }

    when {
        showSettings -> {
            BackHandler { showSettings = false }
            SettingsScreen(vm, onBack = { showSettings = false })
        }
        current != null -> {
            BackHandler { vm.close() }
            SongScreen(vm, onBack = { vm.close() }, onSettings = { showSettings = true })
        }
        else -> SongListScreen(vm, onSettings = { showSettings = true })
    }
}
