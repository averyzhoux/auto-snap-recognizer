package dev.averyzhoux.recognizer

/**
 * 本地数据集的入口。
 *
 * ⚠️ 现在这里是**示例数据**，等你的真实表格给我之后替换 [entries] 即可，
 * 比对逻辑（[OcrMatcher]）和 UI 都不用动。
 *
 * 如果表格条目很多或者要热更新，可以改成从 assets 里的 CSV / JSON 读取，
 * 只要 [entries] 这个对外接口不变就行。
 */
object Dataset {

    val entries: List<Entry> = listOf(
        Entry("Serial Number", aliases = listOf("Serial No", "S/N", "SN")),
        Entry("Model", aliases = listOf("Model No", "Model Number")),
        Entry("Manufacturer", aliases = listOf("Mfg", "Maker")),
        Entry("Production Date", aliases = listOf("Mfg Date", "Date of Manufacture")),
        Entry("Expiry Date", aliases = listOf("Expiration Date", "Exp Date")),
        Entry("Batch Number", aliases = listOf("Batch No", "Lot Number", "Lot No")),
        Entry("Inspector", aliases = listOf("QC", "Checked By")),
        Entry("Certificate", aliases = listOf("Cert No", "Certificate No")),
        Entry("Rated Voltage", aliases = listOf("Voltage", "Volt")),
        Entry("Rated Power", aliases = listOf("Power", "Wattage")),
        Entry("Rated Current", aliases = listOf("Current", "Ampere")),
        Entry("Protection Class", aliases = listOf("IP Rating", "IP Class")),
        Entry("Net Weight", aliases = listOf("Weight", "N.W.")),
        Entry("Gross Weight", aliases = listOf("G.W.")),
        Entry("Dimensions", aliases = listOf("Size", "Dimension")),
        Entry("Country of Origin", aliases = listOf("Made In", "Origin")),
        Entry("Warning", aliases = listOf("Caution", "Alert")),
        Entry("Instructions", aliases = listOf("User Manual", "Manual")),
        Entry("Storage Conditions", aliases = listOf("Storage", "Storage Temp")),
        Entry("Warranty", aliases = listOf("Warranty Period", "Guarantee"))
    )
}
