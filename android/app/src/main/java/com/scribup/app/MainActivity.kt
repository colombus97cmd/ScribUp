package com.scribup.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.scribup.app.ui.AppRoot
import com.scribup.app.ui.theme.ScribUpTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScribUpTheme {
                AppRoot(viewModel)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.flush()
    }
}
