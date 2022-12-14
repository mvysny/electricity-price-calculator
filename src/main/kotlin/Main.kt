import com.opencsv.CSVReader
import java.io.File
import java.lang.RuntimeException
import java.time.LocalDateTime
import java.time.Month
import java.time.format.DateTimeFormatter
import java.util.function.Function

fun main() {
    // spot prices. Maps date+time to a price per kWh in cents.
    // Downloaded from https://sahko.tk/ . The CSV has two columns.
    // First column is the date+time in hourly granularity,
    // second column is the price of electricity in euro-cents.
    val spotPrices = mutableMapOf<LocalDateTime, Double>()
    File("/home/mavi/Downloads/spot_prices.csv").bufferedReader().use {
        val csvReader = CSVReader(it)
        csvReader.readNext() // skip header
        val datetimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val lines = generateSequence { csvReader.readNext() }
        lines.forEach { line ->
            val dateTime = LocalDateTime.parse(line[0], datetimeFormatter)
            spotPrices[dateTime] = line[1].toDouble()
        }
    }

    println("Avg spot price: ${spotPrices.values.average()}")

    // my consumption for 2022. Maps date+time to used kWh.
    val consumption = mutableMapOf<LocalDateTime, Double>()
    // Helen produces a two-column CSV. First column is the date+time in hourly granularity,
    // second column is the consumption in kWh
    File("/home/mavi/Downloads/helen.csv").bufferedReader().use {
        val csvReader = CSVReader(it)
        csvReader.readNext() // skip header
        val datetimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss")
        val lines = generateSequence { csvReader.readNext() }
        lines.forEach { line ->
            if (line[1].isNotBlank()) { // for future dates the consumption will be missing. just skip them.
                val dateTime = LocalDateTime.parse(line[0], datetimeFormatter)
                consumption[dateTime] = line[1].toDouble()
            }
        }
    }

    val solarPanelGenerationPerHour: SolarProductionCalculator = FroniusData() // or DummySolarProduction or NoSolarProduction
    solarPanelGenerationPerHour.dumpMonthlyStats()
    // adjust with a generation data.
    for (key in consumption.keys.toList()) {
        val solarPanelGeneration = solarPanelGenerationPerHour.apply(key)
        consumption[key] = consumption[key]!! - solarPanelGeneration
    }

    // print stats
    fun statsSince(since: LocalDateTime) {
        fun getSpotPriceAt(dateTime: LocalDateTime): Double =
            spotPrices[dateTime] ?: spotPrices.getOrElse(dateTime.toLocalDate().atStartOfDay()) { throw RuntimeException("No spot price for $dateTime") }

        val filteredConsumption = consumption.filterKeys { it >= since }
        println("== Range ${filteredConsumption.keys.min()} .. ${filteredConsumption.keys.max()} ==========================================================")
        println("Total consumption kWh: ${filteredConsumption.values.sum()}")
        println("Min consumption kWh: ${filteredConsumption.entries.minByOrNull { it.value }}")
        println("Max consumption kWh: ${filteredConsumption.entries.maxByOrNull { it.value }}")
        println("Avg hourly consumption kWh: ${filteredConsumption.values.average()}")
        println()
        println("Electricity price at flat 5.18c/kWh: ${filteredConsumption.values.sum() * 0.0518} EUR")
        println("Electricity price at flat 20c/kWh: ${filteredConsumption.values.sum() * 0.2} EUR")
        val totalPriceAtSpot =
            filteredConsumption.entries.sumOf { it.value * getSpotPriceAt(it.key) * 0.01 }
        println("Electricity price at spot prices: $totalPriceAtSpot EUR")
        println("Spot price = flat price at ${totalPriceAtSpot / filteredConsumption.values.sum() * 100}c/kWh")
    }

    statsSince(LocalDateTime.of(2022, 1, 1, 0, 0, 0))
    val i_started_to_charge_my_car_at_1am = LocalDateTime.of(2022, 8, 29, 0, 0, 0)
    statsSince(i_started_to_charge_my_car_at_1am)
}

/**
 * Given a date+time in hourly granularity (year=2022), returns the number of KWh produced in that hour.
 */
typealias SolarProductionCalculator = Function<LocalDateTime, Double>

object NoSolarProduction : SolarProductionCalculator {
    override fun apply(t: LocalDateTime): Double = 0.0
}

object DummySolarProduction : SolarProductionCalculator {
    private val solarPanelGenerationPerHour = listOf<Float>(0f, 0f, 0f, 0f, 0f, 0f,
        0.1f, 0.3f, 0.6f, 0.75f, 0.8f, 0.8f,
        0.85f, 0.95f, 0.8f, 0.75f, 0.5f, 0.3f,
        0.1f, 0f, 0f, 0f, 0f, 0f
    )
    private val maxProductionKwhPerHour = 2.0
    override fun apply(t: LocalDateTime): Double = solarPanelGenerationPerHour[t.hour] * maxProductionKwhPerHour
}

class FroniusData : SolarProductionCalculator {
    // get real production data: 2022-06-01..2022-06-30
    private val froniusData1: FroniusResponse = FroniusResponse.fromJson(File("/home/mavi/Downloads/GetArchiveData 1-15.6.2022.js"))
    private val froniusData2: FroniusResponse = FroniusResponse.fromJson(File("/home/mavi/Downloads/GetArchiveData 16-30.6.2022.js"))

    // maps hour to Wh
    private val hourlyMap: Map<LocalDateTime, Double> = froniusData1.Body.Data["inverter/1"]!!.toHourlyMap() +
            froniusData2.Body.Data["inverter/1"]!!.toHourlyMap()

    override fun apply(t: LocalDateTime): Double {
        require(t.year == 2022) { "${t.year}" }
        if (t.month !in Month.MARCH..Month.OCTOBER) return 0.0
        var adjustedDay = t
        if (adjustedDay.dayOfMonth == 31) {
            adjustedDay = adjustedDay.withDayOfMonth(30)
        }
        adjustedDay = adjustedDay.withMonth(6)
        return (hourlyMap[adjustedDay] ?: 0.0) / 1000.0
    }
}

fun SolarProductionCalculator.dumpMonthlyStats() {
    val production = (1..12).associateWith { month ->
        var start = LocalDateTime.of(2022, month, 1, 0, 0, 0)
        val end = start.plusMonths(1L)
        var productionkWh = 0.0
        while (start < end) {
            productionkWh += apply(start)
            start = start.plusHours(1L)
        }
        productionkWh
    }
    println("MONTHLY PRODUCTION STATS:")
    production.map { "Month ${it.key}: ${it.value}kWh" } .forEach { println(it) }
    println("TOTAL: ${production.values.sum()}kWh\n")
}
