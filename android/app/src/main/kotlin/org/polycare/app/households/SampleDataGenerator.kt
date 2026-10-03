package org.polycare.app.households

import org.polycare.common.HlcClock
import org.polycare.common.UuidV7
import org.polycare.common.EventLog
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates realistic sample data for demo and testing.
 * Creates households with members and visit history across multiple villages.
 */
@Singleton
class SampleDataGenerator @Inject constructor(
    private val repo: HouseholdsRepository,
    private val events: EventLog,
) {
    
    // Realistic Indian names and villages
    private val femaleNames = listOf(
        "Priya Devi", "Sunita Kumari", "Rekha Sharma", "Geeta Patel", "Kavita Singh",
        "Meera Verma", "Anjali Reddy", "Pooja Yadav", "Rani Gupta", "Savita Joshi",
        "Laxmi Bai", "Radha Nair", "Shakuntala Rao", "Uma Desai", "Vandana Pillai"
    )
    
    private val maleNames = listOf(
        "Ramesh Kumar", "Suresh Prasad", "Mahesh Singh", "Rajesh Patel", "Dinesh Yadav",
        "Mukesh Sharma", "Rakesh Verma", "Naresh Gupta", "Yogesh Reddy", "Hitesh Joshi",
        "Anil Kumar", "Vijay Singh", "Ajay Patel", "Sanjay Sharma", "Manoj Yadav"
    )
    
    private val childNames = listOf(
        "Aarav", "Vivaan", "Aditya", "Arjun", "Sai", "Reyansh", "Ayush", "Krishna",
        "Ananya", "Diya", "Ishita", "Navya", "Priya", "Riya", "Sara", "Tara",
        "Dhruv", "Kabir", "Rohan", "Karan", "Lakshmi", "Meera", "Nisha", "Pari"
    )
    
    private val villages = listOf(
        "Rampur", "Shivnagar", "Lakshmipur", "Ganeshpur", "Hanumanpur",
        "Durga Nagar", "Krishna Nagar", "Ram Nagar", "Sita Nagar", "Radha Nagar"
    )
    
    private val relations = listOf("Wife", "Husband", "Son", "Daughter", "Mother", "Father", "Grandmother", "Grandfather")
    
    // Realistic visit notes in simple English (ASHA worker style)
    private val ancNotes = listOf(
        "BP 120/80. Weight 58kg. Baby movement normal. Iron tablets given. Next visit in 2 weeks.",
        "All tests normal. Advised rest and healthy food. Danger signs explained. TT injection given.",
        "Complaints of back pain. Advised exercises. Urine test done. All normal. Continue IFA tablets.",
        "Weight gain good. Baby heartbeat heard. Hospital delivery booked. Danger signs card given.",
        "First ANC visit. Registration done. Blood test sent. Advised nutritious diet and rest."
    )
    
    private val pncNotes = listOf(
        "Mother and baby both healthy. Breastfeeding well. Stitches healing good. Next visit in 1 week.",
        "Baby weight 3.2kg. Mother taking rest. Family helping well. Iron tablets continued.",
        "Exclusive breastfeeding advised. Danger signs explained. Family planning discussed.",
        "6 weeks checkup done. Both healthy. Contraception counseling given. Next immunization due.",
        "Baby gaining weight well. Mother looks healthy. Advised continued breastfeeding."
    )
    
    private val childIllnessNotes = listOf(
        "Fever 99°F. Cough and cold. ORS given. Advised warm fluids. Follow up tomorrow.",
        "Diarrhea since 2 days. Given ORS. Mother taught preparation. Zinc tablets given.",
        "Cough with breathing difficulty. Referred to PHC immediately. Mother accompanied.",
        "Mild fever. No danger signs. Paracetamol advised. Plenty of fluids. Check tomorrow.",
        "Skin rash noticed. Kept clean. Antiseptic cream applied. Advised doctor visit if worse."
    )
    
    private val immunizationNotes = listOf(
        "BCG and Hepatitis B given at birth. Next visit at 6 weeks for DPT and Polio.",
        "DPT and Polio 1st dose given. Minor fever expected. Next dose after 4 weeks.",
        "Measles vaccine given at 9 months. Child cried but tolerated well.",
        "All vaccines up to date. Next booster at 16 months. Vitamin A given.",
        "Missed vaccine catch-up done. Mother counseled on importance of timely vaccination."
    )
    
    private val familyPlanningNotes = listOf(
        "Discussed spacing methods. Interested in Cu-T. Referred to PHC for insertion.",
        "Husband wife both attended. Condom use explained and samples given.",
        "Post delivery contraception discussed. Will decide after 6 weeks checkup.",
        "Injectable contraception given. Side effects explained. Next dose in 3 months.",
        "Permanent method discussed. Couple needs time to decide. Will follow up."
    )
    
    private val routineNotes = listOf(
        "All family members healthy. Kitchen garden doing well. Clean drinking water available.",
        "Discussed hygiene practices. Showed handwashing technique. Toilet in use.",
        "Elder child school going. Pregnant mother taking care. Family cooperative.",
        "Ration card and Aadhar all done. Health insurance enrolled. All records updated.",
        "Discussed seasonal diseases. Mosquito nets being used. House clean and ventilated."
    )
    
    fun generateSampleData() {
        events.record(EventLog.Category.HOUSEHOLDS, "Generating sample data", emptyMap())
        
        // Generate 15 diverse households
        val households = mutableListOf<Household>()
        
        // 5 households with pregnant women (ANC visits)
        repeat(5) { i ->
            val village = villages[i % villages.size]
            val headName = maleNames[i]
            val household = repo.addHousehold(headName, village, consentGiven = true)
            households.add(household)
            
            // Add wife (pregnant)
            val wife = femaleNames[i]
            val wifeMember = repo.addMember(household.id, wife, 24 + i, "Wife")
            
            // Add 1-2 children
            if (i % 2 == 0) {
                repo.addMember(household.id, childNames[i * 2], 3 + i, "Son")
            }
            if (i % 3 == 0) {
                repo.addMember(household.id, childNames[i * 2 + 1], 2, "Daughter")
            }
            
            // Add mother-in-law for some
            if (i % 2 == 0) {
                repo.addMember(household.id, femaleNames[i + 10], 55 + i, "Mother")
            }
            
            // Record ANC visits (2-4 visits in past 3 months)
            val visitCount = 2 + (i % 3)
            repeat(visitCount) { v ->
                val daysAgo = 20 + v * 30
                val visitDate = LocalDate.now().minusDays(daysAgo.toLong()).toString()
                wifeMember?.let {
                    repo.recordVisit(
                        householdId = household.id,
                        memberId = it.id,
                        memberName = wife,
                        type = VisitType.ANC,
                        notes = ancNotes[v % ancNotes.size],
                        date = visitDate,
                        highRisk = i % 4 == 0 && v == visitCount - 1, // Mark last visit as high risk for some
                        incentiveRupees = 300
                    )
                }
            }
        }
        
        // 4 households with newborn babies (PNC visits)
        repeat(4) { i ->
            val village = villages[(5 + i) % villages.size]
            val headName = maleNames[5 + i]
            val household = repo.addHousehold(headName, village, consentGiven = true)
            households.add(household)
            
            // Add wife (new mother)
            val wife = femaleNames[5 + i]
            val wifeMember = repo.addMember(household.id, wife, 22 + i, "Wife")
            
            // Add newborn
            val baby = childNames[10 + i]
            val babyMember = repo.addMember(household.id, baby, 0, if (i % 2 == 0) "Son" else "Daughter")
            
            // Add older child for some
            if (i % 2 == 0) {
                repo.addMember(household.id, childNames[14 + i], 4, if (i % 3 == 0) "Son" else "Daughter")
            }
            
            // Record PNC visits (3-5 visits in past 2 months)
            val visitCount = 3 + (i % 3)
            repeat(visitCount) { v ->
                val daysAgo = 7 + v * 12
                val visitDate = LocalDate.now().minusDays(daysAgo.toLong()).toString()
                wifeMember?.let {
                    repo.recordVisit(
                        householdId = household.id,
                        memberId = it.id,
                        memberName = wife,
                        type = VisitType.PNC,
                        notes = pncNotes[v % pncNotes.size],
                        date = visitDate,
                        highRisk = false,
                        incentiveRupees = 200
                    )
                }
            }
            
            // Record immunization for newborn
            if (i % 2 == 0) {
                babyMember?.let {
                    val visitDate = LocalDate.now().minusDays((5 + i * 3).toLong()).toString()
                    repo.recordVisit(
                        householdId = household.id,
                        memberId = it.id,
                        memberName = baby,
                        type = VisitType.IMMUNIZATION,
                        notes = immunizationNotes[0],
                        date = visitDate,
                        incentiveRupees = 150
                    )
                }
            }
        }
        
        // 3 households with young children (routine + illness visits)
        repeat(3) { i ->
            val village = villages[(9 + i) % villages.size]
            val headName = maleNames[9 + i]
            val household = repo.addHousehold(headName, village, consentGiven = true)
            households.add(household)
            
            // Add wife
            repo.addMember(household.id, femaleNames[9 + i], 28 + i, "Wife")
            
            // Add 2-3 children
            val child1 = repo.addMember(household.id, childNames[18 + i], 5 + i, "Son")
            val child2 = repo.addMember(household.id, childNames[21 + i], 3, "Daughter")
            if (i % 2 == 0) {
                repo.addMember(household.id, childNames[23], 1, "Son")
            }
            
            // Record routine visit
            val routineDate = LocalDate.now().minusDays((15 + i * 10).toLong()).toString()
            repo.recordVisit(
                householdId = household.id,
                memberId = null,
                memberName = household.headOfHousehold,
                type = VisitType.ROUTINE,
                notes = routineNotes[i % routineNotes.size],
                date = routineDate,
                incentiveRupees = 0
            )
            
            // Record child illness visit for one child
            child1?.let {
                val illnessDate = LocalDate.now().minusDays((8 + i * 5).toLong()).toString()
                repo.recordVisit(
                    householdId = household.id,
                    memberId = it.id,
                    memberName = it.name,
                    type = VisitType.CHILD_ILLNESS,
                    notes = childIllnessNotes[i % childIllnessNotes.size],
                    date = illnessDate,
                    highRisk = i == 2, // One high-risk case
                    incentiveRupees = 0
                )
            }
            
            // Record immunization for younger child
            child2?.let {
                val immuneDate = LocalDate.now().minusDays((25 + i * 8).toLong()).toString()
                repo.recordVisit(
                    householdId = household.id,
                    memberId = it.id,
                    memberName = it.name,
                    type = VisitType.IMMUNIZATION,
                    notes = immunizationNotes[(i + 2) % immunizationNotes.size],
                    date = immuneDate,
                    incentiveRupees = 150
                )
            }
        }
        
        // 2 households with family planning visits
        repeat(2) { i ->
            val village = villages[(12 + i) % villages.size]
            val headName = maleNames[12 + i]
            val household = repo.addHousehold(headName, village, consentGiven = true)
            households.add(household)
            
            // Add wife
            val wife = femaleNames[12 + i]
            val wifeMember = repo.addMember(household.id, wife, 30 + i, "Wife")
            
            // Add children
            repo.addMember(household.id, childNames[i], 8, "Son")
            repo.addMember(household.id, childNames[i + 1], 6, "Daughter")
            
            // Record family planning visit
            wifeMember?.let {
                val fpDate = LocalDate.now().minusDays((18 + i * 12).toLong()).toString()
                repo.recordVisit(
                    householdId = household.id,
                    memberId = it.id,
                    memberName = wife,
                    type = VisitType.FAMILY_PLANNING,
                    notes = familyPlanningNotes[i % familyPlanningNotes.size],
                    date = fpDate,
                    incentiveRupees = 150
                )
            }
        }
        
        // 1 household without consent (to show consent gate)
        val noConsentVillage = villages[0]
        val noConsentHead = maleNames[14]
        repo.addHousehold(noConsentHead, noConsentVillage, consentGiven = false)
        
        events.record(
            EventLog.Category.HOUSEHOLDS,
            "Sample data generated",
            mapOf(
                "households" to households.size.toString(),
                "villages" to villages.size.toString()
            )
        )
    }
    
    fun clearAllData() {
        // Note: HouseholdsRepository doesn't have a clear method
        // This would need to be added if needed
        events.record(EventLog.Category.HOUSEHOLDS, "Clear data requested", emptyMap())
    }
}