package com.vpsmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = HostStore(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                App(store)
            }
        }
    }
}
