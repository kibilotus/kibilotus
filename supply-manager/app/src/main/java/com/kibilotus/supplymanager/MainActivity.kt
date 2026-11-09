package com.kibilotus.supplymanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kibilotus.supplymanager.data.settings.ThemeMode
import com.kibilotus.supplymanager.ui.navigation.SupplyApp
import com.kibilotus.supplymanager.ui.theme.SupplyManagerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settingsStore = (application as SupplyApplication).container.settingsStore

        setContent {
            val themeMode by settingsStore.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.DEFAULT)

            SupplyManagerTheme(themeMode = themeMode) {
                SupplyApp()
            }
        }
    }
}
