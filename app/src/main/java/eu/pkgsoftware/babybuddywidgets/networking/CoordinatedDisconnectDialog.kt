package eu.pkgsoftware.babybuddywidgets.networking

import eu.pkgsoftware.babybuddywidgets.BaseFragment
import eu.pkgsoftware.babybuddywidgets.ConnectingDialogInterface
import eu.pkgsoftware.babybuddywidgets.CredStore

/**
 * No-op disconnect dialog retained for API compatibility in pure local standalone mode.
 */
class CoordinatedDisconnectDialog(val fragment: BaseFragment, val credStore: CredStore) {
    val timeout: Long = 0L
    var timeoutGracePeriod = 1000L

    private class LocalNoopConnectingInterface : ConnectingDialogInterface {
        override fun interruptLoading(): Boolean = false
        override fun showConnecting(currentTimeout: Long, error: Exception?) {}
        override fun hideConnecting() {}
    }

    fun getInterface(): ConnectingDialogInterface {
        return LocalNoopConnectingInterface()
    }
}
