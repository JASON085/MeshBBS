package com.meshtastic.bbs.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Static BroadcastReceiver declared in the manifest so Meshtastic can target it
 * via subscribeReceiver(packageName, "com.meshtastic.bbs.data.MeshPacketReceiver").
 *
 * In Meshtastic 2.7.x, TEXT_MESSAGE_APP is delivered only to the component specified
 * in subscribeReceiver — dynamic receivers registered at runtime are NOT reached by
 * this directed broadcast.  A manifest-declared receiver is required.
 *
 * The handler is set by MeshtasticRepository while the flow is active, and cleared
 * on flow close so no stale callbacks linger.
 */
class MeshPacketReceiver : BroadcastReceiver() {

    companion object {
        /** Set by MeshtasticRepository.connect(); cleared in awaitClose. */
        @Volatile var handler: ((Intent) -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        handler?.invoke(intent)
    }
}
