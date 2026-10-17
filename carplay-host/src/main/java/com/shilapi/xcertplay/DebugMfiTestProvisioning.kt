package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ApplicationInfo
import com.shilapi.xcertplay.mfi.LocalMfiIdentityStore
import java.io.File
import java.io.IOException

/** Only a debuggable APK can provision an explicitly packaged local test identity. */
internal object DebugMfiTestProvisioning {
    fun provisionIfAbsent(context: Context): Boolean {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
        val assets = context.assets.list("offline-mfi").orEmpty().toSet()
        if ("identity.pk8" !in assets && "certificate.p7b" !in assets) return false
        if ("identity.pk8" !in assets || "certificate.p7b" !in assets) {
            throw IOException("Debug MFi test identity is incomplete")
        }
        val storeFile = File(context.noBackupFilesDir, LocalMfiIdentityStore.FILE_NAME)
        if (storeFile.exists()) {
            LocalMfiIdentityStore(storeFile).load()
            return false
        }
        val key = context.assets.open("offline-mfi/identity.pk8").use(
            LocalMfiIdentityStore::readDocument,
        )
        try {
            val certificate = context.assets.open("offline-mfi/certificate.p7b").use(
                LocalMfiIdentityStore::readDocument,
            )
            LocalMfiIdentityStore(storeFile).replace(key, certificate)
        } finally {
            key.fill(0)
        }
        return true
    }
}
