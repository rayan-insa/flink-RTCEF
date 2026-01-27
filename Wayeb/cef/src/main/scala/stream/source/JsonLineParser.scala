package stream.source

import play.api.libs.json.{JsObject, Json}
import stream.GenericEvent


object JsonLineParser extends LineParser {

  override def line2Event(
                           line: String,
                           id: Int
                         ): GenericEvent = {
    val jsValue = Json.parse(line).as[JsObject]
    val map = jsValue.value.toMap.mapValues {
      case s: play.api.libs.json.JsString => s.value
      case n: play.api.libs.json.JsNumber => if (n.value.isValidLong) n.value.toLong else n.value.toDouble
      case b: play.api.libs.json.JsBoolean => b.value
      case other => other.toString
    }.toMap

    // 1. Extract Timestamp
    val timestamp = map.get("timestamp").map {
      case l: Long => l
      case i: Int => i.toLong
      case s: String => s.toLong // simplified
      case d: Double => d.toLong
      case _ => id.toLong
    }.getOrElse(id.toLong)

    // 2. Extract Event Type
    val eventType = map.get("type").map(_.toString).getOrElse("GenericJson")

    // 3. Extract ID (Smart Strategy)
    // We look for common ID keys. If value is Int, use it. If String, hash it.
    // Fallback to the loop 'id' provided by Source.
    val keysToCheck = List("id", "mmsi", "pan", "trx_no")
    
    val extractedId: Int = keysToCheck.collectFirst {
      case key if map.contains(key) => map(key)
    }.map {
      case i: Int => i
      case l: Long => l.toInt // potential overflow but GenericEvent uses Int
      case d: Double => d.toInt
      case s: String => s.hashCode
      case other => other.toString.hashCode
    }.getOrElse(id)

    GenericEvent(extractedId, eventType, timestamp, map)
  }

  override def line2Event(
                           line: Seq[String],
                           id: Int
                         ): GenericEvent = throw new UnsupportedOperationException("Json domain does not have columns for each line")

}
