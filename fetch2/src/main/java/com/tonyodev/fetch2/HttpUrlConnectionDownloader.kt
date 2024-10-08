package com.tonyodev.fetch2

import com.tonyodev.fetch2core.*
import java.io.InputStream
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import kotlin.collections.HashMap

/**
 * The default Downloader used by Fetch for downloading requests.
 * This downloader uses a HttpUrlConnection to perform http requests
 * @see {@link com.tonyodev.fetch2core.Downloader}
 * */
open class HttpUrlConnectionDownloader @JvmOverloads constructor(
        /**
         * HttpUrlConnectionPreferences to set preference settings for the
         * HttpUrlConnectionDownloader HttpUrlConnection client.
         * @see com.tonyodev.fetch2.HttpUrlConnectionDownloader.HttpUrlConnectionPreferences
         * */
        httpUrlConnectionPreferences: HttpUrlConnectionPreferences? = null,
        /** The file downloader type used to download a request.
         * The SEQUENTIAL type downloads bytes in sequence.
         * The PARALLEL type downloads bytes in parallel.
         * */
        private val fileDownloaderType: Downloader.FileDownloaderType = Downloader.FileDownloaderType.SEQUENTIAL) : Downloader<HttpURLConnection, Void> {

    constructor(fileDownloaderType: Downloader.FileDownloaderType) : this(null, fileDownloaderType)

    private val connectionPrefs = httpUrlConnectionPreferences ?: HttpUrlConnectionPreferences()
    private val connections: MutableMap<Downloader.Response, HttpURLConnection> = Collections.synchronizedMap(HashMap<Downloader.Response, HttpURLConnection>())
    private val cookieManager = getDefaultCookieManager()

    override fun onPreClientExecute(client: HttpURLConnection, request: Downloader.ServerRequest): Void? {
        client.requestMethod = request.requestMethod
        client.readTimeout = connectionPrefs.readTimeout
        client.connectTimeout = connectionPrefs.connectTimeout
        client.useCaches = connectionPrefs.usesCache
        client.defaultUseCaches = connectionPrefs.usesDefaultCache
        client.instanceFollowRedirects = connectionPrefs.followsRedirect
        client.doInput = true
        request.headers.entries.forEach {
            client.addRequestProperty(it.key, it.value)
        }
        return null
    }

    private fun getCleanedHeaders(responseHeaders: MutableMap<String?, List<String>?>): MutableMap<String, List<String>> {
        val headers = mutableMapOf<String, List<String>>()
        for (responseHeader in responseHeaders) {
            val key = responseHeader.key
            if (key != null) {
                headers[key] = responseHeader.value ?: emptyList()
            }
        }
        return headers
    }

    override fun execute(request: Downloader.ServerRequest, interruptMonitor: InterruptMonitor): Downloader.Response? {
        CookieHandler.setDefault(cookieManager)
        var httpUrl = URL(request.url)
        var client = httpUrl.openConnection() as HttpURLConnection
        onPreClientExecute(client, request)
        if (client.getRequestProperty("Referer") == null) {
            val referer = getRefererFromUrl(request.url)
            client.addRequestProperty("Referer", referer)
        }
        client.connect()
        var responseHeaders = getCleanedHeaders(client.headerFields)
        var code = client.responseCode
        if ((code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_MOVED_PERM
                        || code == HttpURLConnection.HTTP_SEE_OTHER) && getHeaderValue(responseHeaders, "Location") != null) {
            try {
                client.disconnect()
            } catch (_: Exception) {
            }
            httpUrl = URL(getHeaderValue(responseHeaders, "Location") ?: "")
            client = httpUrl.openConnection() as HttpURLConnection
            onPreClientExecute(client, request)
            if (client.getRequestProperty("Referer") == null) {
                val referer = getRefererFromUrl(request.url)
                client.addRequestProperty("Referer", referer)
            }
            client.connect()
            responseHeaders = getCleanedHeaders(client.headerFields)
            code = client.responseCode
        }
        var success = false
        var contentLength = -1L
        var byteStream: InputStream? = null
        var errorResponseString: String? = null
        var hash = ""
        if (isResponseOk(code)) {
            success = true
            contentLength = getContentLengthFromHeader(responseHeaders, contentLength)
            byteStream = client.inputStream
            hash = getContentHash(responseHeaders)
        } else {
            errorResponseString = copyStreamToString(client.errorStream, false)
        }

        val acceptsRanges = acceptRanges(code, responseHeaders)

        onServerResponse(request, Downloader.Response(
                code = code,
                isSuccessful = success,
                contentLength = contentLength,
                byteStream = null,
                request = request,
                hash = hash,
                responseHeaders = client.headerFields,
                acceptsRanges = acceptsRanges,
                errorResponse = errorResponseString))

        val response = Downloader.Response(
                code = code,
                isSuccessful = success,
                contentLength = contentLength,
                byteStream = byteStream,
                request = request,
                hash = hash,
                responseHeaders = responseHeaders,
                acceptsRanges = acceptsRanges,
                errorResponse = errorResponseString)

        connections[response] = client
        return response
    }

    private fun isResponseOk(responseCode: Int): Boolean {
        return responseCode in 200..299
    }

    override fun disconnect(response: Downloader.Response) {
        if (connections.contains(response)) {
            val client = connections[response]
            connections.remove(response)
            disconnectClient(client)
        }
    }

    override fun getContentHash(responseHeaders: MutableMap<String, List<String>>): String {
        return getHeaderValue(responseHeaders, "Content-MD5") ?: ""
    }

    override fun close() {
        connections.entries.forEach {
            disconnectClient(it.value)
        }
        connections.clear()
    }

    private fun disconnectClient(client: HttpURLConnection?) {
        try {
            client?.disconnect()
        } catch (_: Exception) {

        }
    }

    override fun getFileSlicingCount(request: Downloader.ServerRequest, contentLength: Long): Int? {
        return null
    }

    override fun getRequestFileDownloaderType(request: Downloader.ServerRequest, supportedFileDownloaderTypes: Set<Downloader.FileDownloaderType>): Downloader.FileDownloaderType {
        return fileDownloaderType
    }

    override fun verifyContentHash(request: Downloader.ServerRequest, hash: String): Boolean {
        if (hash.isEmpty()) {
            return true
        }
        val fileHash = getFileMd5String(request.file)
        return fileHash?.contentEquals(hash) ?: true
    }

    override fun onServerResponse(request: Downloader.ServerRequest, response: Downloader.Response) {

    }

    override fun getHeadRequestMethodSupported(request: Downloader.ServerRequest): Boolean {
        return false
    }

    override fun getRequestBufferSize(request: Downloader.ServerRequest): Int {
        return DEFAULT_BUFFER_SIZE
    }

    override fun getRequestContentLength(request: Downloader.ServerRequest): Long {
        return getRequestContentLength(request, this)
    }

    override fun getRequestSupportedFileDownloaderTypes(request: Downloader.ServerRequest): Set<Downloader.FileDownloaderType> {
        if (fileDownloaderType == Downloader.FileDownloaderType.SEQUENTIAL) {
            return mutableSetOf(fileDownloaderType)
        }
        return try {
            getRequestSupportedFileDownloaderTypes(request, this)
        } catch (e: Exception) {
            mutableSetOf(fileDownloaderType)
        }
    }

    /**
     * Use this class to set preference settings for the
     * HttpUrlConnectionDownloader HttpUrlConnection client.
     * */
    open class HttpUrlConnectionPreferences {
        var readTimeout = 20_000
        var connectTimeout = 15_000
        var usesCache = false
        var usesDefaultCache = false
        var followsRedirect = true
    }

}
