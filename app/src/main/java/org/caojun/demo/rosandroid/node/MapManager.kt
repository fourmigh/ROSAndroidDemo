package org.caojun.demo.rosandroid.node

import android.annotation.SuppressLint
import android.content.Context
import android.os.AsyncTask
import com.github.rosjava.android_remocons.common_tools.apps.AppRemappings
import org.ros.exception.RemoteException
import org.ros.exception.ServiceNotFoundException
import org.ros.namespace.GraphName
import org.ros.namespace.NameResolver
import org.ros.node.AbstractNodeMain
import org.ros.node.ConnectedNode
import org.ros.node.service.ServiceClient
import org.ros.node.service.ServiceResponseListener
import world_canvas_msgs.SaveMap
import world_canvas_msgs.SaveMapRequest
import world_canvas_msgs.SaveMapResponse

import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class MapManager(context: Context, remaps: AppRemappings) : AbstractNodeMain() {

    private var connectedNode: ConnectedNode? = null
    private val saveServiceResponseListener: ServiceResponseListener<SaveMapResponse>? = null

    private var mapName: String? = null
    private var saveSrvName: String? = null
    private var nameResolver: NameResolver? = null
    private var nameResolverSet = false
    private var waitingFlag = false

    private var statusCallback: StatusCallback? = null

    interface StatusCallback {
        fun timeoutCallback()
        fun onSuccessCallback(arg0: SaveMapResponse)
        fun onFailureCallback(e: Exception)
    }

    fun registerCallback(statusCallback: StatusCallback) {
        this.statusCallback = statusCallback
    }

    init {
        // Apply remappings
        saveSrvName = remaps.get("save_map")
        mapName = ""
    }

    fun setMapName(name: String) {
        mapName = name
    }

    fun setNameResolver(newNameResolver: NameResolver) {
        nameResolver = newNameResolver
        nameResolverSet = true
    }

    private fun clearWaitFor() {
        waitingFlag = false
    }

    private fun waitFor(timeout: Int): Boolean {
        waitingFlag = true
        @SuppressLint("StaticFieldLeak")
        val asyncTask = object : AsyncTask<Void, Void, Boolean>() {
            override fun doInBackground(vararg params: Void): Boolean? {
                var count = 0
                val timeout_count = timeout * 1000 / 200
                while (waitingFlag) {
                    try {
                        Thread.sleep(200)
                    } catch (e: InterruptedException) {
                        return false
                    }

                    if (count < timeout_count) {
                        count += 1
                    } else {
                        return false
                    }
                }
                return true
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
        try {
            return asyncTask.get(timeout.toLong(), TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            return false
        } catch (e: ExecutionException) {
            return false
        } catch (e: TimeoutException) {
            return false
        }

    }

    fun saveMap() {
        var saveMapClient: ServiceClient<SaveMapRequest, SaveMapResponse>? = null
        if (connectedNode != null) {
            try {
                if (nameResolverSet) {
                    saveSrvName = nameResolver!!.resolve(saveSrvName!!).toString()
                }
                saveMapClient = connectedNode!!.newServiceClient(saveSrvName, SaveMap._TYPE)
            } catch (e: ServiceNotFoundException) {
                try {
                    Thread.sleep(1000L)
                } catch (ex: Exception) {
                }

                statusCallback!!.onFailureCallback(e)
            }

            if (saveMapClient != null) {
                val request = saveMapClient.newMessage()
                request.mapName = mapName
                saveMapClient.call(request, object : ServiceResponseListener<SaveMapResponse> {
                    override fun onSuccess(saveMapResponse: SaveMapResponse) {
                        if (waitingFlag) {
                            clearWaitFor()
                            statusCallback!!.onSuccessCallback(saveMapResponse)
                        }
                    }

                    override fun onFailure(e: RemoteException) {
                        if (waitingFlag) {
                            clearWaitFor()
                            statusCallback!!.onFailureCallback(e)
                        }
                    }
                })
                if (!waitFor(10)) {
                    statusCallback!!.timeoutCallback()
                }
            }

        }
    }

    override fun getDefaultNodeName(): GraphName? {
        return null
    }

    override fun onStart(connectedNode: ConnectedNode?) {
        super.onStart(connectedNode)
        this.connectedNode = connectedNode
        saveMap()
    }
}
