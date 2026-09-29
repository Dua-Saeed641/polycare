package org.polycare.app.households

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class HouseholdsViewModel @Inject constructor(private val repo: HouseholdsRepository) : ViewModel() {
    val households: StateFlow<List<Household>> = repo.households
    val members: StateFlow<List<Member>> = repo.members
    val storageWarning: StateFlow<String?> = repo.storageWarning

    fun addHousehold(headOfHousehold: String, village: String, consentGiven: Boolean): Boolean {
        if (headOfHousehold.isBlank() || village.isBlank()) return false
        repo.addHousehold(headOfHousehold.trim(), village.trim(), consentGiven)
        return true
    }

    /** Returns false if the name is blank or the household hasn't given consent. */
    fun addMember(householdId: String, name: String, age: Int, relation: String): Boolean {
        if (name.isBlank()) return false
        return repo.addMember(householdId, name.trim(), age, relation) != null
    }

    fun updateHousehold(householdId: String, head: String, village: String): Boolean = repo.updateHousehold(householdId, head, village)

    fun updateMember(memberId: String, name: String, age: Int, relation: String): Boolean = repo.updateMember(memberId, name, age, relation)

    fun deleteMember(memberId: String): Boolean = repo.deleteMember(memberId)

    fun deleteHousehold(householdId: String): Boolean = repo.deleteHousehold(householdId)

    fun withdrawConsent(householdId: String): Boolean = repo.withdrawConsent(householdId)

    /** Logs a visit that was not on the due list, optionally scheduling a follow-up. */
    fun logVisit(
        householdId: String,
        memberId: String?,
        memberName: String?,
        type: VisitType,
        notes: String,
        highRisk: Boolean,
        followUpInDays: Int?,
    ): Boolean = repo.recordVisit(
        householdId = householdId, memberId = memberId, memberName = memberName, type = type,
        notes = notes.trim().ifBlank { "Visit recorded" }, highRisk = highRisk, followUpInDays = followUpInDays,
    ) != null

    /** Explicit recovery when the encrypted store cannot be opened. Returns households rebuilt. */
    fun rebuildFromChangeLog(): Int = repo.recoverFromOpLog()
}
