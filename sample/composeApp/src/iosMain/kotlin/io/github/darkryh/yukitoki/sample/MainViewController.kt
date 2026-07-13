package io.github.darkryh.yukitoki.sample

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Entry point consumed by the SwiftUI `iosApp` wrapper (see sample/iosApp). */
fun MainViewController(): UIViewController = ComposeUIViewController {
    App()
}
