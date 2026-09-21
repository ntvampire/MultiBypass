package io.github.romanvht.byedpi.core

object ByeDpiProxy {
    init {
        System.loadLibrary("byedpi")
    }

    external fun jniStartProxy(args: Array<String>): Int
    external fun jniStopProxy(): Int
    external fun jniForceClose(): Int
    external fun setDnsRedirectPort(port: Int)
}
