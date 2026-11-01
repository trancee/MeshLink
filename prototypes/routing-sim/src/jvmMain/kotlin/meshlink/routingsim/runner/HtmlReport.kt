package meshlink.routingsim.runner

import java.io.File

object HtmlReport {
    fun render(
        rows: List<AggregateRow>,
        timeseries: File?,
        notes: File?,
    ): String = "<html><body>${rows.size} rows</body></html>"
}
