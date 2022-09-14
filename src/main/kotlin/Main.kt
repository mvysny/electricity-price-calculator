import com.opencsv.CSVReader
import java.io.File
import java.lang.RuntimeException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun main() {
    // spot prices. Maps date+time to a price per kWh in cents
    val spotPrices = mutableMapOf<LocalDateTime, Float>()
    File("/home/mavi/Downloads/spot_prices.csv").bufferedReader().use {
        val csvReader = CSVReader(it)
        csvReader.readNext() // skip header
        val datetimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val lines = generateSequence { csvReader.readNext() }
        lines.forEach { line ->
            val dateTime = LocalDateTime.parse(line[0], datetimeFormatter)
            spotPrices[dateTime] = line[1].toFloat()
        }
    }
    fun getSpotPriceAt(dateTime: LocalDateTime): Float =
        spotPrices[dateTime] ?: spotPrices.getOrElse(dateTime.toLocalDate().atStartOfDay()) { throw RuntimeException("No spot price for $dateTime") }

    println("Avg spot price: ${spotPrices.values.average()}")

    // my consumption for 2022. Maps date+time to used kWh
    val consumption = mutableMapOf<LocalDateTime, Float>()
    File("/home/mavi/Downloads/helen.csv").bufferedReader().use {
        val csvReader = CSVReader(it)
        csvReader.readNext() // skip header
        val datetimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss")
        val lines = generateSequence { csvReader.readNext() }
        lines.forEach { line ->
            if (line[1].isNotBlank()) {
                val dateTime = LocalDateTime.parse(line[0], datetimeFormatter)
                consumption[dateTime] = line[1].toFloat()
            }
        }
    }

    // print stats
    fun statsSince(since: LocalDateTime) {
        val filteredConsumption = consumption.filterKeys { it >= since }
        println("== Since $since ==========================================================")
        println("Total consumption kWh: ${filteredConsumption.values.sum()}")
        println("Avg hourly consumption kWh: ${filteredConsumption.values.average()}")

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
