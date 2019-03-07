/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.caojun.demo.rosandroid.core

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.WifiLock
import android.os.*
import android.view.WindowManager
import com.google.common.base.Preconditions
import com.socks.library.KLog
import org.caojun.demo.rosandroid.R
import org.caojun.demo.rosandroid.utils.AppHelper
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.runOnUiThread
import org.ros.RosCore
import org.ros.concurrent.ListenerGroup
import org.ros.exception.RosRuntimeException
import org.ros.node.*

import java.net.URI
import java.util.concurrent.ScheduledExecutorService

/**
 * @author damonkohler@google.com (Damon Kohler)
 */
class NodeMainExecutorService : Service(), NodeMainExecutor {

    private val nodeMainExecutor: NodeMainExecutor
    private val binder: IBinder
    private val listeners: ListenerGroup<NodeMainExecutorServiceListener>

//    private var handler: Handler? = null
//    private var wakeLock: WakeLock? = null
    private var wifiLock: WifiLock? = null
    private var rosCore: RosCore? = null
    var masterUri: URI? = null
    var rosHostname: String? = null

    /**
     * Class for clients to access. Because we know this service always runs in
     * the same process as its clients, we don't need to deal with IPC.
     */
    inner class LocalBinder : Binder() {
        val service: NodeMainExecutorService
            get() = this@NodeMainExecutorService
    }

    init {
        rosHostname = null
        nodeMainExecutor = DefaultNodeMainExecutor.newDefault()
        binder = LocalBinder()
        listeners = ListenerGroup(
            nodeMainExecutor.scheduledExecutorService
        )
    }

    override fun onCreate() {
//        handler = Handler()
//        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
//        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
//        wakeLock?.acquire()
        var wifiLockType = WifiManager.WIFI_MODE_FULL
        try {
            wifiLockType = WifiManager::class.java.getField("WIFI_MODE_FULL_HIGH_PERF").getInt(null)
        } catch (e: Exception) {
            // We must be running on a pre-Honeycomb device.
            KLog.w(TAG, "Unable to acquire high performance wifi lock.")
        }

        val wifiManager = WifiManager::class.java.cast(applicationContext.getSystemService(Context.WIFI_SERVICE))
        wifiLock = wifiManager!!.createWifiLock(wifiLockType, TAG)
        wifiLock?.acquire()
    }

    override fun execute(
        nodeMain: NodeMain, nodeConfiguration: NodeConfiguration,
        nodeListeneners: Collection<NodeListener>?
    ) {
        nodeMainExecutor.execute(nodeMain, nodeConfiguration, nodeListeneners)
    }

    override fun execute(nodeMain: NodeMain, nodeConfiguration: NodeConfiguration) {
        execute(nodeMain, nodeConfiguration, null)
    }

    override fun getScheduledExecutorService(): ScheduledExecutorService {
        return nodeMainExecutor.scheduledExecutorService
    }

    override fun shutdownNodeMain(nodeMain: NodeMain) {
        nodeMainExecutor.shutdownNodeMain(nodeMain)
    }

    override fun shutdown() {
        runOnUiThread {
            if (AppHelper.setTopApp(this@NodeMainExecutorService)) {
                val builder = AlertDialog.Builder(this@NodeMainExecutorService)
                builder.setMessage(R.string.dialog_shutdown_msg)
                builder.setPositiveButton(R.string.shutdown) { dialog, which -> forceShutdown() }
                builder.setNegativeButton(android.R.string.cancel) { dialog, which -> }
                val alertDialog = builder.create()
                alertDialog.window!!.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                alertDialog.show()
            } else {
                forceShutdown()
            }
        }
    }

    fun forceShutdown() {
        signalOnShutdown()
        stopForeground(true)
        stopSelf()
    }

    fun addListener(listener: NodeMainExecutorServiceListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: NodeMainExecutorServiceListener) {
        listeners.remove(listener)
    }

    private fun signalOnShutdown() {
        listeners.signal { nodeMainExecutorServiceListener -> nodeMainExecutorServiceListener.onShutdown(this@NodeMainExecutorService) }
    }

