package com.nizaprime.ai

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var webView: WebView

    private var pendingPickerReply: JavaScriptReplyProxy? = null
    private var pendingPickerRequestId: String? = null

    companion object {
        private const val APP_URL = "https://niza-ai-app.lovable.app"
        private const val APP_HOST = "niza-ai-app.lovable.app"
        private const val BRIDGE_NAME = "NizaNative"
        private const val FILE_PICKER_REQUEST = 4101
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
            }
        }

        CookieManager.getInstance().setAcceptCookie(true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url

                if (uri.scheme == "https" && uri.host == APP_HOST) {
                    return false
                }

                if (uri.scheme == "https" || uri.scheme == "http") {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    return true
                }

                return true
            }
        }

        webView.webChromeClient = WebChromeClient()

        setContentView(webView)

        installNativeBridge()
        webView.loadUrl(APP_URL)
    }

    private fun installNativeBridge() {
        if (!WebViewFeature.isFeatureSupported(
                WebViewFeature.WEB_MESSAGE_LISTENER
            )
        ) {
            return
        }

        WebViewCompat.addWebMessageListener(
            webView,
            BRIDGE_NAME,
            setOf("https://$APP_HOST")
        ) { _, message, sourceOrigin, isMainFrame, replyProxy ->

            val trustedOrigin =
                sourceOrigin.scheme == "https" &&
                sourceOrigin.host == APP_HOST &&
                (sourceOrigin.port == -1 || sourceOrigin.port == 443)

            if (!trustedOrigin || !isMainFrame) {
                return@addWebMessageListener
            }

            val request = try {
                JSONObject(message.data ?: "")
            } catch (_: Exception) {
                replyProxy.postMessage(
                    errorResponse("", "Invalid JSON request")
                )
                return@addWebMessageListener
            }

            val requestId = request.optString("id", "")
            val action = request.optString("action", "")
            val params = request.optJSONObject("params") ?: JSONObject()

            try {
                when (action) {
                    "getDeviceInfo" -> {
                        val result = JSONObject()
                            .put("manufacturer", Build.MANUFACTURER)
                            .put("model", Build.MODEL)
                            .put("androidVersion", Build.VERSION.RELEASE)
                            .put("sdkInt", Build.VERSION.SDK_INT)

                        replyProxy.postMessage(
                            successResponse(requestId, result)
                        )
                    }

                    "pickFile" -> {
                        if (pendingPickerReply != null) {
                            replyProxy.postMessage(
                                errorResponse(
                                    requestId,
                                    "A file picker is already open"
                                )
                            )
                            return@addWebMessageListener
                        }

                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = params.optString("mimeType", "*/*")
                        }

                        pendingPickerReply = replyProxy
                        pendingPickerRequestId = requestId

                        try {
                            startActivityForResult(
                                intent,
                                FILE_PICKER_REQUEST
                            )
                        } catch (e: Exception) {
                            pendingPickerReply = null
                            pendingPickerRequestId = null
                            replyProxy.postMessage(
                                errorResponse(
                                    requestId,
                                    e.message ?: "Could not open file picker"
                                )
                            )
                        }
                    }

                    "openSettings" -> {
                        startActivity(Intent(Settings.ACTION_SETTINGS))

                        replyProxy.postMessage(
                            successResponse(
                                requestId,
                                JSONObject().put("opened", true)
                            )
                        )
                    }

                    "openExternalUrl" -> {
                        val uri = Uri.parse(params.optString("url", ""))

                        if (
                            uri.scheme != "https" &&
                            uri.scheme != "http"
                        ) {
                            replyProxy.postMessage(
                                errorResponse(
                                    requestId,
                                    "Only HTTP and HTTPS links are supported"
                                )
                            )
                            return@addWebMessageListener
                        }

                        startActivity(Intent(Intent.ACTION_VIEW, uri))

                        replyProxy.postMessage(
                            successResponse(
                                requestId,
                                JSONObject().put("opened", true)
                            )
                        )
                    }

                    "launchApp" -> {
                        val packageName =
                            params.optString("packageName", "")

                        if (
                            packageName.isBlank() ||
                            packageName.contains(" ")
                        ) {
                            replyProxy.postMessage(
                                errorResponse(
                                    requestId,
                                    "A valid packageName is required"
                                )
                            )
                            return@addWebMessageListener
                        }

                        val launchIntent =
                            packageManager.getLaunchIntentForPackage(
                                packageName
                            )

                        if (launchIntent == null) {
                            replyProxy.postMessage(
                                errorResponse(
                                    requestId,
                                    "App not found or not launchable"
                                )
                            )
                        } else {
                            startActivity(launchIntent)
                            replyProxy.postMessage(
                                successResponse(
                                    requestId,
                                    JSONObject().put("launched", true)
                                )
                            )
                        }
                    }

                    else -> {
                        replyProxy.postMessage(
                            errorResponse(
                                requestId,
                                "Unsupported native action: $action"
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                replyProxy.postMessage(
                    errorResponse(
                        requestId,
                        e.message ?: "Native action failed"
                    )
                )
            }
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != FILE_PICKER_REQUEST) return

        val reply = pendingPickerReply
        val requestId = pendingPickerRequestId ?: ""

        pendingPickerReply = null
        pendingPickerRequestId = null

        if (reply == null) return

        if (resultCode != RESULT_OK || data?.data == null) {
            reply.postMessage(
                errorResponse(requestId, "File selection cancelled")
            )
            return
        }

        val uri = data.data!!

        try {
            if (
                data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
            ) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        } catch (_: Exception) {
            // A temporary URI grant may still be sufficient.
        }

        var displayName = "selected-file"

        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(
                        OpenableColumns.DISPLAY_NAME
                    )
                    if (index >= 0) {
                        displayName = cursor.getString(index) ?: displayName
                    }
                }
            }
        } catch (_: Exception) {
            // Keep the fallback display name.
        }

        val result = JSONObject()
            .put("uri", uri.toString())
            .put("name", displayName)
            .put(
                "mimeType",
                contentResolver.getType(uri) ?: "application/octet-stream"
            )

        reply.postMessage(successResponse(requestId, result))
    }

    private fun successResponse(
        id: String,
        result: JSONObject
    ): String {
        return JSONObject()
            .put("id", id)
            .put("ok", true)
            .put("result", result)
            .toString()
    }

    private fun errorResponse(
        id: String,
        message: String
    ): String {
        return JSONObject()
            .put("id", id)
            .put("ok", false)
            .put(
                "error",
                JSONObject().put("message", message)
            )
            .toString()
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        pendingPickerReply = null
        pendingPickerRequestId = null

        if (::webView.isInitialized) {
            webView.destroy()
        }

        super.onDestroy()
    }
}
