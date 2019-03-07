package org.caojun.demo.rosandroid

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.text.Editable
import android.text.TextWatcher
import com.socks.library.KLog
import kotlinx.android.synthetic.main.activity_master_chooser.*
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.toast
import org.jetbrains.anko.uiThread
import org.ros.internal.node.client.MasterClient
import org.ros.internal.node.xmlrpc.XmlRpcTimeoutException
import org.ros.namespace.GraphName
import java.net.URI
import java.net.URISyntaxException
import java.util.*
import java.util.regex.Pattern

/**
 * @author caojun@deepblueai.com
 *
 * 替换android_core_components里的MasterChooser
 */
class MasterChooser : AppCompatActivity() {

    /**
     * Regular expressions used with ROS URIs.
     *
     * The majority of the expressions and variables were copied from
     * [android.util.Patterns]. The [android.util.Patterns] class could not be
     * utilized because the PROTOCOL regex included other web protocols besides http. The
     * http protocol is required by ROS.
     */
    private object RosURIPattern {
        /* A word boundary or end of input.  This is to stop foo.sure from matching as foo.su */
        private val WORD_BOUNDARY = "(?:\\b|$|^)"

        /**
         * Valid UCS characters defined in RFC 3987. Excludes space characters.
         */
        private val UCS_CHAR = "[" +
                "\u00A0-\uD7FF" +
                "\uF900-\uFDCF" +
                "\uFDF0-\uFFEF" +
                "\uD800\uDC00-\uD83F\uDFFD" +
                "\uD840\uDC00-\uD87F\uDFFD" +
                "\uD880\uDC00-\uD8BF\uDFFD" +
                "\uD8C0\uDC00-\uD8FF\uDFFD" +
                "\uD900\uDC00-\uD93F\uDFFD" +
                "\uD940\uDC00-\uD97F\uDFFD" +
                "\uD980\uDC00-\uD9BF\uDFFD" +
                "\uD9C0\uDC00-\uD9FF\uDFFD" +
                "\uDA00\uDC00-\uDA3F\uDFFD" +
                "\uDA40\uDC00-\uDA7F\uDFFD" +
                "\uDA80\uDC00-\uDABF\uDFFD" +
                "\uDAC0\uDC00-\uDAFF\uDFFD" +
                "\uDB00\uDC00-\uDB3F\uDFFD" +
                "\uDB44\uDC00-\uDB7F\uDFFD" +
                "&&[^\u00A0[\u2000-\u200A]\u2028\u2029\u202F\u3000]]"

        /**
         * Valid characters for IRI label defined in RFC 3987.
         */
        private val LABEL_CHAR = "a-zA-Z0-9$UCS_CHAR"

        /**
         * RFC 1035 Section 2.3.4 limits the labels to a maximum 63 octets.
         */
        private val IRI_LABEL =
            "[$LABEL_CHAR](?:[$LABEL_CHAR\\-]{0,61}[$LABEL_CHAR]){0,1}"

        private val IP_ADDRESS = Pattern.compile(
            "((25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9])\\.(25[0-5]|2[0-4]"
                    + "[0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9]|0)\\.(25[0-5]|2[0-4][0-9]|[0-1]"
                    + "[0-9]{2}|[1-9][0-9]|[1-9]|0)\\.(25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}"
                    + "|[1-9][0-9]|[0-9]))"
        )

        /**
         * Regular expression that matches domain names without a TLD
         */
        private val RELAXED_DOMAIN_NAME = (
                "(?:" + "(?:" + IRI_LABEL + "(?:\\.(?=\\S))" + "?)+" +
                        "|" + IP_ADDRESS + ")")

        private val HTTP_PROTOCOL = "(?i:http):\\/\\/"

        val HTTP_PROTOCOL_LENGTH = ("http://").length

        private val PORT_NUMBER = "\\:\\d{1,5}\\/?"

        /**
         * Regular expression pattern to match valid rosmaster URIs.
         * This assumes the port number and trailing "/" will be auto
         * populated (default port: 11311) if left out.
         */
        val URI = Pattern.compile(
            ("("
                    + WORD_BOUNDARY
                    + "(?:"
                    + "(?:" + HTTP_PROTOCOL + ")"
                    + "(?:" + RELAXED_DOMAIN_NAME + ")"
                    + "(?:" + PORT_NUMBER + ")?"
                    + ")"
                    + WORD_BOUNDARY
                    + ")")
        )

