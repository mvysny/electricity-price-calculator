import fronius.gson
import java.io.File
import java.io.Serializable
import java.time.LocalDateTime
import java.time.ZonedDateTime

class Body : Serializable {
    var Data: Map<String, Inverter> = mutableMapOf()
}

class Inverter : Serializable {
    var Data: Data = Data()
    var DeviceType: Int = 0
    var End: ZonedDateTime? = null
    var NodeType: Int = 0
    var Start: ZonedDateTime? = null
}

class Data: Serializable {
    var EnergyReal_WAC_Sum_Produced: Produced = Produced()
}

class Produced : Serializable {
    var Unit: String = ""
    var Values: Map<String, Double> = mutableMapOf<String, Double>()
}

class Head : Serializable {
    var RequestArguments: RequestArguments = RequestArguments()
    var Status: Status = Status()
    var TimeStamp: ZonedDateTime? = null
}

class RequestArguments : Serializable {
    var Channel: List<String> = mutableListOf()
    var EndDate: ZonedDateTime? = null
    var HumanReadable: String = ""
    var Scope: String = ""
    var SeriesType: String = ""
    var StartDate: ZonedDateTime? = null
}

class Status: Serializable {
    var Code: Int = 0
    var Reason: String = ""
    var UserMessage: String = ""
}

class FroniusResponse: Serializable {
    var Head: Head = Head()
    var Body: Body = Body()

    companion object {
        fun fromJson(json: File): FroniusResponse = json.reader().use { r ->
            gson.fromJson(r.buffered(), FroniusResponse::class.java)
        }
    }
}

/**
 * Converts this map to a map of hour->Wh produced.
 */
fun Produced.toHourlyMap(start: LocalDateTime): Map<LocalDateTime, Double> {
    check(this.Unit == "Wh") { "${this.Unit}: expected Wh" }
    // Values: key is number of seconds since "start", value is a value in Wh. Group them by the hour
    val groupedByHours: Map<Int, Double> = Values.entries
        .groupBy { e -> e.key.toInt() / 3600 }
        .mapValues { e -> e.value.sumOf { it.value } }
    val hours: Map<LocalDateTime, Double> =
        groupedByHours.mapKeys { e -> start.plusHours(e.key.toLong()) }
    return hours
}

fun Inverter.toHourlyMap(): Map<LocalDateTime, Double> =
    Data.EnergyReal_WAC_Sum_Produced.toHourlyMap(Start!!.toLocalDateTime())
