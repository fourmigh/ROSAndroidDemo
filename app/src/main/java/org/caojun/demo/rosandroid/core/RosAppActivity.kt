/*
 * Copyright (C) 2013 OSRF.
 * Copyright (c) 2013, Yujin Robot.
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
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import com.github.robotics_in_concert.rocon_rosjava_core.rocon_interactions.InteractionMode
import com.github.rosjava.android_remocons.common_tools.apps.AppParameters
import com.github.rosjava.android_remocons.common_tools.apps.AppRemappings
import com.github.rosjava.android_remocons.common_tools.apps.MasterNameResolver
import com.github.rosjava.android_remocons.common_tools.dashboards.Dashboard
import com.github.rosjava.android_remocons.common_tools.master.MasterDescription
import com.github.rosjava.android_remocons.common_tools.rocon.Constants
import com.socks.library.KLog
import org.jetbrains.anko.doAsync
import org.ros.address.InetAddressFactory
import org.ros.exception.RosRuntimeException
import org.ros.namespace.NameResolver
import org.ros.node.NodeConfiguration
import org.ros.node.NodeMainExecutor
import org.yaml.snakeyaml.Yaml

import java.io.Serializable
import java.net.URI
import java.net.URISyntaxException
import java.util.LinkedHashMap

/**
 * @author murase@jsk.imi.i.u-tokyo.ac.jp (Kazuto Murase)
 * @author jorge@yujinrobot.com (Jorge Santos Simon)
 *
 * Modified to work in standalone, paired (robot) and concert modes.
 * Also now handles parameters and remappings.
 *
 * @author caojun@deepblueai.com
 * 增加自定义的MasterChooser
 */
