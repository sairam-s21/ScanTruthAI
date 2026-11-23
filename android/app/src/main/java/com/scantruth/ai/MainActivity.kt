package com.scantruth.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.scantruth.ai.ui.ScanTruthAppShell
import com.scantruth.ai.ui.theme.ScanTruthTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ScanTruthTheme {
                ScanTruthAppShell()
            }
        }
    }
}
