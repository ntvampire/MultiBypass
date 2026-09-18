package com.multibypass.app.core.tgproxy

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

interface TgProxyLibrary : Library {
    companion object {
        val INSTANCE: TgProxyLibrary by lazy {
            Native.load("tgwsproxy", TgProxyLibrary::class.java) as TgProxyLibrary
        }
    }

    fun StartProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int
    fun StopProxy(): Int
    fun SetPoolSize(size: Int)
    fun SetCfProxyCacheDir(cacheDir: String)
    fun SetCfProxyConfig(enabled: Int, priority: Int, userDomain: String)
    fun GetSecretWithPrefix(): Pointer?
    fun GetStats(): Pointer?
    fun FreeString(p: Pointer)
}

object NativeTgProxy {
    fun startProxy(host: String = "127.0.0.1", port: Int = 1443, secret: String, verbose: Boolean = false): Int {
        return TgProxyLibrary.INSTANCE.StartProxy(
            host,
            port,
            "", // default telegram DC IPs
            secret,
            if (verbose) 1 else 0
        )
    }

    fun stopProxy(): Int {
        return TgProxyLibrary.INSTANCE.StopProxy()
    }

    fun getSecretWithPrefix(): String? {
        val ptr = TgProxyLibrary.INSTANCE.GetSecretWithPrefix() ?: return null
        val result = ptr.getString(0)
        TgProxyLibrary.INSTANCE.FreeString(ptr)
        return result
    }

    fun getStats(): String? {
        val ptr = TgProxyLibrary.INSTANCE.GetStats() ?: return null
        val result = ptr.getString(0)
        TgProxyLibrary.INSTANCE.FreeString(ptr)
        return result
    }
}
