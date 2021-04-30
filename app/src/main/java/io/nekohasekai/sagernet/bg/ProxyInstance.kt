/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <sekai@neko.services>                    *
 * Copyright (C) 2021 by Max Lv <max.c.lv@gmail.com>                          *
 * Copyright (C) 2021 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import cn.hutool.json.JSONObject
import com.github.shadowsocks.plugin.PluginConfiguration
import com.github.shadowsocks.plugin.PluginManager
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.gson.gson
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig
import io.nekohasekai.sagernet.fmt.v2ray.buildV2RayConfig
import io.nekohasekai.sagernet.fmt.v2ray.buildXrayConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.utils.DirectBoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import libv2ray.Libv2ray
import libv2ray.V2RayPoint
import libv2ray.V2RayVPNServiceSupportsSet
import java.io.File
import java.io.IOException
import java.util.*
import io.nekohasekai.sagernet.plugin.PluginManager as PluginManagerS


class ProxyInstance(val profile: ProxyEntity) {

    lateinit var v2rayPoint: V2RayPoint
    lateinit var config: V2RayConfig
    lateinit var base: BaseService.Interface
    lateinit var wsForwarder: WebView
    lateinit var xrayPlugin: String
    lateinit var xrayConfig: String

    fun init(service: BaseService.Interface) {
        base = service
        v2rayPoint = Libv2ray.newV2RayPoint(SagerSupportClass(if (service is VpnService)
            service else null), false)
        if (profile.useExternalShadowsocks() || profile.useXray() || profile.type == 2) {
            v2rayPoint.domainName = "127.0.0.1:${DataStore.socksPort + 10}"
        } else {
            v2rayPoint.domainName =
                profile.requireBean().serverAddress + ":" + profile.requireBean().serverPort
        }
        config = buildV2RayConfig(profile)
        v2rayPoint.configureFileContent = gson.toJson(config).also {
            Logs.d(it)
        }

        if (profile.useXray()) {
            xrayPlugin = PluginManagerS.init("xtls-plugin")!!.path
            xrayConfig = gson.toJson(buildXrayConfig(profile)).also {
                Logs.d(it)
            }
        }
    }

    var cacheFiles = LinkedList<File>()

