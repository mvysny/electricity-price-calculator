import com.opencsv.CSVReader
import java.io.File
import java.lang.RuntimeException
import java.time.LocalDateTime
import java.time.Month
import java.time.format.DateTimeFormatter
import java.util.function.Function
import kotlin.math.roundToInt

fun main() {
    // spot prices. Maps date+time to a price per kWh in cents.
    // Downloaded from https://sahko.tk/ - click on the upper-right hamburger menu icon,
    // then "download CSV". The CSV has two columns.
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
        val datetimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
        val lines = generateSequence { csvReader.readNext() }
        lines.forEach { line ->
            if (line[1].isNotBlank()) { // for future dates the consumption will be missing. just skip them.
                val dateTime = LocalDateTime.parse(line[0], datetimeFormatter)
                consumption[dateTime] = line[1].toDouble()
            }
        }
    }

    val solarPanelGenerationPerHour: SolarProductionCalculator = NoSolarProduction // or DummySolarProduction or NoSolarProduction
    solarPanelGenerationPerHour.dumpMonthlyStats()
    // adjust with a generation data.
    for (key in consumption.keys.toList()) {
        val solarPanelGeneration = solarPanelGenerationPerHour.apply(key)
        consumption[key] = consumption[key]!! - solarPanelGeneration
    }

    // print stats
    fun statsSince(since: LocalDateTime) {
        /**
         * Returns spot price at [dateTime], in euro-cents.
         */
        fun getSpotPriceAt(dateTime: LocalDateTime): Double =
            spotPrices[dateTime] ?: spotPrices.getOrElse(dateTime.toLocalDate().atStartOfDay()) { throw RuntimeException("No spot price for $dateTime") }

        val filteredConsumption = consumption.filterKeys { it >= since }
        println("== Range ${filteredConsumption.keys.min()} .. ${filteredConsumption.keys.max()} ==========================================================")
        println("Total consumption kWh: ${filteredConsumption.values.sum()}")
        println("Min consumption kWh: ${filteredConsumption.entries.minByOrNull { it.value }}")
        println("Max consumption kWh: ${filteredConsumption.entries.maxByOrNull { it.value }}")
        println("Avg hourly consumption kWh: ${filteredConsumption.values.average()}")
        println()
        val currentPriceEur = 0.0518
        println("Electricity price at flat ${currentPriceEur * 100}c/kWh: ${filteredConsumption.values.sum() * currentPriceEur} EUR")
        println("Electricity price at flat 20c/kWh: ${filteredConsumption.values.sum() * 0.2} EUR")
        val totalPriceAtSpot =
            filteredConsumption.entries.sumOf { it.value * getSpotPriceAt(it.key) * 0.01 }
        println("Electricity price at spot prices: $totalPriceAtSpot EUR")
        println("Spot price = flat price at ${totalPriceAtSpot / filteredConsumption.values.sum() * 100}c/kWh")
        println("== Spot prices: Average cost by hour ================================================================")
        (0..23).forEach { hour ->
            val spotPricesAtHour = filteredConsumption.filterKeys { it.hour == hour }.keys.map { getSpotPriceAt(it) }
            val roundedSpotPricesAtHour = spotPricesAtHour.map { it.roundToInt().toDouble() }
            println("$hour: ${spotPricesAtHour.statistics()} ;   ${roundedSpotPricesAtHour.statistics()}")
        }
        println("=======================================================================================")
        println("Days with non-zero consumption where spot price was higher than ${currentPriceEur * 100}c")
        val costlyConsumption = filteredConsumption.filter { (dateTime, c) -> getSpotPriceAt(dateTime) > currentPriceEur * 100 && c > 0.0 }
        println(costlyConsumption.map { "${it.key}: ${it.value}" } .joinToString("\n"))
    }

    statsSince(LocalDateTime.of(2023, 1, 1, 0, 0, 0))
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
        var start = LocalDateTime.of(2023, month, 1, 0, 0, 0)
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

fun Iterable<Double>.statistics(): String =
        "min: ${min()}, max: ${max()}, avg: ${average()}, median: ${median()}"
fun Iterable<Double>.median(): Double? = sorted().middleItem()
fun <T> List<T>.middleItem(): T? = get(indices.first + (indices.last - indices.first) / 2)
