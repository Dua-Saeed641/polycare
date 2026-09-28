package org.polycare.app.ocr

/**
 * Turns raw OCR text into candidate household-record and visit fields (M3: "OCR scan of MCP cards,
 * lab reports, prescriptions and medicine strips → confirmed fields in the household record").
 * Plain keyword/regex matching against common MCP (Mother and Child Protection) card labels,
 * prescriptions, lab reports, and medicine strips in English and Hindi.
 *
 * Every field it finds is presented to the ASHA worker as an editable, pre-filled suggestion in
 * [ScanScreen], never saved without her reviewing and confirming consent first.
 */
object McpFieldExtractor {
    data class Candidates(
        val name: String? = null,
        val age: Int? = null,
        val village: String? = null,
        val docType: String = "MCP Card",
        val clinicalNotes: String? = null,
    )

    private val namePatterns = listOf(
        Regex("""(?i)(?:mother(?:'s)?\s*name|beneficiary|name|patient\s*name)\s*[:\-]\s*(.+)"""),
        Regex("""(?:माता\s*का\s*नाम|लाभार्थी|नाम|मरीज\s*का\s*नाम)\s*[:\-]?\s*(.+)"""),
    )
    private val agePatterns = listOf(
        Regex("""(?i)(?:age|years|yrs)\s*[:\-]?\s*(\d{1,3})"""),
        Regex("""(?:आयु|उम्र)\s*[:\-]?\s*(\d{1,3})"""),
    )
    private val villagePatterns = listOf(
        Regex("""(?i)(?:village|area|address|residence)\s*[:\-]\s*(.+)"""),
        Regex("""(?:गांव|ग्राम|पता|निवास)\s*[:\-]?\s*(.+)"""),
    )

    private val bpPattern = Regex("""(?i)\b(?:bp|blood\s*pressure)\s*[:\-]?\s*(\d{2,3}\s*/\s*\d{2,3})\b""")
    private val hbPattern = Regex("""(?i)\b(?:hb|haemoglobin|hemoglobin)\s*[:\-]?\s*(\d{1,2}(?:\.\d)?)\b""")
    private val eddPattern = Regex("""(?i)\b(?:edd|due\s*date|प्रसव\s*तिथि)\s*[:\-]?\s*(\d{1,2}[\/\-\.]\d{1,2}[\/\-\.]\d{2,4})\b""")

    fun extract(latinText: String, devanagariText: String): Candidates {
        val combined = "$latinText\n$devanagariText"

        val docType = detectDocType(combined)
        val clinicalItems = mutableListOf<String>()

        bpPattern.find(combined)?.let { clinicalItems.add("BP: ${it.groupValues[1]}") }
        hbPattern.find(combined)?.let { clinicalItems.add("Hb: ${it.groupValues[1]} g/dL") }
        eddPattern.find(combined)?.let { clinicalItems.add("EDD: ${it.groupValues[1]}") }

        // Check for common medicines if it's a prescription or strip
        val lower = combined.lowercase()
        if (lower.contains("ifa") || lower.contains("iron")) clinicalItems.add("IFA Tablets")
        if (lower.contains("ors")) clinicalItems.add("ORS packets")
        if (lower.contains("zinc")) clinicalItems.add("Zinc syrup")
        if (lower.contains("paracetamol") || lower.contains("pcm")) clinicalItems.add("Paracetamol")
        if (lower.contains("calcium")) clinicalItems.add("Calcium tablets")

        val notes = if (clinicalItems.isNotEmpty()) clinicalItems.joinToString(", ") else null

        return Candidates(
            name = firstMatch(combined, namePatterns)?.let(::cleanField),
            age = firstMatch(combined, agePatterns)?.toIntOrNull()?.takeIf { it in 0..120 },
            village = firstMatch(combined, villagePatterns)?.let(::cleanField),
            docType = docType,
            clinicalNotes = notes,
        )
    }

    private fun detectDocType(text: String): String {
        val lower = text.lowercase()
        return when {
            lower.contains("mcp") || lower.contains("mother and child") || lower.contains("मातृ") || lower.contains("rch") -> "MCP Card"
            lower.contains("rx") || lower.contains("prescription") || lower.contains("opd") || lower.contains("phc") || lower.contains("chc") -> "Prescription"
            lower.contains("lab") || lower.contains("pathology") || lower.contains("blood test") || lower.contains("urine") -> "Lab Report"
            lower.contains("tab") || lower.contains("cap") || lower.contains("mg") || lower.contains("strip") || lower.contains("expiry") -> "Medicine Strip"
            else -> "Health Document"
        }
    }

    private fun firstMatch(text: String, patterns: List<Regex>): String? {
        for (line in text.lineSequence()) {
            for (pattern in patterns) {
                pattern.find(line)?.groupValues?.getOrNull(1)?.let { return it }
            }
        }
        return null
    }

    /** Strips trailing punctuation/whitespace OCR commonly leaves on a field value. */
    private fun cleanField(raw: String): String? =
        raw.trim().trim(':', '-', '.', ',', ';').trim().takeIf { it.isNotBlank() }
}