    override fun onDestroy() {
//        toast("Shutting down...")
        nodeMainExecutor.shutdown()
        if (rosCore != null) {
            rosCore!!.shutdown()
        }
//        if (wakeLock!!.isHeld) {
//            wakeLock!!.release()
//        }
        if (wifiLock!!.isHeld) {
            wifiLock!!.release()
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (intent.action == null) {
            return Service.START_NOT_STICKY
        }
        if (intent.action == ACTION_START) {
            Preconditions.checkArgument(intent.hasExtra(EXTRA_NOTIFICATION_TICKER))
            Preconditions.checkArgument(intent.hasExtra(EXTRA_NOTIFICATION_TITLE))
            val notificationIntent = Intent(this, NodeMainExecutorService::class.java)
            notificationIntent.action = NodeMainExecutorService.ACTION_SHUTDOWN
            val pendingIntent = PendingIntent.getService(this, 0, notificationIntent, 0)
            val notification = buildNotification(intent, pendingIntent)

            startForeground(ONGOING_NOTIFICATION, notification)
        }
        if (intent.action == ACTION_SHUTDOWN) {
            shutdown()
            return Service.START_STICKY
        }
        return Service.START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return binder
    }

    /**
     * This version of startMaster can only create private masters.
     *
     */
    @Deprecated("use {@link public void startMaster(Boolean isPrivate)} instead.")
    fun startMaster() {
        startMaster(true)
    }

    /**
     * Starts a new ros master in an AsyncTask.
     * @param isPrivate
     */
    fun startMaster(isPrivate: Boolean) {
        doAsync {
            this@NodeMainExecutorService.startMasterBlocking(isPrivate)
        }
    }

    /**
     * Private blocking method to start a Ros Master.
     * @param isPrivate
     */
    private fun startMasterBlocking(isPrivate: Boolean) {
        rosCore = when {
            isPrivate -> RosCore.newPrivate()
            rosHostname != null -> RosCore.newPublic(rosHostname!!, 11311)
            else -> RosCore.newPublic(11311)
        }
        rosCore?.start()
        try {
            rosCore?.awaitStart()
        } catch (e: Exception) {
            throw RosRuntimeException(e)
        }

        masterUri = rosCore?.uri
    }

//    private fun toast(text: String) {
//        handler!!.post { Toast.makeText(this@NodeMainExecutorService, text, Toast.LENGTH_SHORT).show() }
//    }

    private fun buildNotification(intent: Intent, pendingIntent: PendingIntent): Notification? {
        val notification: Notification
        val builder: Notification.Builder
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_NONE
            )
            chan.lightColor = Color.BLUE
            chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
            builder = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            builder = Notification.Builder(this)
        }
        notification = builder.setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSmallIcon(org.ros.android.android_core_components.R.mipmap.icon)
            .setTicker(intent.getStringExtra(EXTRA_NOTIFICATION_TICKER))
            .setWhen(System.currentTimeMillis())
            .setContentTitle(intent.getStringExtra(EXTRA_NOTIFICATION_TITLE))
            .setAutoCancel(true)
            .setContentText(getString(R.string.notification_shutdown_msg))
            .build()
        return notification
    }

    companion object {

        private val TAG = "NodeMainExecutorService"

        // NOTE(damonkohler): If this is 0, the notification does not show up.
        private val ONGOING_NOTIFICATION = 1

        val ACTION_START = "org.ros.android.ACTION_START_NODE_RUNNER_SERVICE"
        val ACTION_SHUTDOWN = "org.ros.android.ACTION_SHUTDOWN_NODE_RUNNER_SERVICE"
        val EXTRA_NOTIFICATION_TITLE = "org.ros.android.EXTRA_NOTIFICATION_TITLE"
        val EXTRA_NOTIFICATION_TICKER = "org.ros.android.EXTRA_NOTIFICATION_TICKER"

        val NOTIFICATION_CHANNEL_ID = "org.ros.android"
        val CHANNEL_NAME = "ROS Android background service"
    }
}
