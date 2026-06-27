package com.ead.yukitoki.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.ead.yukitoki.manager.AndroidDownloadPathContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lets the manager resolve a default Downloads directory if a request omits a path.
        AndroidDownloadPathContext.register(applicationContext)
        setContent {
            val environment = remember { AndroidDownloadEnvironment(applicationContext) }
            App(environment)
        }
    }
}
