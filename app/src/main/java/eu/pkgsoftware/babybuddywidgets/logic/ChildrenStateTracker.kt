package eu.pkgsoftware.babybuddywidgets.logic

import android.os.Handler
import android.os.Looper
import eu.pkgsoftware.babybuddywidgets.ActivityStore
import eu.pkgsoftware.babybuddywidgets.BaseFragmentDisconnectInterface
import eu.pkgsoftware.babybuddywidgets.local.LocalBabyBuddyRepository
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.Client
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Child
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.ChildrenList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

typealias ChildListener = (Array<Child>) -> Unit

/**
 * Event-driven local child state tracker: reacts instantaneously to [LocalBabyBuddyRepository]
 * updates without any 5-second background network polling.
 */
class ChildrenStateTracker(
    val v2Client: Client,
    val activityStore: ActivityStore,
    val requestScheduler: RequestScheduler,
    val disconnectInterface: BaseFragmentDisconnectInterface,
) {
    var children: Array<Child> = emptyArray()

    private val listeners = mutableListOf<ChildListener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val trackerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val repoListener = LocalBabyBuddyRepository.ChangeListener {
        trackerScope.launch {
            refreshChildrenList()
        }
    }

    init {
        children = v2Client.repository.listChildren().sortedBy { it.id }.toTypedArray()
        v2Client.repository.addChangeListener(repoListener)
    }

    fun addChildListener(listener: ChildListener, triggerOnAdd: Boolean = true) {
        listeners.add(listener)
        if (triggerOnAdd) {
            listener(children)
        }
    }

    fun removeChildListener(listener: ChildListener) {
        listeners.remove(listener)
    }

    suspend fun refreshChildrenList() {
        val loaded = v2Client.repository.listChildren().sortedBy { it.id }.toTypedArray()
        disconnectInterface.setDisconnected("Local children loaded", false)
        mainHandler.post {
            if (!children.contentEquals(loaded)) {
                val newChildData = ChildrenList(loaded)
                children = newChildData.children
                activityStore.login("children-state-tracker", children)
                for (listener in ArrayList(listeners)) {
                    listener(children)
                }
            }
        }
    }

    fun forceNotifyListeners() {
        val loaded = v2Client.repository.listChildren().sortedBy { it.id }.toTypedArray()
        children = loaded
        for (listener in ArrayList(listeners)) {
            listener(children)
        }
    }

    fun destroy() {
        v2Client.repository.removeChangeListener(repoListener)
        trackerScope.cancel()
    }
}
