package com.apiguanli.app

import android.content.Intent
import androidx.lifecycle.LifecycleCoroutineScope

object DebugHooks {
    @Suppress("UNUSED_PARAMETER")
    fun handle(intent: Intent?, scope: LifecycleCoroutineScope) = Unit
}
