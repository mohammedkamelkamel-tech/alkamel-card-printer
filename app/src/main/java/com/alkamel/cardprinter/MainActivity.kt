package com.alkamel.cardprinter

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.alkamel.cardprinter.ui.navigation.AppNavGraph
import com.alkamel.cardprinter.ui.theme.AlkamelTheme

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AlkamelTheme {
                AppNavGraph()
            }
        }
    }
}