    @Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    fun start() {
        val bean = profile.requireBean()

        when {
            profile.useExternalShadowsocks() -> {
                bean as ShadowsocksBean
                val port = DataStore.socksPort + 10

                val proxyConfig = JSONObject().also {
                    it["server"] = bean.serverAddress
                    it["server_port"] = bean.serverPort
                    it["method"] = bean.method
                    it["password"] = bean.password
                    it["local_address"] = "127.0.0.1"
                    it["local_port"] = port
                    it["local_udp_address"] = "127.0.0.1"
                    it["local_udp_port"] = port
                    it["mode"] = "tcp_and_udp"
                    if (DataStore.enableLocalDNS) {
                        it["dns"] = "127.0.0.1:${DataStore.localDNSPort}"
                    } else {
                        it["dns"] = DataStore.remoteDNS
                    }

                    if (DataStore.ipv6Route && DataStore.preferIpv6) {
                        it["ipv6_first"] = true
                    }
                }

                if (bean.plugin.isNotBlank()) {
                    val pluginConfiguration = PluginConfiguration(bean.plugin ?: "")
                    PluginManager.init(pluginConfiguration)?.let { (path, opts, isV2) ->
                        proxyConfig["plugin"] = path
                        proxyConfig["plugin_opts"] = opts.toString()
                    }
                }

                Logs.d(proxyConfig.toStringPretty())

                val context =
                    if (Build.VERSION.SDK_INT < 24 || SagerNet.user.isUserUnlocked)
                        SagerNet.application else SagerNet.deviceStorage
                val configFile =
                    File(context.noBackupFilesDir,
                        "shadowsocks_" + SystemClock.elapsedRealtime() + ".json")
                configFile.parentFile.mkdirs()
                configFile.writeText(proxyConfig.toString())
                cacheFiles.add(configFile)

                val commands = mutableListOf(
                    File(SagerNet.application.applicationInfo.nativeLibraryDir,
                        Executable.SS_LOCAL).absolutePath,
                    "-c", configFile.absolutePath
                )

                base.data.processes!!.start(commands)
            }
            profile.type == 2 -> {
                bean as ShadowsocksRBean
                val port = DataStore.socksPort + 10

                val proxyConfig = JSONObject().also {

                    it["server"] = bean.serverAddress
                    it["server_port"] = bean.serverPort
                    it["method"] = bean.method
                    it["password"] = bean.password
                    it["protocol"] = bean.protocol
                    it["protocol_param"] = bean.protocolParam
                    it["obfs"] = bean.obfs
                    it["obfs_param"] = bean.obfsParam
                    it["ipv6"] = DataStore.ipv6Route
                    if (DataStore.enableLocalDNS) {
                        it["dns"] = "127.0.0.1:${DataStore.localDNSPort}"
                    } else {
                        it["dns"] = DataStore.remoteDNS
                    }
                }

                Logs.d(proxyConfig.toStringPretty())

                val context =
                    if (Build.VERSION.SDK_INT < 24 || SagerNet.user.isUserUnlocked)
                        SagerNet.application else SagerNet.deviceStorage

                val configFile =
                    File(context.noBackupFilesDir,
                        "shadowsocksr_" + SystemClock.elapsedRealtime() + ".json")
                configFile.parentFile.mkdirs()
                configFile.writeText(proxyConfig.toString())
                cacheFiles.add(configFile)

                val commands = mutableListOf(
                    File(SagerNet.application.applicationInfo.nativeLibraryDir,
                        Executable.SSR_LOCAL).absolutePath,
                    "-b", "127.0.0.1",
                    "-c", configFile.absolutePath,
                    "-l", "$port"
                )

                base.data.processes!!.start(commands)
            }
            profile.useXray() -> {
                val context =
                    if (Build.VERSION.SDK_INT < 24 || SagerNet.user.isUserUnlocked)
                        SagerNet.application else SagerNet.deviceStorage

                val configFile =
                    File(context.noBackupFilesDir,
                        "xray_" + SystemClock.elapsedRealtime() + ".json")
                configFile.parentFile.mkdirs()
                configFile.writeText(xrayConfig)
                cacheFiles.add(configFile)

                val commands = mutableListOf(
                    xrayPlugin, "-c", configFile.absolutePath
                )

                base.data.processes!!.start(commands)
            }
        }

        v2rayPoint.runLoop(DataStore.preferIpv6)
        runOnDefaultDispatcher {
            val url = "http://127.0.0.1:" + (DataStore.socksPort + 11) + "/"
            if (bean is StandardV2RayBean) {
                if (bean.type == "ws" && bean.wsUseBrowserForwarder) {
                    onMainDispatcher {
                        wsForwarder = WebView(base as Context)
                        @SuppressLint("SetJavaScriptEnabled")
                        wsForwarder.settings.javaScriptEnabled = true
                        wsForwarder.webViewClient = object : WebViewClient() {
                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                Logs.d("WebView load failed: $error")

                                runOnMainDispatcher {
                                    wsForwarder.loadUrl("about:blank")

                                    delay(1000L)
                                    wsForwarder.loadUrl(url)
                                }
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                super.onPageFinished(view, url)

                                Logs.d("WebView loaded: ${view.title}")

                            }
                        }
                        wsForwarder.loadUrl(url)
                    }
                }
            }
        }
    }

    fun stop() {
        v2rayPoint.stopLoop()

        if (::wsForwarder.isInitialized) {
            wsForwarder.loadUrl("about:blank")
            wsForwarder.destroy()
        }
    }

    fun stats(direct: String): Long {
        if (!::v2rayPoint.isInitialized) {
            return 0L
        }
        return v2rayPoint.queryStats("out", direct)
    }

    val uplink
        get() = stats("uplink").also {
            uplinkTotal += it
        }

    val downlink
        get() = stats("downlink").also {
            downlinkTotal += it
        }

    var uplinkTotal = 0L
    var downlinkTotal = 0L

    fun persistStats() {
        try {
            uplink
            downlink
            profile.tx += uplinkTotal
            profile.rx += downlinkTotal
            SagerDatabase.proxyDao.updateProxy(profile)
        } catch (e: IOException) {
            if (!DataStore.directBootAware) throw e // we should only reach here because we're in direct boot
            val profile = DirectBoot.getDeviceProfile()!!
            profile.tx += uplinkTotal
            profile.rx += downlinkTotal
            profile.dirty = true
            DirectBoot.update(profile)
            DirectBoot.listenForUnlock()
        }
    }

    fun shutdown(coroutineScope: CoroutineScope) {
        persistStats()
        cacheFiles.removeAll { it.delete(); true }
    }

    private class SagerSupportClass(val service: VpnService?) : V2RayVPNServiceSupportsSet {

        override fun onEmitStatus(p0: Long, status: String): Long {
            Logs.i("onEmitStatus $status")
            return 0L
        }

        override fun protect(l: Long): Boolean {
            return (service ?: return true).protect(l.toInt())
        }

        override fun shutdown(): Long {
            return 0
        }
    }


}