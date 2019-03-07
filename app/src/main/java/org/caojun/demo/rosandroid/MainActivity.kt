package org.caojun.demo.rosandroid

import android.os.Bundle
import android.view.View
import com.google.common.collect.Lists
import com.socks.library.KLog
import kotlinx.android.synthetic.main.activity_main.*
import org.caojun.demo.rosandroid.core.RosAppActivity
import org.caojun.demo.rosandroid.layer.MapPosePublisherLayer
import org.caojun.demo.rosandroid.node.MapManager
import org.caojun.demo.rosandroid.node.SystemCommands
import org.jetbrains.anko.doAsync
import org.ros.address.InetAddressFactory
import org.ros.android.view.visualization.layer.*
import org.ros.node.NodeConfiguration
import org.ros.node.NodeMainExecutor
import org.ros.internal.node.client.MasterClient
import org.ros.namespace.GraphName
import org.jetbrains.anko.toast
import org.jetbrains.anko.uiThread
import world_canvas_msgs.SaveMapResponse


/**
 * @author caojun@deepblueai.com
 */
class MainActivity : RosAppActivity("ROSAndroidDemo", "ROSAndroidDemo", MasterChooser::class.java) {

    private val cameraControlLayer = CameraControlLayer()
    private var mapPosePublisherLayer: MapPosePublisherLayer? = null
    private val systemCommands = SystemCommands()

