package com.wembassy.promptcam.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages mDNS/Bonjour service discovery for PromptCam using Android's NsdManager.
 * 
 * Registers the phone as "promptcam._tcp" on the specified port so that
 * desktop clients on the same Wi-Fi network can discover and connect to it
 * automatically without manually entering an IP address.
 * 
 * The service is registered with:
 * - Service type: "_tcp"
 * - Service name: "promptcam" 
 * - Port: The video streaming port (default 8554)
 * 
 * @param context Application or activity context
 */
class DiscoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "DiscoveryManager"
        private const val SERVICE_TYPE = "_tcp"
        private const val SERVICE_NAME = "promptcam"
    }

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    // Reactive state for UI
    private val _isRegistered = MutableStateFlow(false)
    val isRegistered: StateFlow<Boolean> = _isRegistered

    /**
     * Register the PromptCam service on the local network.
     * 
     * @param port The TCP port that the video stream server is listening on
     */
    fun registerService(port: Int) {
        if (_isRegistered.value) {
            Log.w(TAG, "Service already registered")
            return
        }

        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            this.port = port
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(service: NsdServiceInfo) {
                Log.i(TAG, "Service registered: ${service.serviceName} on port $port")
                _isRegistered.value = true
            }

            override fun onRegistrationFailed(service: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Service registration failed: error code $errorCode")
                _isRegistered.value = false
            }

            override fun onServiceUnregistered(service: NsdServiceInfo) {
                Log.i(TAG, "Service unregistered")
                _isRegistered.value = false
            }

            override fun onUnregistrationFailed(service: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Service unregistration failed: error code $errorCode")
            }
        }

        try {
            nsdManager?.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                registrationListener
            )
            Log.i(TAG, "Registering service '$SERVICE_NAME' on port $port...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NSD service", e)
        }
    }

    /**
     * Unregister the service and release resources.
     * Safe to call even if not registered.
     */
    fun unregisterService() {
        if (!_isRegistered.value && registrationListener == null) return

        try {
            registrationListener?.let { listener ->
                nsdManager?.unregisterService(listener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering service", e)
        }

        registrationListener = null
        _isRegistered.value = false
        Log.i(TAG, "Service unregistered")
    }
}
