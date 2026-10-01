package com.mcfrenchpants.activityledger.wear

import android.app.Application
import com.mcfrenchpants.activityledger.wear.capture.CaptureRuntime

/** Holds the one per-process [CaptureRuntime]. */
class WatchApplication : Application() {
    val runtime: CaptureRuntime by lazy { CaptureRuntime(this) }
}
