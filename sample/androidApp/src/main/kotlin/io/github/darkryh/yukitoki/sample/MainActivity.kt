package io.github.darkryh.yukitoki.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import io.github.darkryh.yukitoki.manager.AndroidDownloadPathContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidDownloadPathContext.register(applicationContext)
        setContent {
            val environment = remember { AndroidDownloadEnvironment(applicationContext) }
            App(environment)
        }
    }
}
