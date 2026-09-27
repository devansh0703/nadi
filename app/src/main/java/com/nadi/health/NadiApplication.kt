package com.nadi.health

import android.app.Application
import com.nadi.health.data.local.NadiDatabase
import com.nadi.health.data.local.NadiRepository
import com.nadi.health.health.HealthConnectManager
import com.nadi.health.ml.qwen.QwenEngine

/**
 * Application-scoped singletons.
 *
 * The database is opened lazily so cold start is not blocked by disk I/O, and
 * the [HealthConnectManager] is created once because the Health Connect client
 * holds IPC state worth reusing.
 */
class NadiApplication : Application() {

    val repository: NadiRepository by lazy {
        NadiRepository(NadiDatabase.get(this).dao())
    }

    val healthConnect: HealthConnectManager by lazy {
        HealthConnectManager(this)
    }

    /**
     * The on-device Qwen3-4B engine (official GenieX SDK, in-process).
     *
     * Application-scoped on purpose: the Hexagon holds exactly one model
     * context, so two engines would fight over the same 3.4 GB of shared
     * buffers. Every AI screen shares this one. Call [QwenEngine.initialize]
     * once before the first generate (the AI tab does this on startup).
     */
    val qwen: QwenEngine by lazy {
        QwenEngine(this)
    }
}
