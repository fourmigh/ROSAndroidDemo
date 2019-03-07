package org.caojun.demo.rosandroid.node

import org.ros.namespace.GraphName
import org.ros.node.AbstractNodeMain
import org.ros.concurrent.CancellableLoop
import org.ros.node.ConnectedNode

class Talker : AbstractNodeMain {
    private val topic_name: String

    constructor() {
        this.topic_name = "chatter"
    }

    constructor(topic: String) {
        this.topic_name = topic
    }

    override fun getDefaultNodeName(): GraphName {
        return GraphName.of("rosjava_tutorial_pubsub/talker")
    }

    override fun onStart(connectedNode: ConnectedNode?) {
        val publisher = connectedNode!!.newPublisher<std_msgs.String>(this.topic_name, "std_msgs/String")
        connectedNode.executeCancellableLoop(object : CancellableLoop() {
            private var sequenceNumber: Int = 0

            override fun setup() {
                this.sequenceNumber = 0
            }

            @Throws(InterruptedException::class)
            override fun loop() {
                val str = publisher.newMessage() as std_msgs.String
                str.data = "Hello world! " + this.sequenceNumber
                publisher.publish(str)
                ++this.sequenceNumber
                Thread.sleep(1000L)
            }
        })
    }
}