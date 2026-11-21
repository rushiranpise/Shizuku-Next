package moe.shizuku.manager.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue

@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    context: Context, private val serviceType: String,
    private val observer: Observer<Pair<String, Int>>
) {

    @Volatile
    private var running = false

    @Volatile
    private var serviceName: String? = null

    // A fresh one for every search: NsdManager refuses a listener it has not finished with, and a
    // search that is stopped and started again straight away has not finished with the last one.
    @Volatile
    private var listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)

    /**
     * The service being resolved, and the ones found while it was.
     *
     * NsdManager resolves one service at a time on Android 11 to 13: a second resolve asked
     * for while the first is out is refused, and the service is simply never looked at. A
     * device that advertises more than one adb port - the phone's own, a stale announcement, a
     * second adb instance - therefore lost every port after the first to a race that looked like
     * discovery finding nothing. Queued and resolved one after another instead.
     */
    @Volatile
    private var pendingResolve: NsdServiceInfo? = null
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolveLock = Any()

    fun start() {
        if (running) return
        running = true
        listener = DiscoveryListener(this)
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            // Discovery that could not be started is not discovery that is running: leaving
            // this set made every later start() in this instance return as if the search were
            // under way, and the start that needed it waited out its whole timeout.
            Log.w(TAG, "onStartDiscoveryFailed: $serviceType", e)
            running = false
        }
    }

    fun stop() {
        if (!running) return
        running = false
        // Anything still queued belongs to a search that is over, and resolving it would hand
        // a closed flow a port it can no longer use.
        synchronized(resolveLock) {
            resolveQueue.clear()
            pendingResolve = null
        }
        // Asked for whether or not the started callback has arrived yet: discovery can be
        // starting when this is called, and the request to stop it is what ends it either way.
        // The old check only stopped a search this class had been told had started, so a stop
        // in the moment before that callback left it running for the rest of the process.
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            Log.w(TAG, "onStopDiscoveryFailed: $serviceType", e)
        }
    }

    private fun onServiceFound(info: NsdServiceInfo) {
        synchronized(resolveLock) {
            if (pendingResolve == null) {
                resolve(info)
            } else {
                resolveQueue.add(info)
            }
        }
    }

    /**
     * Resolves [info], and forgets it if the platform refuses so the queue is not stuck behind
     * a service that will never answer.
     */
    private fun resolve(info: NsdServiceInfo) {
        pendingResolve = info
        try {
            nsdManager.resolveService(info, ResolveListener(this))
        } catch (e: Exception) {
            Log.w(TAG, "onResolveFailed: ${info.serviceName}", e)
            drainResolveQueue()
        }
    }

    private fun onResolveFailed() {
        drainResolveQueue()
    }

    private fun drainResolveQueue() {
        synchronized(resolveLock) {
            pendingResolve = null
            if (!running) {
                resolveQueue.clear()
                return
            }
            val next = resolveQueue.poll() ?: return
            resolve(next)
        }
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) observer.onChanged("" to -1)
    }

    private fun onServiceResolved(resolvedService: NsdServiceInfo) {
        val host = resolvedService.host?.hostAddress ?: return
        if (running && NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .any { networkInterface ->
                    networkInterface.inetAddresses
                        .asSequence()
                        .any { host == it.hostAddress }
                }
            && isPortAvailable(host, resolvedService.port)
        ) {
            serviceName = resolvedService.serviceName
            observer.onChanged(host to resolvedService.port)
        }
        drainResolveQueue()
    }

    private fun isPortAvailable(host: String, port: Int) = try {
        ServerSocket().use {
            it.bind(InetSocketAddress(host, port), 1)
            false
        }
    } catch (e: IOException) {
        true
    }

    internal class DiscoveryListener(private val adbMdns: AdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.v(TAG, "onDiscoveryStarted: $serviceType")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStartDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.v(TAG, "onDiscoveryStopped: $serviceType")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStopDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceFound: ${serviceInfo.serviceName}")

            adbMdns.onServiceFound(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceLost: ${serviceInfo.serviceName}")

            adbMdns.onServiceLost(serviceInfo)
        }
    }

    internal class ResolveListener(private val adbMdns: AdbMdns) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, i: Int) {
            Log.w(TAG, "onResolveFailed: ${nsdServiceInfo.serviceName}, code=$i")
            adbMdns.onResolveFailed()
        }

        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            adbMdns.onServiceResolved(nsdServiceInfo)
        }

    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "AdbMdns"
    }
}
