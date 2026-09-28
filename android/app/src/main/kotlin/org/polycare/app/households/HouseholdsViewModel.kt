package org.polycare.app.households

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class HouseholdsViewModel @Inject constructor(private val repo: HouseholdsRepository) : ViewModel() {
    val households: StateFlow<List<Household>> = repo.households
    val members: StateFlow<List<Member>> = repo.members

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
}
