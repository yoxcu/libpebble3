package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.NotificationConfigFlow
import io.rebble.libpebblecommon.permissions.AppPermissions
import io.rebble.libpebblecommon.permissions.RunningApps
import io.rebble.libpebblecommon.plugin.PluginRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow

class JvmPrivatePKJSInterface(
    jsRunner: JsRunner,
    device: CompanionAppDevice,
    scope: CoroutineScope,
    outgoingAppMessages: MutableSharedFlow<AppMessageRequest>,
    logMessages: Channel<String>,
    jsTokenUtil: JsTokenUtil,
    remoteTimelineEmulator: RemoteTimelineEmulator,
    httpInterceptorManager: HttpInterceptorManager,
    notificationConfigFlow: NotificationConfigFlow,
    pluginRegistry: PluginRegistry,
    permissions: AppPermissions,
    runningApps: RunningApps,
) : PrivatePKJSInterface(
    jsRunner, device, scope, outgoingAppMessages, logMessages,
    jsTokenUtil, remoteTimelineEmulator, httpInterceptorManager, notificationConfigFlow, pluginRegistry,
    permissions, runningApps,
) {
    override fun getVersionCode(): Int = 1
}
