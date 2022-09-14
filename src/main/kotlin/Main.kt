import com.opencsv.CSVReader
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun main() {
    // read spot prices. Maps date+time to a price per kWh in cents
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

    println("Number of lines in spot prices: ${spotPrices.size}")
    println("Avg price: ${spotPrices.values.average()}")
}