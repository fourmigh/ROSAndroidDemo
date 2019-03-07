/*
 * Copyright (C) 2012 Google Inc.
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

package org.caojun.demo.rosandroid.node

import org.ros.namespace.GraphName
import org.ros.node.AbstractNodeMain
import org.ros.node.ConnectedNode
import org.ros.node.Node
import org.ros.node.topic.Publisher

/**
 * @author damonkohler@google.com (Damon Kohler)
 */
class SystemCommands : AbstractNodeMain() {

    private var publisher: Publisher<std_msgs.String>? = null

    override fun getDefaultNodeName(): GraphName {
        return GraphName.of("system_commands")
    }

    override fun onStart(connectedNode: ConnectedNode) {
        publisher = connectedNode.newPublisher<std_msgs.String>("syscommand", std_msgs.String._TYPE)
    }

    fun reset() {
        publish("reset")
    }

    fun saveGeotiff() {
        publish("savegeotiff")
    }

    private fun publish(command: String) {
        val message = publisher?.newMessage()
        message?.data = command
        publisher?.publish(message)
    }

    override fun onShutdown(arg0: Node?) {
        publisher = null
    }
}