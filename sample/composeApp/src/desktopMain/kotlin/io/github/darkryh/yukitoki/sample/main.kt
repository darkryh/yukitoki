package io.github.darkryh.yukitoki.sample

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Yukitoki Sample") {
        App()
    }
}
