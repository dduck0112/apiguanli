package com.apiguanli.app

import android.content.Intent
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import com.apiguanli.app.data.Repo
import com.apiguanli.app.net.NewApiClient
import kotlinx.coroutines.launch

/**
 * Debug builds only: lets a developer add a site from an existing access token over adb,
 * e.g. `am start -n com.apiguanli.app/.MainActivity --es seed_url .. --es seed_uid .. --es seed_token ..`.
 * The release variant of this file is a no-op.
 */
object DebugHooks {
    fun handle(intent: Intent?, scope: LifecycleCoroutineScope) {
        val url = intent?.getStringExtra("seed_url") ?: return
        val uid = intent.getStringExtra("seed_uid")?.toIntOrNull() ?: return
        val token = intent.getStringExtra("seed_token") ?: return
        scope.launch {
            runCatching {
                val base = Repo.normalizeUrl(url)
                val status = NewApiClient(base).status()
                Repo.saveLogin(base, status, uid, token, "", null)
            }.onSuccess { Log.i("DebugHooks", "seeded ${it.name}") }
                .onFailure { Log.e("DebugHooks", "seed failed", it) }
        }
    }
}
