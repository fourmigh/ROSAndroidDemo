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

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.google.common.base.Preconditions
import org.jetbrains.anko.doAsync
import org.ros.address.InetAddressFactory
import org.ros.android.MasterChooser
import org.ros.exception.RosRuntimeException
import org.ros.node.NodeMain
import org.ros.node.NodeMainExecutor

import java.net.NetworkInterface
import java.net.SocketException
import java.net.URI
import java.net.URISyntaxException

/**
 * @author damonkohler@google.com (Damon Kohler)
 */
abstract class RosActivity
/**
 * Custom Master URI constructor.
 * Use this constructor to skip launching [MasterChooser].
 * @param notificationTicker Title to use in Ticker notifications.
 * @param notificationTitle Title to use in notifications.
 * @param customMasterUri URI of the ROS master to connect to.
 */
@JvmOverloads protected constructor(
    private val notificationTicker: String,
    private val notificationTitle: String,
    customMasterUri: URI? = null
) : Activity() {

    private val nodeMainExecutorServiceConnection: NodeMainExecutorServiceConnection
    private var masterChooserActivity : Class<*> = MasterChooser::class.java
    private var masterChooserRequestCode = MASTER_CHOOSER_REQUEST_CODE
    protected var nodeMainExecutorService: NodeMainExecutorService? = null

    /**
     * Default Activity Result callback - compatible with standard [MasterChooser]
     */
    private var onActivityResultCallback: OnActivityResultCallback? = object : OnActivityResultCallback {
        override fun execute(requestCode: Int, resultCode: Int, data: Intent?) {
            if (resultCode == Activity.RESULT_OK) {
                if (requestCode == MASTER_CHOOSER_REQUEST_CODE) {
                    val host: String
                    val networkInterfaceName = data?.getStringExtra("ROS_MASTER_NETWORK_INTERFACE")
                    // Handles the default selection and prevents possible errors
                    host = if (networkInterfaceName == null || networkInterfaceName == "") {
                        defaultHostAddress
                    } else {
                        try {
                            val networkInterface = NetworkInterface.getByName(networkInterfaceName)
                            InetAddressFactory.newNonLoopbackForNetworkInterface(networkInterface).hostAddress
                        } catch (e: SocketException) {
                            throw RosRuntimeException(e)
                        }

                    }
                    nodeMainExecutorService?.rosHostname = host
                    if (data?.getBooleanExtra("ROS_MASTER_CREATE_NEW", false) == true) {
                        nodeMainExecutorService?.startMaster(data.getBooleanExtra("ROS_MASTER_PRIVATE", true))
                    } else {
                        val uri: URI
                        try {
                            uri = URI(data?.getStringExtra("ROS_MASTER_URI"))
                        } catch (e: URISyntaxException) {
                            throw RosRuntimeException(e)
                        }

                        nodeMainExecutorService?.masterUri = uri
                    }
                    // Run init() in a new thread as a convenience since it often requires network access.
                    doAsync {
                        this@RosActivity.init(nodeMainExecutorService!!)
                    }
                } else {
                    // Without a master URI configured, we are in an unusable state.
                    nodeMainExecutorService?.forceShutdown()
                }
            }
        }
    }

    val masterUri: URI?
        get() {
            Preconditions.checkNotNull(nodeMainExecutorService)
            return nodeMainExecutorService?.masterUri
        }

    val rosHostname: String?
        get() {
            Preconditions.checkNotNull(nodeMainExecutorService)
            return nodeMainExecutorService?.rosHostname
        }

    private val defaultHostAddress: String
        get() = InetAddressFactory.newNonLoopback().hostAddress

    private inner class NodeMainExecutorServiceConnection(private val customMasterUri: URI?) : ServiceConnection {

        var serviceListener: NodeMainExecutorServiceListener? = null
            private set

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            nodeMainExecutorService = (binder as NodeMainExecutorService.LocalBinder).service

            if (customMasterUri != null) {
                nodeMainExecutorService?.masterUri = customMasterUri
                nodeMainExecutorService?.rosHostname = defaultHostAddress
            }

            serviceListener = object : NodeMainExecutorServiceListener {
                override fun onShutdown(nodeMainExecutorService: NodeMainExecutorService) {
                    if (!this@RosActivity.isFinishing) {
                        this@RosActivity.finish()
                        //关闭全部程序
                        System.exit(0)
                    }
                }
            }
            nodeMainExecutorService?.addListener(serviceListener!!)
            if (masterUri == null) {
                startMasterChooser()
            } else {
                init()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            nodeMainExecutorService?.removeListener(serviceListener!!)
            serviceListener = null
        }

    }

    init {
        nodeMainExecutorServiceConnection = NodeMainExecutorServiceConnection(customMasterUri)
    }

    /**
     * Custom MasterChooser constructor.
     * Use this constructor to specify which [Activity] should be started in place of [MasterChooser].
     * The specified activity shall return a result that can be handled by a custom callback.
     * See [.setOnActivityResultCallback] for more information about
     * how to handle custom request codes and results.
     * @param notificationTicker Title to use in Ticker notifications.
     * @param notificationTitle Title to use in notifications.
     * @param activity [Activity] to launch instead of [MasterChooser].
     * @param requestCode Request identifier to start the given [Activity] for a result.
     */
    protected constructor(
        notificationTicker: String,
        notificationTitle: String,
        activity: Class<*>,
        requestCode: Int
    ) : this(notificationTicker, notificationTitle) {
        masterChooserActivity = activity
        masterChooserRequestCode = requestCode
    }

    override fun onStart() {
        super.onStart()
        bindNodeMainExecutorService()
    }

    protected fun bindNodeMainExecutorService() {
        val intent = Intent(this, NodeMainExecutorService::class.java)
        intent.action = NodeMainExecutorService.ACTION_START
        intent.putExtra(NodeMainExecutorService.EXTRA_NOTIFICATION_TICKER, notificationTicker)
        intent.putExtra(NodeMainExecutorService.EXTRA_NOTIFICATION_TITLE, notificationTitle)
        startService(intent)
        Preconditions.checkState(
            bindService(intent, nodeMainExecutorServiceConnection, Context.BIND_AUTO_CREATE),
            "Failed to bind NodeMainExecutorService."
        )
    }

    override fun onDestroy() {
        unbindService(nodeMainExecutorServiceConnection)
        nodeMainExecutorService?.removeListener(nodeMainExecutorServiceConnection.serviceListener!!)
        super.onDestroy()
    }

    protected fun init() {
        // Run init() in a new thread as a convenience since it often requires
        // network access.
//        object : AsyncTask<Void, Void, Void>() {
//            override fun doInBackground(vararg params: Void): Void? {
//                this@RosActivity.init(nodeMainExecutorService!!)
//                return null
//            }
//        }.execute()
        doAsync {
            this@RosActivity.init(nodeMainExecutorService!!)
        }
    }

    /**
     * This method is called in a background thread once this [Activity] has
     * been initialized with a master [URI] via the [MasterChooser]
     * and a [NodeMainExecutorService] has started. Your [NodeMain]s
     * should be started here using the provided [NodeMainExecutor].
     *
     * @param nodeMainExecutor
     * the [NodeMainExecutor] created for this [Activity]
     */
    protected open abstract fun init(nodeMainExecutor: NodeMainExecutor)

    open fun startMasterChooser() {
        Preconditions.checkState(masterUri == null)
        // Call this method on super to avoid triggering our precondition in the
        // overridden startActivityForResult().
        super.startActivityForResult(Intent(this, masterChooserActivity), masterChooserRequestCode)
    }

    override fun startActivityForResult(intent: Intent, requestCode: Int) {
        Preconditions.checkArgument(requestCode != masterChooserRequestCode)
        super.startActivityForResult(intent, requestCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (onActivityResultCallback != null) {
            onActivityResultCallback!!.execute(requestCode, resultCode, data)
        }
    }

    interface OnActivityResultCallback {
        fun execute(requestCode: Int, resultCode: Int, data: Intent?)
    }

    /**
     * Set a callback that will be called onActivityResult.
     * Custom callbacks should be able to handle custom request codes configured
     * in custom Activity constructor [.RosActivity].
     * @param callback Action that will be performed when this Activity gets a result.
     */
    fun setOnActivityResultCallback(callback: OnActivityResultCallback) {
        onActivityResultCallback = callback
    }

    companion object {

        protected val MASTER_CHOOSER_REQUEST_CODE = 0
    }
}
/**
 * Standard constructor.
 * Use this constructor to proceed using the standard [MasterChooser].
 * @param notificationTicker Title to use in Ticker notifications.
 * @param notificationTitle Title to use in notifications.
 */
