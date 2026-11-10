package com.wembassy.promptcam.network

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * Android Service that registers mDNS/NSD services for PromptCam discovery.
 *
 * Registers two services on the local network:
 * - "promptcam._tcp" on the video stream port (default 8554)
 * - "promptcam-tp._tcp" on the control port (default 8555)
 *
 * This allows desktop clients on the same Wi-Fi network to discover
 * and connect to the phone automatically without manually entering an IP address.
 *
 * The service runs while the app is active. It properly registers and
 * unregisters services to avoid stale DNS entries.
 */
class DiscoveryService : Service() {

    companion object {
        private const val TAG = "DiscoveryService"
        private const val SERVICE_TYPE = "_tcp"

        const val EXTRA_VIDEO_PORT = "video_port"
        const val EXTRA_CONTROL_PORT = "control_port"
        const val DEFAULT_VIDEO_PORT = 8554
        const val DEFAULT_CONTROL_PORT = 8555

        /**
         * Start the discovery service with the specified ports.
         */
        fun start(context: Context, videoPort: Int, controlPort: Int) {
            val intent = Intent(context, DiscoveryService::class.java).apply {
                putExtra(EXTRA_VIDEO_PORT, videoPort)
                putExtra(EXTRA_CONTROL_PORT, controlPort)
            }
            context.startService(intent)
        }

        /**
         * Stop the discovery service.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, DiscoveryService::class.java))
        }
    }

    private var nsdManager: NsdManager? = null
    private var videoRegistrationListener: NsdManager.RegistrationListener? = null
    private var controlRegistrationListener: NsdManager.RegistrationListener? = null

    private var videoPort: Int = DEFAULT_VIDEO_PORT
    private var controlPort: Int = DEFAULT_CONTROL_PORT

    override fun onCreate() {
        super.onCreate()
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        Log.i(TAG, "DiscoveryService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            videoPort = it.getIntExtra(EXTRA_VIDEO_PORT, DEFAULT_VIDEO_PORT)
            controlPort = it.getIntExtra(EXTRA_CONTROL_PORT, DEFAULT_CONTROL_PORT)
        }

        registerServices()
        return START_STICKY
    }

    /**
     * Register both mDNS services.
     */
    private fun registerServices() {
        // Register video stream service
        val videoService = NsdServiceInfo().apply {
            serviceName = "promptcam"
            serviceType = SERVICE_TYPE
            this.port = videoPort
        }

        videoRegistrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(service: NsdServiceInfo) {
                Log.i(TAG, "Video service registered: ${service.serviceName} on port $videoPort")
            }

            override fun onRegistrationFailed(service: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Video service registration failed: error code $errorCode")
            }

            override fun onServiceUnregistered(service: NsdServiceInfo) {
                Log.i(TAG, "Video service unregistered")
            }

            override fun onUnregistrationFailed(service: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Video service unregistration failed: error code $errorCode")
            }
        }

        // Register control service
        val controlService = NsdServiceInfo().apply {
            serviceName = "promptcam-tp"
            serviceType = SERVICE_TYPE
            this.port = controlPort
        }

        controlRegistrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(service: NsdServiceInfo) {
                Log.i(TAG, "Control service registered: ${service.serviceName} on port $controlPort")
            }

            override fun onRegistrationFailed(service: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Control service registration failed: error code $errorCode")
            }

            override fun onServiceUnregistered(service: NsdServiceInfo) {
                Log.i(TAG, "Control service unregistered")
            }

            override fun onUnregistrationFailed(service: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Control service unregistration failed: error code $errorCode")
            }
        }

        try {
            nsdManager?.registerService(
                videoService,
                NsdManager.PROTOCOL_DNS_SD,
                videoRegistrationListener
            )
            Log.i(TAG, "Registering video service 'promptcam' on port $videoPort...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register video NSD service", e)
        }

        try {
            nsdManager?.registerService(
                controlService,
                NsdManager.PROTOCOL_DNS_SD,
                controlRegistrationListener
            )
            Log.i(TAG, "Registering control service 'promptcam-tp' on port $controlPort...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register control NSD service", e)
        }
    }

    /**
     * Unregister all services.
     */
    private fun unregisterServices() {
        try {
            videoRegistrationListener?.let {
                nsdManager?.unregisterService(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering video service", e)
        }

        try {
            controlRegistrationListener?.let {
                nsdManager?.unregisterService(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering control service", e)
        }

        videoRegistrationListener = null
        controlRegistrationListener = null
    }

    override fun onDestroy() {
        unregisterServices()
        Log.i(TAG, "DiscoveryService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
