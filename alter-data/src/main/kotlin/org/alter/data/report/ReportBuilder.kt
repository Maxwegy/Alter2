package org.alter.data.report

/** Collects report items by section while a tool runs; [build] sorts them so reports are stable. */
class ReportBuilder(private val tool: String) {
    private val sections = LinkedHashMap<String, Pair<Report.Severity, MutableSet<String>>>()
    private val summary = LinkedHashMap<String, Any>()

    fun add(section: String, item: String, severity: Report.Severity = Report.Severity.WARNING) {
        sections.getOrPut(section) { severity to sortedSetOf() }.second += item
    }

    fun summary(key: String, value: Any) {
        summary[key] = value
    }

    fun count(section: String): Int = sections[section]?.second?.size ?: 0

    fun build(): Report = Report(
        tool = tool,
        summary = summary,
        sections = sections.map { (title, entry) -> Report.Section(title, entry.second.toList(), entry.first) },
    )
}
