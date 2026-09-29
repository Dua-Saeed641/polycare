package org.polycare.app.sync

import org.polycare.app.conflicts.ConflictsRepository
import org.polycare.app.team.RemoteTipVerifier
import org.polycare.app.team.TeamMemoryRepository
import org.polycare.common.EventLog
import org.polycare.common.EventLog.Category
import org.polycare.common.sync.SemanticMerkle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Semantic anti-entropy (ARCHITECTURE.md §5.6): finds the team tips this phone is missing without
 * listing every tip. It compares the phone's [SemanticMerkle] with the gateway's one level at a time,
 * descends only into subtrees whose hashes differ, and at a differing 4-digit region asks the gateway
 * which op ids it holds there. Tips are then fetched by id, signature-checked
 * ([RemoteTipVerifier]) and applied idempotently.
 *
 * Because SimHash gives similar meanings the same prefix, "the phone and the cloud disagree about
 * *dengue guidance*" is one subtree, not hundreds of rows.
 */
@Singleton
class AntiEntropy @Inject constructor(
    private val client: GatewayClient,
    private val team: TeamMemoryRepository,
    private val conflicts: ConflictsRepository,
    private val identity: DeviceIdentity,
    private val events: EventLog,
) {
    data class Result(
        val fetched: Int = 0,
        /** Fetched tips whose signature or shape did not check out; they are dropped. */
        val rejected: Int = 0,
        /** SimHash regions where the cloud had tips this phone lacked. */
        val divergedRegions: Int = 0,
        /** Short labels of the newest tips received, e.g. for "2 topics updated". */
        val topics: List<String> = emptyList(),
        /** Possible contradictions filed in the Conflict Inbox. */
        val conflicts: Int = 0,
    )

    fun reconcile(): Result {
        val local = SemanticMerkle(team.merkleEntries())
        val known = team.knownIds()
        val missing = LinkedHashSet<String>()
        var diverged = 0

        fun descend(prefix: String) {
            val remote = client.merkleChildren(prefix)
            val mine = local.children(prefix)
            for (i in 0 until 16) {
                if (remote[i] == mine[i] || remote[i] == SemanticMerkle.EMPTY) continue
                val next = prefix + HEX[i]
                if (next.length == 4) {
                    val lacking = client.merkleLeaf(next).filter { it !in known }
                    if (lacking.isNotEmpty()) { diverged++; missing += lacking }
                } else {
                    descend(next)
                }
            }
        }
        descend("")
        if (missing.isEmpty()) return Result()

        var fetched = 0
        var rejected = 0
        var filed = 0
        val topics = ArrayList<String>()
        for (chunk in missing.chunked(FETCH_CHUNK)) {
            val items = client.fetchOps(chunk)
            for (i in 0 until items.length()) {
                val tip = RemoteTipVerifier.verify(items.getJSONObject(i), identity.deviceId)
                if (tip == null) { rejected++; continue }
                val candidates = team.applyRemote(tip)
                fetched++
                if (topics.size < MAX_TOPICS) topics += tip.text.take(48)
                filed += conflicts.addTipConflicts(tip, candidates)
            }
        }
        events.record(
            Category.TEAM, "Team memory reconciled",
            mapOf("fetched" to fetched, "rejected" to rejected, "regions" to diverged, "conflicts" to filed),
        )
        return Result(fetched, rejected, diverged, topics, filed)
    }

    private companion object {
        const val HEX = "0123456789abcdef"
        const val FETCH_CHUNK = 50
        const val MAX_TOPICS = 3
    }
}
