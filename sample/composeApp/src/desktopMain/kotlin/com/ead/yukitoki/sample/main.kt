package com.ead.yukitoki.sample

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Yukitoki Sample") {
        val environment = remember { DesktopDownloadEnvironment() }
        App(environment)
    }
}
