package de.alog.util

import java.security.MessageDigest

import scala.util.Try

import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.Period
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.PeriodFormatterBuilder

trait Helpers {
  	
	implicit def digester = MessageDigest.getInstance("MD5")
	
	def hash(b:Array[Byte]*)(implicit d:MessageDigest): Array[Byte] = {
		b.foreach(d.update(_))
		d.digest()
	}
	
	def hash(s:String)(implicit d:MessageDigest):String = 
	  d.digest(s.getBytes).map("%02X".format(_)).mkString
	
  implicit def str2Hash(s:String) = 
    StringHash(s)
    
  case class StringHash(s:String) {
	   def hash = Helpers.hash(s)
	}
	
	val OptTimestamp = """(((\d\d)\.)?(\d\d)\.)?(\d{4})(/(\d\d)(:(\d\d)(:(\d\d)(,(\d*))?)?)?)?""".r
	def parseOptDateTime(s:String ) =
	  OptTimestamp.findFirstMatchIn(s).map{ m=>
	    val day = "1" ?: m.group(3)
	    val month = "1" ?: m.group(4)
	    val year = m.group(5)
	    val hour = "0" ?: m.group(7)
	    val minute = "0" ?: m.group(9)
	    val second = "0" ?: m.group(11)
	    val millis = "0" ?: m.group(13)
	    new DateTime(year.toInt,month.toInt,day.toInt,hour.toInt,minute.toInt,second.toInt,millis.toInt,logTimezone)
	  }
	
	def parseDuration(s:String) = 
	  new PeriodFormatterBuilder()
	    .appendDays().appendSuffix("d")
	    .appendHours().appendSuffix("h")
	    .appendMinutes().appendSuffix("m")
	    .appendSeconds().appendSuffix("s")
	    .toFormatter().parsePeriod(s)

	implicit private def strToOpt(s:String): OptStr = OptStr(s)
	case class OptStr(s:String) {
	  def ?:(deflt:String) = Option(s).getOrElse(deflt)
	}
	
  val logTimezone = DateTimeZone.forID("Europe/Berlin")

}

object Helpers extends Helpers

object OptDateTime {
  def unapply(s:Option[String]): Option[DateTime] =
    s.flatMap(Helpers.parseOptDateTime _)
}

object DateTime {
	lazy val dateTimeFmt =
	  DateTimeFormat.forPattern("dd.MM.yyyy'/'HH:mm:ss,SSS")

  def unapply(s:String): Option[DateTime] =
    Try(dateTimeFmt.parseDateTime(s)).toOption
        
}

object Duration {
  def unapply(s:String): Option[Period] = unapply(Some(s))
  def unapply(s:Option[String]): Option[Period] =
    s.flatMap(x => Try(Helpers.parseDuration(x)).toOption)
}