    override fun onCreate(savedInstanceState: Bundle?) {

        setDashboardResource(R.id.llTopBar)
        setMainWindowResource(R.layout.activity_main)
        super.onCreate(savedInstanceState)

//        visualizationView.camera.jumpToFrame("map")
//        visualizationView.onCreate(
//            Lists.newArrayList<Layer>(
//                cameraControlLayer,
//                OccupancyGridLayer("map"),
//                PathLayer("move_base/NavfnROS/plan"),
//                PathLayer("move_base_dynamic/NavfnROS/plan"),
//                LaserScanLayer("scan"),
//                PoseSubscriberLayer("simple_waypoints_server/goal_pose"),
//                PosePublisherLayer("simple_waypoints_server/goal_pose"),
//                RobotLayer("base_footprint")
//            )
//        )
        disableFollowMe()
        visualizationView.onCreate(
            Lists.newArrayList<Layer>(
                cameraControlLayer,
                OccupancyGridLayer("map"),
                LaserScanLayer("scan"),
                PathLayer("move_base/NavfnROS/plan"),//导航路线
                RobotLayer("base_link")
            )
        )

        ibRefresh.setOnClickListener {
            visualizationView.camera.jumpToFrame(params.get("map_frame", "map") as String)
        }

        tbFollowMe.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                enableFollowMe()
            } else {
                disableFollowMe()
            }
        }

        tbJoystick.setOnCheckedChangeListener { buttonView, isChecked ->
            virtualJoystickView.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        ibSaveMap.setOnClickListener {

            systemCommands.saveGeotiff()
        }

        rosTextView.setTopicName("chatter")
        rosTextView.setMessageType(std_msgs.String._TYPE)
        rosTextView.setMessageToStringCallable {
                message -> message.toString()
        }
        rosTextView.setMessageToStringCallable { message -> (message as std_msgs.String).data }
    }

    override fun init(nodeMainExecutor: NodeMainExecutor) {
        super.init(nodeMainExecutor)
        val nodeConfiguration = NodeConfiguration.newPublic(
            InetAddressFactory.newNonLoopback().hostAddress,
            masterUri
        )

        KLog.d("hostAddress", InetAddressFactory.newNonLoopback().hostAddress)
        KLog.d("masterUri", masterUri)

        //导航
        visualizationView.init(nodeMainExecutor)
        cameraControlLayer.addListener(object : CameraControlListener {
            override fun onZoom(focusX: Float, focusY: Float, factor: Float) {
                disableFollowMe()
            }

            override fun onTranslate(distanceX: Float, distanceY: Float) {
                disableFollowMe()
            }

            override fun onRotate(focusX: Float, focusY: Float, deltaAngle: Double) {
                disableFollowMe()
            }

            override fun onDoubleTap(x: Float, y: Float) {}
        })
        for (i in remaps) {
            KLog.d("remaps", "${i.key} : ${i.value}")
        }
        KLog.d("remaps", "////////////////////////////////////////")
        val joyTopic = remaps.get("cmd_vel")
        virtualJoystickView.setTopicName(joyTopic)
        mapPosePublisherLayer = MapPosePublisherLayer(masterNameSpace, params, remaps)
        visualizationView.addLayer(mapPosePublisherLayer)
//        nodeMainExecutor.execute(virtualJoystickView, nodeConfiguration.setNodeName("virtual_joystick"))
//        nodeMainExecutor.execute(visualizationView, nodeConfiguration.setNodeName("android/map_view"))
        nodeMainExecutor.execute(systemCommands, nodeConfiguration)
        nodeMainExecutor.execute(virtualJoystickView, nodeConfiguration)
        nodeMainExecutor.execute(visualizationView, nodeConfiguration)

//        doTest(nodeConfiguration)

        //Talker
//        val talker = Talker()
//        nodeMainExecutor.execute(talker, nodeConfiguration)
//        nodeMainExecutor.execute(rosTextView, nodeConfiguration)

//        runOnUiThread {
//            ibSaveMap.setOnClickListener {
//                doSaveMap(nodeMainExecutor, nodeConfiguration)
//            }
//        }
    }

    private fun doSaveMap(nodeMainExecutor: NodeMainExecutor, nodeConfiguration : NodeConfiguration) {
        doAsync {

            try {
                val mapManager = MapManager(this@MainActivity, remaps)
                val name = "map0"
                mapManager.setMapName(name)
                mapManager.setNameResolver(masterNameSpace)
                mapManager.registerCallback(object : MapManager.StatusCallback {
                    override fun timeoutCallback() {
                        uiThread {
                            toast("Error: Timeout")
                        }
                    }

                    override fun onSuccessCallback(arg0: SaveMapResponse) {
                        uiThread {
                            toast("Success: Map saving success!")
                        }
                    }

                    override fun onFailureCallback(e: Exception) {
                        uiThread {
                            toast("Error: ${e.message}")
                        }
                    }
                })

                nodeMainExecutor.execute(
                    mapManager,
                    nodeConfiguration.setNodeName("android/save_map")
                )

            } catch (e: Exception) {
                e.printStackTrace()
                uiThread {
                    toast("Error during saving: $e")
                }
            }
        }

    }

    private fun doTest(nodeConfiguration : NodeConfiguration) {

        val masterClient = MasterClient(nodeConfiguration.masterUri)
        val systemState = masterClient.getSystemState(GraphName.of(""))
        val topicSystemStateList = systemState.result.topics
        for (topic in topicSystemStateList) {
            KLog.i("topic", "topic.topicName: ${topic.topicName}")
            KLog.i("topic", "topic.publishers: ${topic.publishers}")
            KLog.i("topic", "topic.subscribers: ${topic.subscribers}")
            KLog.i("topic", "/////////////////////////////////////////////")
        }

        doAsync {
            for (topic in topicSystemStateList) {
//                for (publish in topic.publishers) {
//
//                    KLog.i("publishedTopic", "publish: $publish")
//                    val publishedTopics = masterClient.getPublishedTopics(GraphName.of(publish), "")
//                    for (publishedTopic in publishedTopics.result) {
//                        KLog.i("publishedTopic", "publishedTopic.name: ${publishedTopic.name}")
//                        KLog.i("publishedTopic", "publishedTopic.messageType: ${publishedTopic.messageType}")
//                        KLog.i("publishedTopic", "/////////////////////////////////////////////")
//                    }
//                }
                val topicTypes = masterClient.getTopicTypes(GraphName.of(topic.topicName))
                for (topicType in topicTypes.result) {
                    KLog.i("topicType", "topicType.name: ${topicType.name}")
                    KLog.i("topicType", "topicType.messageType: ${topicType.messageType}")
                    KLog.i("topicType", "/////////////////////////////////////////////")
                }
            }
        }
    }

    private fun enableFollowMe() {
        runOnUiThread {
            visualizationView.camera.jumpToFrame("base_link")
            tbFollowMe.isChecked = true
        }
    }

    private fun disableFollowMe() {
        runOnUiThread {
            visualizationView.camera.setFrame("map")
            tbFollowMe.isChecked = false
        }
    }

    fun setPoseClicked(view: View) {
        setPose()
    }

    fun setGoalClicked(view: View) {
        setGoal()
    }

    private fun setPose() {
        mapPosePublisherLayer?.setPoseMode()
    }

    private fun setGoal() {
        mapPosePublisherLayer?.setGoalMode()
    }
}
