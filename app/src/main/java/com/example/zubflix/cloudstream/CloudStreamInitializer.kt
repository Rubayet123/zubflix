package com.example.zubflix.cloudstream

import android.content.Context

object CloudStreamInitializer {
    fun init(context: Context) {
        com.example.CloudStreamInitializer.init(context)
    }

    fun isInitialized(): Boolean = com.example.CloudStreamInitializer.isInitialized()
}
