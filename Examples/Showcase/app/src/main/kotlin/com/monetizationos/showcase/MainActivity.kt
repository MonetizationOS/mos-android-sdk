package com.monetizationos.showcase

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.view.WindowCompat
import com.monetizationos.showcase.support.LocalShowcase
import com.monetizationos.showcase.support.SettingsScreen
import com.monetizationos.showcase.support.Showcase

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.enableEdgeToEdge(window)
        super.onCreate(savedInstanceState)
        // UI tests and scripts: `--ez SHOWCASE_RESET true` starts in fixture mode with a new anonymous reader.
        val showcase = Showcase.get(this, resetAtLaunch = intent.getBooleanExtra(RESET_EXTRA, false))
        setContent {
            val dark = isSystemInDarkTheme()
            // Edge to edge: the system bar icons follow the app's theme, not the window's.
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).run {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalShowcase provides showcase) {
                    // Test tags as resource ids, so adb and UiAutomator can find controls too.
                    Box(Modifier.semantics { testTagsAsResourceId = true }) {
                        App()
                    }
                }
            }
        }
    }

    companion object {
        const val RESET_EXTRA = "SHOWCASE_RESET"
    }
}

/** Home, one screen at a time on top of it, or Settings. */
@Composable
private fun App() {
    var screen by rememberSaveable { mutableStateOf<ShowcaseScreen?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val current = screen
    when {
        settings -> {
            BackHandler { settings = false }
            SettingsScreen(LocalShowcase.current) { settings = false }
        }
        current != null -> {
            BackHandler { screen = null }
            current.Content { screen = null }
        }
        else -> HomeScreen(onOpen = { screen = it }, onSettings = { settings = true })
    }
}
