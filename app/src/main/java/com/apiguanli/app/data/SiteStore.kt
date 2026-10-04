package com.apiguanli.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** Local-only persistence of sites in a JSON file inside the app's private storage. */
object SiteStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private lateinit var file: File
    private val _sites = MutableStateFlow<List<Site>>(emptyList())
    val sites: StateFlow<List<Site>> = _sites

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "sites.json")
        _sites.value = runCatching { json.decodeFromString<List<Site>>(file.readText()) }.getOrDefault(emptyList())
    }

    fun get(id: String): Site? = _sites.value.firstOrNull { it.id == id }

    suspend fun upsert(site: Site) = update { list ->
        if (list.any { it.id == site.id }) list.map { if (it.id == site.id) site else it } else list + site
    }

    suspend fun modify(id: String, change: (Site) -> Site) = update { list ->
        list.map { if (it.id == id) change(it) else it }
    }

    suspend fun delete(id: String) = update { list -> list.filterNot { it.id == id } }

    private suspend fun update(transform: (List<Site>) -> List<Site>) = mutex.withLock {
        val next = transform(_sites.value)
        withContext(Dispatchers.IO) {
            val tmp = File(file.parentFile, "sites.json.tmp")
            tmp.writeText(json.encodeToString(next))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
        _sites.value = next
    }
}
