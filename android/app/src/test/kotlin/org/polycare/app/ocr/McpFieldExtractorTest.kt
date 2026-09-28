package org.polycare.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpFieldExtractorTest {

    @Test
    fun extractsEnglishMcpCardFields() {
        val latin = """
            GOVERNMENT OF INDIA
            MOTHER AND CHILD PROTECTION CARD
            Mother's Name: Priya Sharma
            Age: 23
            Village: Rampur
            BP: 120/80
            Hb: 11.2
            EDD: 15/11/2026
        """.trimIndent()

        val candidates = McpFieldExtractor.extract(latin, "")
        assertEquals("Priya Sharma", candidates.name)
        assertEquals(23, candidates.age)
        assertEquals("Rampur", candidates.village)
        assertEquals("MCP Card", candidates.docType)
        assertNotNull(candidates.clinicalNotes)
        assertTrue(candidates.clinicalNotes!!.contains("BP: 120/80"))
        assertTrue(candidates.clinicalNotes!!.contains("Hb: 11.2 g/dL"))
        assertTrue(candidates.clinicalNotes!!.contains("EDD: 15/11/2026"))
    }

    @Test
    fun extractsHindiDevanagariMcpCardFields() {
        val devanagari = """
            मातृ एवं शिशु संरक्षण कार्ड
            माता का नाम: सुनीता देवी
            उम्र: 26
            गांव: चांदपुर
        """.trimIndent()

        val candidates = McpFieldExtractor.extract("", devanagari)
        assertEquals("सुनीता देवी", candidates.name)
        assertEquals(26, candidates.age)
        assertEquals("चांदपुर", candidates.village)
        assertEquals("MCP Card", candidates.docType)
    }

    @Test
    fun extractsPrescriptionAndMedicineNotes() {
        val prescription = """
            PHC Primary Health Centre Rampur
            Rx Prescription
            Patient Name: Rekha Devi
            Age: 28
            Village: Kalyanpur
            Tab IFA 1 OD
            Tab Calcium 500mg
            ORS packets for child
        """.trimIndent()

        val candidates = McpFieldExtractor.extract(prescription, "")
        assertEquals("Rekha Devi", candidates.name)
        assertEquals(28, candidates.age)
        assertEquals("Kalyanpur", candidates.village)
        assertEquals("Prescription", candidates.docType)
        assertNotNull(candidates.clinicalNotes)
        assertTrue(candidates.clinicalNotes!!.contains("IFA Tablets"))
        assertTrue(candidates.clinicalNotes!!.contains("Calcium tablets"))
        assertTrue(candidates.clinicalNotes!!.contains("ORS packets"))
    }

    @Test
    fun handlesEmptyOrUnmatchedTextGracefully() {
        val candidates = McpFieldExtractor.extract("Random text without form headers", "")
        assertNull(candidates.name)
        assertNull(candidates.age)
        assertNull(candidates.village)
    }
}
