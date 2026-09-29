package org.polycare.app.medicine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.polycare.app.knowledge.KnowledgeHit
import org.polycare.app.knowledge.KnowledgeRepository
import javax.inject.Inject

enum class CardKind(val label: String) { MEDICINE("Medicines"), COUNSELLING("Counselling") }

/**
 * A topic card. It holds only a *question* for the knowledge base, never clinical text of its
 * own: the dose, the advice and the page all come from the official ASHA module passages
 * indexed on the phone, so every card is cited and nothing here can drift from the source.
 */
data class TopicCard(val id: String, val kind: CardKind, val title: String, val caption: String, val query: String)

val TopicCards = listOf(
    TopicCard("ors", CardKind.MEDICINE, "ORS", "Oral rehydration for diarrhoea", "how to prepare and give ORS solution to a child with diarrhoea"),
    TopicCard("zinc", CardKind.MEDICINE, "Zinc", "Zinc tablets in diarrhoea", "zinc tablets dose for child with diarrhoea and how many days"),
    TopicCard("ifa", CardKind.MEDICINE, "IFA tablets", "Iron and folic acid", "iron folic acid tablets dose in pregnancy and for children"),
    TopicCard("vita", CardKind.MEDICINE, "Vitamin A", "Supplement schedule", "vitamin A supplementation schedule for children"),
    TopicCard("calcium", CardKind.MEDICINE, "Calcium", "In pregnancy and after birth", "calcium supplementation during pregnancy and lactation"),
    TopicCard("chx", CardKind.MEDICINE, "Chlorhexidine", "Newborn cord care", "chlorhexidine application on the newborn umbilical cord"),
    TopicCard("albendazole", CardKind.MEDICINE, "Albendazole", "Deworming", "albendazole deworming tablets for children"),
    TopicCard("paracetamol", CardKind.MEDICINE, "Paracetamol", "Fever in children", "paracetamol for fever in a child dose"),

    TopicCard("bf", CardKind.COUNSELLING, "Breastfeeding", "Start early, exclusive for 6 months", "exclusive breastfeeding counselling early initiation"),
    TopicCard("kmc", CardKind.COUNSELLING, "Kangaroo care", "Low birth weight babies", "kangaroo mother care for low birth weight baby"),
    TopicCard("cf", CardKind.COUNSELLING, "Complementary feeding", "From 6 months", "complementary feeding from six months what to give"),
    TopicCard("hand", CardKind.COUNSELLING, "Handwashing", "Hygiene and sanitation", "handwashing steps hygiene counselling"),
    TopicCard("fp", CardKind.COUNSELLING, "Birth spacing", "Family planning choices", "family planning methods counselling birth spacing"),
    TopicCard("danger", CardKind.COUNSELLING, "Pregnancy danger signs", "When to go to the facility", "danger signs in pregnancy that need referral"),
    TopicCard("immun", CardKind.COUNSELLING, "Immunisation", "Schedule and missed doses", "immunisation schedule for infants and what to do about missed doses"),
)

sealed interface CardState {
    data object Loading : CardState
    data class Found(val hits: List<KnowledgeHit>) : CardState
    data object NothingFound : CardState
    data object NotInstalled : CardState
}

@HiltViewModel
class MedicineViewModel @Inject constructor(
    private val knowledge: KnowledgeRepository,
) : ViewModel() {
    private val _kind = MutableStateFlow(CardKind.MEDICINE)
    val kind: StateFlow<CardKind> = _kind.asStateFlow()

    private val _expanded = MutableStateFlow<String?>(null)
    val expanded: StateFlow<String?> = _expanded.asStateFlow()

    private val _results = MutableStateFlow<Map<String, CardState>>(emptyMap())
    val results: StateFlow<Map<String, CardState>> = _results.asStateFlow()

    fun setKind(kind: CardKind) {
        _kind.value = kind
        _expanded.value = null
    }

    /** Opens a card (closing any other) and looks its topic up once. */
    fun toggle(card: TopicCard) {
        if (_expanded.value == card.id) {
            _expanded.value = null
            return
        }
        _expanded.value = card.id
        if (_results.value[card.id] is CardState.Found) return
        _results.update { it + (card.id to CardState.Loading) }
        viewModelScope.launch {
            knowledge.open()
            val result = knowledge.search(card.query, limit = 3)
            val state = when {
                result == null -> CardState.NotInstalled
                result.hits.isEmpty() -> CardState.NothingFound
                else -> CardState.Found(result.hits)
            }
            _results.update { it + (card.id to state) }
        }
    }
}
