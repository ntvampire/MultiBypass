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
    fun SetBufferSizeKb(kb: Int)
    fun SetCfProxyCacheDir(cacheDir: String)
    fun SetCfProxyConfig(enabled: Int, priority: Int, userDomain: String)
    fun SetCfWorkerDomains(domains: String)
    fun SetFakeTlsDomain(domain: String)
    fun SetDisableSecure(v: Int)
    fun SetForceTestDc(v: Int)
    fun SetProxyProtocol(v: Int)
    fun GetSecretWithPrefix(): Pointer?
    fun GetStats(): Pointer?
    fun FreeString(p: Pointer)
}

object NativeTgProxy {
    fun startProxy(
        host: String = "127.0.0.1",
        port: Int = 1443,
        dcIps: String = "",
        secret: String,
        verbose: Boolean = true
    ): Int {
        return TgProxyLibrary.INSTANCE.StartProxy(
            host,
            port,
            dcIps,
            secret,
            if (verbose) 1 else 0
        )
    }

    fun stopProxy(): Int {
        return TgProxyLibrary.INSTANCE.StopProxy()
    }

    fun setPoolSize(size: Int) {
        try {
            TgProxyLibrary.INSTANCE.SetPoolSize(size)
        } catch (e: Throwable) {
            android.util.Log.w("NativeTgProxy", "Failed to set pool size: ${e.message}")
        }
    }

    fun setBufferSizeKb(kb: Int) {
        try {
            TgProxyLibrary.INSTANCE.SetBufferSizeKb(kb)
        } catch (e: Throwable) {
            android.util.Log.w("NativeTgProxy", "Failed to set buffer size: ${e.message}")
        }
    }

    fun setCfProxyCacheDir(cacheDir: String) {
        try {
            TgProxyLibrary.INSTANCE.SetCfProxyCacheDir(cacheDir)
        } catch (e: Throwable) {
            android.util.Log.w("NativeTgProxy", "Failed to set CF proxy cache dir: ${e.message}")
        }
    }

    fun setCfProxyConfig(enabled: Boolean, priority: Boolean = true, userDomain: String = "") {
        try {
            TgProxyLibrary.INSTANCE.SetCfProxyConfig(
                if (enabled) 1 else 0,
                if (priority) 1 else 0,
                userDomain
            )
        } catch (e: Throwable) {
            android.util.Log.w("NativeTgProxy", "Failed to set CF proxy config: ${e.message}")
        }
    }

    fun setCfWorkerDomains(domains: String) {
        try {
            TgProxyLibrary.INSTANCE.SetCfWorkerDomains(domains)
        } catch (e: Throwable) {
            android.util.Log.w("NativeTgProxy", "Failed to set CF worker domains: ${e.message}")
        }
    }

    fun setFakeTlsDomain(domain: String) {
        try {
            TgProxyLibrary.INSTANCE.SetFakeTlsDomain(domain)
        } catch (e: Throwable) {
            android.util.Log.w("NativeTgProxy", "Failed to set fake TLS domain: ${e.message}")
        }
    }

    fun setDisableSecure(disableSecure: Boolean) {
        try {
            TgProxyLibrary.INSTANCE.SetDisableSecure(if (disableSecure) 1 else 0)
        } catch (e: Throwable) {
            android.util.Log.w("NativeTgProxy", "Failed to set disable secure: ${e.message}")
        }
    }

    fun setForceTestDc(forceTestDc: Boolean) {
        try {
            TgProxyLibrary.INSTANCE.SetForceTestDc(if (forceTestDc) 1 else 0)
        } catch (e: Throwable) {
            android.util.Log.w("NativeTgProxy", "Failed to set force test DC: ${e.message}")
        }
    }

    fun setProxyProtocol(enabled: Boolean) {
        try {
            TgProxyLibrary.INSTANCE.SetProxyProtocol(if (enabled) 1 else 0)
        } catch (e: Throwable) {
            android.util.Log.w("NativeTgProxy", "Failed to set proxy protocol: ${e.message}")
        }
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
