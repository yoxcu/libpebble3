package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.NotificationConfigFlow
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
) : PrivatePKJSInterface(
    jsRunner, device, scope, outgoingAppMessages, logMessages,
    jsTokenUtil, remoteTimelineEmulator, httpInterceptorManager, notificationConfigFlow,
) {
    override fun getVersionCode(): Int = 1
}
