package org.caojun.demo.rosandroid.layer

import android.view.GestureDetector
import android.view.MotionEvent
import com.github.rosjava.android_remocons.common_tools.apps.AppParameters
import com.github.rosjava.android_remocons.common_tools.apps.AppRemappings
import com.google.common.base.Preconditions
import geometry_msgs.PoseStamped
import geometry_msgs.PoseWithCovarianceStamped
import move_base_msgs.MoveBaseActionGoal
import org.ros.android.view.visualization.VisualizationView
import org.ros.android.view.visualization.layer.DefaultLayer
import org.ros.android.view.visualization.shape.PixelSpacePoseShape
import org.ros.android.view.visualization.shape.Shape
import org.ros.namespace.GraphName
import org.ros.namespace.NameResolver
import org.ros.node.ConnectedNode
import org.ros.node.Node
import org.ros.node.topic.Publisher
import org.ros.rosjava_geometry.Transform
import org.ros.rosjava_geometry.Vector3

import javax.microedition.khronos.opengles.GL10

//import com.github.rosjava.android_apps.application_management.rapp_manager.AppParameters;
//import com.github.rosjava.android_apps.application_management.rapp_manager.AppRemappings;

class MapPosePublisherLayer(
    private val nameResolver: NameResolver,
    params: AppParameters, remaps: AppRemappings
) : DefaultLayer() {

    private var shape: Shape? = null
    private var initialPosePublisher: Publisher<PoseWithCovarianceStamped>? = null
    private var androidGoalPublisher: Publisher<PoseStamped>? = null
    private var goalPublisher: Publisher<MoveBaseActionGoal>? = null
    private var visible: Boolean = false
    private var gestureDetector: GestureDetector? = null
    private var pose: Transform? = null
    private var fixedPose: Transform? = null
    private var connectedNode: ConnectedNode? = null
    private var mode: Int = 0

    private val mapFrame: String
    private val robotFrame: String
    private val initialPoseTopic: String
    private val simpleGoalTopic: String
    private val moveBaseGoalTopic: String

    init {
        visible = false

        this.mapFrame = params.get("map_frame", "map") as String
        this.robotFrame = params.get("robot_frame", "base_footprint") as String

        this.initialPoseTopic = remaps.get("initialpose")
        this.simpleGoalTopic = remaps.get("move_base_simple/goal")
        this.moveBaseGoalTopic = remaps.get("move_base/goal")

    }

    fun setPoseMode() {
        mode = POSE_MODE
    }

    fun setGoalMode() {
        mode = GOAL_MODE
    }

    override fun draw(view: VisualizationView?, gl: GL10?) {
        if (visible) {
            Preconditions.checkNotNull<Transform>(pose)
            shape!!.draw(view, gl)
        }
    }

    private fun angle(x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val deltaX = x1 - x2
        val deltaY = y1 - y2
        return Math.atan2(deltaY, deltaX)
    }

    override fun onTouchEvent(view: VisualizationView?, event: MotionEvent?): Boolean {
        if (visible) {
            Preconditions.checkNotNull<Transform>(pose)

            val poseVector: Vector3
            val pointerVector: Vector3

            if (event!!.action == MotionEvent.ACTION_MOVE) {
                poseVector = pose!!.apply(Vector3.zero())
                pointerVector = view!!.camera.toCameraFrame(
                    event.x.toInt(),
                    event.y.toInt()
                )

                val angle = angle(
                    pointerVector.x,
                    pointerVector.y, poseVector.x,
                    poseVector.y
                )
                pose = Transform.translation(poseVector).multiply(
                    Transform.zRotation(angle)
                )

                shape!!.transform = pose
                return true
            }
            if (event.action == MotionEvent.ACTION_UP) {

                val poseStamped: PoseStamped
                when (mode) {
                    POSE_MODE -> {
                        view!!.camera.setFrame(mapFrame)
                        poseVector = fixedPose!!.apply(Vector3.zero())
                        pointerVector = view.camera.toCameraFrame(
                            event.x.toInt(), event.y.toInt()
                        )
                        val angle2 = angle(
                            pointerVector.x,
                            pointerVector.y, poseVector.x,
                            poseVector.y
                        )
                        fixedPose = Transform.translation(poseVector).multiply(
                            Transform.zRotation(angle2)
                        )
                        view.camera.setFrame(robotFrame)
                        poseStamped = fixedPose!!.toPoseStampedMessage(
                            GraphName.of(robotFrame),
                            connectedNode!!.currentTime,
                            androidGoalPublisher!!.newMessage()
                        )

                        val initialPose = initialPosePublisher!!.newMessage()
                        initialPose.header.frameId = mapFrame
                        initialPose.pose.pose = poseStamped.pose
                        val covariance = initialPose.pose.covariance
                        covariance[6 * 0 + 0] = 0.5 * 0.5
                        covariance[6 * 1 + 1] = 0.5 * 0.5
                        covariance[6 * 5 + 5] = (Math.PI / 12.0 * Math.PI / 12.0).toFloat().toDouble()

                        initialPosePublisher!!.publish(initialPose)
                    }
                    GOAL_MODE -> {
                        poseStamped = pose!!.toPoseStampedMessage(
                            GraphName.of(robotFrame),
                            connectedNode!!.currentTime,
                            androidGoalPublisher!!.newMessage()
                        )
                        androidGoalPublisher!!.publish(poseStamped)

                        val message = goalPublisher!!.newMessage()
                        message.header = poseStamped.header
                        message.goalId.stamp = connectedNode!!.currentTime
                        message.goalId.id =
                            "move_base/move_base_client_android" + connectedNode!!.currentTime.toString()
                        message.goal.targetPose = poseStamped
                        goalPublisher!!.publish(message)
                    }
                }
                visible = false
                return true
            }
        }
        gestureDetector!!.onTouchEvent(event)
        return false
    }

    override fun onStart(view: VisualizationView?, connectedNode: ConnectedNode?) {
        this.connectedNode = connectedNode
        shape = PixelSpacePoseShape()
        mode = GOAL_MODE

        initialPosePublisher = connectedNode!!.newPublisher(
            nameResolver.resolve(initialPoseTopic).toString(),
            "geometry_msgs/PoseWithCovarianceStamped"
        )
        androidGoalPublisher = connectedNode.newPublisher(
            nameResolver.resolve(simpleGoalTopic).toString(),
            "geometry_msgs/PoseStamped"
        )
        goalPublisher = connectedNode.newPublisher(
            nameResolver.resolve(moveBaseGoalTopic).toString(),
            "move_base_msgs/MoveBaseActionGoal"
        )
        view!!.post {
            gestureDetector = GestureDetector(view.context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onLongPress(e: MotionEvent) {
                        pose = Transform.translation(
                            view.camera.toCameraFrame(
                                e.x.toInt(), e.y.toInt()
                            )
                        )
                        shape!!.transform = pose
                        view.camera.setFrame(mapFrame)
                        fixedPose = Transform.translation(
                            view.camera.toCameraFrame(
                                e.x.toInt(), e.y.toInt()
                            )
                        )
                        view.camera.setFrame(robotFrame)
                        visible = true
                    }
                })
        }
    }

    override fun onShutdown(view: VisualizationView?, node: Node?) {
        initialPosePublisher!!.shutdown()
        androidGoalPublisher!!.shutdown()
        goalPublisher!!.shutdown()
    }

    companion object {
        private val POSE_MODE = 0
        private val GOAL_MODE = 1
    }
}
