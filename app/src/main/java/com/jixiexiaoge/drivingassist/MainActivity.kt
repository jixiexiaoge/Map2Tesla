package com.jixiexiaoge.drivingassist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import com.jixiexiaoge.drivingassist.ui.theme.NavipilotTheme
import timber.log.Timber

/**
 * Tesla map projection app entry point.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Timber.treeCount == 0) {
            Timber.plant(Timber.DebugTree())
        }
        enableEdgeToEdge()
        setContent {
            NavipilotTheme {
                ProjectionHome()
            }
        }
    }
}