        val PORT = Pattern.compile(PORT_NUMBER)
    }

    companion object {
        /**
         * Default port number for master URI. Apended if the URI does not
         * contain a port number.
         */
        private val DEFAULT_PORT = "11311"
    }

    private var isFixing = false
    private var onTextChangedStart = 0
    private var beforeTextChangedCharSequence: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_master_chooser)

        btnConnect.setOnClickListener {
            doConnect()
        }

        etIP.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!isFixing && countDot(s) < 3) {
                    isFixing = true
                    etIP.setText(beforeTextChangedCharSequence)
                    etIP.setSelection(onTextChangedStart)
                    isFixing = false
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (!isFixing) {
                    beforeTextChangedCharSequence = s?.substring(0, s.length)
                }
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isFixing) {
                    onTextChangedStart = start
                }
            }
        })
        etIP.setText("192.168.22.16")
        etIP.setSelection(etIP.text.length)
        etPort.setText(DEFAULT_PORT)
    }

    private fun countDot(s: CharSequence?): Int {
        if (s == null) {
            return 0
        }
        return s.count {it == '.'}
    }

    private fun createNewMasterIntent(): Intent {
        val intent = Intent()
        val uri = getURI()
        intent.putExtra("ROS_MASTER_URI", uri)
        return intent
    }

    private fun getURI(): String {
        return "${tvHeader.text}${etIP.text}${tvColon.text}${etPort.text}"
    }

    private fun doConnect() {
        var tmpURI = getURI()

        // Check to see if the URI has a port.
        val portPattern = RosURIPattern.PORT
        if (!portPattern.matcher(tmpURI).find()) {
            // Append the default port to the URI and update the TextView.
            tmpURI = String.format(Locale.getDefault(), "%s:%d/", tmpURI, DEFAULT_PORT)
//            uriText.setText(tmpURI)
        }

        // Set the URI for connection.
        val uri = tmpURI

        // Prevent further edits while we verify the URI.
        // Note: This was placed after the URI port check due to odd behavior
        // with setting the connectButton to disabled.
//        uriText.setEnabled(false)
        btnConnect.isEnabled = false
        etIP.isEnabled = false
        etPort.isEnabled = false

        doAsync {
            try {
                val masterClient = MasterClient(URI(uri))
                val uriRes = masterClient.getUri(GraphName.of("android/master_chooser_activity"))
                KLog.d("MasterClient", "uriRes.result: ${uriRes.result}")
                KLog.d("MasterClient", "uriRes.statusCode: ${uriRes.statusCode}")
                KLog.d("MasterClient", "uriRes.statusMessage: ${uriRes.statusMessage}")
                KLog.d("MasterClient", "uriRes.isSuccess: ${uriRes.isSuccess}")
                for (i in uriRes.toList()) {
                    KLog.d("MasterClient", "uriRes.toList: $i")
                }
                uiThread {
                    toast(R.string.connected)
                }

                // If the displayed URI is valid then pack that into the intent.
                // Package the intent to be consumed by the calling activity.
                val intent = createNewMasterIntent()
                setResult(Activity.RESULT_OK, intent)
                finish()
                return@doAsync
            } catch (e: URISyntaxException) {
                uiThread {
                    toast(R.string.error_urisyntax)
                }
            } catch (e: XmlRpcTimeoutException) {
                uiThread {
                    toast(R.string.error_xmlrpctimeout)
                }
            } catch (e: Exception) {
                val exceptionMessage = e.message?:""
                uiThread {
                    when {
                        exceptionMessage.contains("ECONNREFUSED") -> toast(R.string.error_econnrefused)
                        exceptionMessage.contains("UnknownHost") -> toast(R.string.error_unknownhost)
                        else -> toast(R.string.error_exception)
                    }
                }
            }
            uiThread {
                btnConnect.isEnabled = true
                etIP.isEnabled = true
                etPort.isEnabled = true
            }
        }
    }
}