abstract class RosAppActivity protected constructor(
    notificationTicker: String, androidApplicationName: String, // descriptive helper only
    masterChooser: Class<*>
) : RosActivity(notificationTicker, androidApplicationName, masterChooser, 0) {

    /*
      By default we assume the ros app activity is launched independently. The following attribute is
      used to identify when it has instead been launched by a controlling application (e.g. remocons)
      in paired, one-to-one, or concert mode.
     */
    private var appMode = InteractionMode.STANDALONE
    private var masterAppName: String? = null
    private var defaultMasterAppName: String? = null
    private var defaultMasterName: String? = ""
    private var remoconActivity: String? = null  // The remocon activity to start when finishing this app
    // e.g. com.github.rosjava.android_remocons.robot_remocon.RobotRemocon
    private var remoconExtraData: Serializable? =
        null // Extra data for remocon (something inheriting from MasterDescription)

    private var dashboardResourceId = 0
    private var mainWindowId = 0
    private var dashboard: Dashboard? = null
    private var nodeConfiguration: NodeConfiguration? = null
    private var nodeMainExecutor: NodeMainExecutor? = null
    protected var masterNameResolver: MasterNameResolver? = null
    protected var masterDescription: MasterDescription? = null

    // By now params and remaps are only available for concert apps; that is, appMode must be CONCERT
    protected var params = AppParameters()
    protected var remaps = AppRemappings()

    protected val masterNameSpace: NameResolver
        get() = masterNameResolver!!.masterNameSpace

    protected fun setDashboardResource(resource: Int) {
        dashboardResourceId = resource
    }

    protected fun setMainWindowResource(resource: Int) {
        mainWindowId = resource
    }

    protected fun setDefaultMasterName(name: String) {
        defaultMasterName = name
    }

    protected fun setDefaultAppName(name: String) {
        defaultMasterAppName = name
    }

    protected fun setCustomDashboardPath(path: String) {
        dashboard?.setCustomDashboardPath(path)
    }

//    override fun onStart() {
//        super.onStart()
//    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (mainWindowId == 0) {
            KLog.e(
                "RosApp",
                "You must set the dashboard resource ID in your RosAppActivity"
            )
            return
        }
        if (dashboardResourceId == 0) {
            KLog.e(
                "RosApp",
                "You must set the dashboard resource ID in your RosAppActivity"
            )
            return
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(mainWindowId)

        masterNameResolver = MasterNameResolver()

        if (defaultMasterName != null) {
            masterNameResolver?.masterName = defaultMasterName
        }

        // FAKE concert remocon invocation
        //        MasterId mid = new MasterId("http://192.168.10.129:11311", "http://192.168.10.129:11311", "DesertStorm3", "WEP2", "yujin0610");
        //        MasterDescription  md = MasterDescription.createUnknown(mid);
        //        getIntent().putExtra(MasterDescription.UNIQUE_KEY, md);
        //        getIntent().putExtra(AppManager.PACKAGE + ".concert_app_name", "KKKK");
        //        getIntent().putExtra("PairedManagerActivity", "com.github.rosjava.android_remocons.rocon_remocon.Remocon");
        //        getIntent().putExtra("ChooserURI", "http://192.168.10.129:11311");
        //        getIntent().putExtra("Parameters", "{pickup_point: pickup}");
        //        getIntent().putExtra("Remappings", "{ 'cmd_vel':'/robot_teleop/cmd_vel', 'image_color':'/robot_teleop/image_color/compressed_throttle' }");

        // FAKE robot remocon invocation
        //        MasterId mid = new MasterId("http://192.168.10.211:11311", "http://192.168.10.167:11311", "DesertStorm3", "WEP2", "yujin0610");
        //        MasterDescription  md = MasterDescription.createUnknown(mid);
        //        md.setMasterName("grieg");
        //        md.setMasterType("turtlebot");
        //        getIntent().putExtra(MasterDescription.UNIQUE_KEY, md);
        //        getIntent().putExtra(AppManager.PACKAGE + ".paired_app_name", "KKKK");
        //        getIntent().putExtra("PairedManagerActivity", "com.github.rosjava.android_remocons.robot_remocon.RobotRemocon");
        ////        getIntent().putExtra("RemoconURI", "http://192.168.10.129:11311");
        //        getIntent().putExtra("Parameters", "{pickup_point: pickup}");
        //        getIntent().putExtra("Remappings", "{ 'cmd_vel':'/robot_teleop/cmd_vel', 'image_color':'/robot_teleop/image_color/compressed_throttle' }");


        for (mode in InteractionMode.values()) {
            // The remocon specifies its type in the app name extra content string, useful information for the app
            masterAppName = intent.getStringExtra(Constants.ACTIVITY_SWITCHER_ID + "." + mode + "_app_name")
            if (masterAppName != null) {
                appMode = mode
                break
            }
        }

        if (masterAppName == null) {
            // App name extra content key not present on intent; no remocon started the app, so we are standalone app
            KLog.e("RosApp", "We are running as standalone :(")
            masterAppName = defaultMasterAppName
            appMode = InteractionMode.STANDALONE
        } else {
            // Managed app; take from the intent all the fancy stuff remocon put there for us

            // Extract parameters and remappings from a YAML-formatted strings; translate into hash maps
            // We create empty maps if the strings are missing to avoid continuous if ! null checks
            val yaml = Yaml()

            val paramsStr = intent.getStringExtra("Parameters")
            val remapsStr = intent.getStringExtra("Remappings")

            KLog.d("RosApp", "Parameters: " + paramsStr!!)
            KLog.d("RosApp", "Remappings: " + remapsStr!!)

            try {
                if (!paramsStr.isEmpty()) {
                    val paramsList = yaml.load(paramsStr) as LinkedHashMap<String, Any>
//                    if (paramsList != null) {
                        params.putAll(paramsList)
                        KLog.d("RosApp", "Parameters: $paramsStr")
//                    }
                }
            } catch (e: ClassCastException) {
                KLog.e("RosApp", "Cannot cast parameters yaml string to a hash map ($paramsStr)")
                throw RosRuntimeException("Cannot cast parameters yaml string to a hash map ($paramsStr)")
            }

            try {
                if (!remapsStr.isEmpty()) {
                    val remapsList = yaml.load(remapsStr) as LinkedHashMap<String, String>
//                    if (remapsList != null) {
                        remaps.putAll(remapsList)
                        KLog.d("RosApp", "Remappings: $remapsStr")
//                    }
                }
            } catch (e: ClassCastException) {
                KLog.e("RosApp", "Cannot cast parameters yaml string to a hash map ($remapsStr)")
                throw RosRuntimeException("Cannot cast parameters yaml string to a hash map ($remapsStr)")
            }

            remoconActivity = intent.getStringExtra("RemoconActivity")

            // Master description is mandatory on managed apps, as it contains master URI
            if (intent.hasExtra(MasterDescription.UNIQUE_KEY)) {
                // Keep a non-casted copy of the master description, so we don't lose the inheriting object
                // when switching back to the remocon. Not fully sure why this works and not if casting
                remoconExtraData = intent.getSerializableExtra(MasterDescription.UNIQUE_KEY)

                try {
                    masterDescription = intent.getSerializableExtra(MasterDescription.UNIQUE_KEY) as MasterDescription
                } catch (e: ClassCastException) {
                    KLog.e("RosApp", "Master description expected on intent on $appMode mode")
                    throw RosRuntimeException("Master description expected on intent on $appMode mode")
                }

            } else {
                // TODO how should I handle these things? try to go back to remocon? Show a message?
                KLog.e("RosApp", "Master description missing on intent on $appMode mode")
                throw RosRuntimeException("Master description missing on intent on $appMode mode")
            }
        }

        if (dashboard == null) {
            dashboard = Dashboard(this)
            dashboard!!.setView(
                findViewById<LinearLayout>(dashboardResourceId),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    override fun init(nodeMainExecutor: NodeMainExecutor) {
        this.nodeMainExecutor = nodeMainExecutor
        nodeConfiguration = NodeConfiguration.newPublic(
            InetAddressFactory
                .newNonLoopback().hostAddress, masterUri
        )

        if (appMode == InteractionMode.STANDALONE) {
            dashboard?.setRobotName(masterNameResolver?.masterName)
        } else {
            masterNameResolver?.setMaster(masterDescription)
            dashboard?.setRobotName(masterDescription?.masterName)  // TODO dashboard not working for concerted apps (Issue #32)

            if (appMode == InteractionMode.PAIRED) {
                dashboard?.setRobotName(masterDescription?.masterType)
            }
        }

        // Run master namespace resolver
        nodeMainExecutor.execute(masterNameResolver, nodeConfiguration!!.setNodeName("masterNameResolver"))
        masterNameResolver?.waitForResolver()

        nodeMainExecutor.execute(dashboard, nodeConfiguration!!.setNodeName("dashboard"))
    }

    protected fun onAppTerminate() {
        this@RosAppActivity.runOnUiThread {
            AlertDialog.Builder(this@RosAppActivity)
                .setTitle("App Termination")
                .setMessage(
                    "The application has terminated on the server, so the client is exiting."
                )
                .setCancelable(false)
                .setNeutralButton(
                    "Exit"
                ) { dialog, which -> this@RosAppActivity.finish() }.create().show()
        }
    }

    override fun startMasterChooser() {
        if (appMode == InteractionMode.STANDALONE) {
            super.startMasterChooser()
        } else {
            try {
                nodeMainExecutorService?.masterUri = URI(masterDescription?.masterUri)
//                object : AsyncTask<Void, Void, Void>() {
//                    override fun doInBackground(vararg params: Void): Void? {
//                        this@RosAppActivity.init(nodeMainExecutorService!!)
//                        return null
//                    }
//                }.execute()
                doAsync {
                    this@RosAppActivity.init(nodeMainExecutorService!!)
                }
            } catch (e: URISyntaxException) {
                // Remocon cannot be such a bastard to send as a wrong URI...
                throw RosRuntimeException(e)
            }

        }
    }

    protected fun releaseMasterNameResolver() {
        nodeMainExecutor?.shutdownNodeMain(masterNameResolver)
    }

    protected fun releaseDashboardNode() {
        nodeMainExecutor?.shutdownNodeMain(dashboard)
    }

    /**
     * Whether this ros app activity should be responsible for
     * starting and stopping a paired master application.
     *
     * This responsibility is relinquished if the application
     * is controlled from a remocon, but required if the
     * android application is connecting and running directly.
     *
     * @return boolean : true if it needs to be managed.
     */
    private fun managePairedRobotApplication(): Boolean {
        return appMode == InteractionMode.STANDALONE && masterAppName != null
    }

    override fun onBackPressed() {
        if (appMode != InteractionMode.STANDALONE) {  // i.e. it's a managed app
            KLog.i("RosApp", "app terminating and returning control to the remocon.")
            // Restart the remocon, supply it with the necessary information and stop this activity
            val intent = Intent()
            intent.putExtra(Constants.ACTIVITY_SWITCHER_ID + "." + appMode + "_app_name", "AppChooser")
            intent.putExtra(MasterDescription.UNIQUE_KEY, remoconExtraData)
            intent.action = remoconActivity
            intent.addCategory("android.intent.category.DEFAULT")
            startActivity(intent)
            finish()
        } else {
            KLog.i("RosApp", "backpress processing for RosAppActivity")
        }
        super.onBackPressed()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 0 && resultCode != Activity.RESULT_OK) {
            //从设置MasterChooser取消返回
            finish()
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }
}
