package eu.pkgsoftware.babybuddywidgets.networking.babybuddy

import eu.pkgsoftware.babybuddywidgets.debugging.GlobalDebugObject
import eu.pkgsoftware.babybuddywidgets.local.LocalBabyBuddyRepository
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Profile
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TimeEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Random
import kotlin.reflect.KClass

const val LOGTAG = "ClientV2Local"

fun genRequestId(): String {
    return Random().nextInt(0xFFFFFF + 1).toString(16).padStart(6, '0')
}

class InvalidBody : Exception("Invalid body")

data class PaginatedResult<T>(
    val entries: List<T>,
    val offset: Int,
    val totalCount: Int,
)

/**
 * Pure local standalone client facade delegating directly to [LocalBabyBuddyRepository]
 * with zero HTTP/network calls.
 */
class Client(val repository: LocalBabyBuddyRepository) {

    suspend fun getProfile(): Profile = withContext(Dispatchers.IO) {
        repository.getLocalProfile()
    }

    suspend fun <T : Any> getEntries(
        itemClass: KClass<T>,
        offset: Int = 0,
        limit: Int = 100,
        childId: Int? = null,
        extraArgs: Map<String, String> = emptyMap(),
    ): PaginatedResult<T> = withContext(Dispatchers.IO) {
        val reqId = genRequestId()
        GlobalDebugObject.log("$reqId LocalClient::getEntries ${itemClass.simpleName} childId=$childId offset=$offset limit=$limit")
        repository.getEntries(
            itemClass = itemClass,
            offset = offset,
            limit = limit,
            childId = childId,
            extraArgs = extraArgs,
        )
    }

    suspend fun <T : TimeEntry> createEntry(itemClass: KClass<T>, item: T): T = withContext(Dispatchers.IO) {
        val reqId = genRequestId()
        GlobalDebugObject.log("$reqId LocalClient::createEntry ${itemClass.simpleName}")
        repository.createEntry(item)
    }

    suspend fun <T : TimeEntry> updateEntry(item: T): T = withContext(Dispatchers.IO) {
        val reqId = genRequestId()
        GlobalDebugObject.log("$reqId LocalClient::updateEntry ${item::class.simpleName} id=${item.id}")
        repository.updateEntry(item)
    }

    suspend fun <T : TimeEntry> deleteEntry(item: T) = withContext(Dispatchers.IO) {
        val reqId = genRequestId()
        GlobalDebugObject.log("$reqId LocalClient::deleteEntry ${item::class.simpleName} id=${item.id}")
        repository.deleteEntry(item)
        Unit
    }
}
