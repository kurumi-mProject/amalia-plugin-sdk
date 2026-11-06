package org.amalia.platform.protocol.sdk

import org.amalia.platform.protocol.Registration
import org.amalia.platform.protocol.ui.UiDocument

/** Declarative screens rendered by the host, with actions bound through the existing protocol.
 * Routes and document IDs must use the authenticated package namespace. Registration grants no
 * invocation authority. The host owns generation fencing and withdraws screens on deactivation.
 */
interface PluginUiAccess {
    fun registerScreen(route: String, title: String, document: UiDocument): Registration
}

/** UI is unavailable unless the host explicitly supplies reviewed, caller-scoped access. */
object DeniedPluginUiAccess : PluginUiAccess {
    override fun registerScreen(route: String, title: String, document: UiDocument): Registration =
        throw SecurityException("Plugin UI access is not granted")
}
