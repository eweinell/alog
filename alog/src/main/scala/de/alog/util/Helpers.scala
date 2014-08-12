package de.alog.util

import java.security.MessageDigest
import scala.util.Try
import scala.concurrent.duration._
import org.joda.time.{DateTime=>JDateTime}
import org.joda.time.DateTimeZone
import org.joda.time.Period
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.PeriodFormatterBuilder
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import org.joda.time.format.DateTimeFormatterBuilder

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
	    new JDateTime(year.toInt,month.toInt,day.toInt,hour.toInt,minute.toInt,second.toInt,millis.toInt,logTimezone)
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

	val standardDurations = 
	  List(1 second, 5 seconds, 15 seconds, 30 seconds, 1 minute, 5 minutes, 15 minutes, 30 minutes, 1 hour, 2 hours, 4 hours, 8 hours, 1 day, 2 days, 7 days, 30 days)
	
	def nextStandardDuration(millis:Long) =
	  standardDurations.find(_.toMillis > millis).getOrElse(millis millis)
	
	implicit def durToPer(d:Duration): Period =
	  new Period(d.toMillis)
	  
  val logTimezone = DateTimeZone.forID("Europe/Berlin")


}

object Helpers extends Helpers

object OptDateTime {
  def unapply(s:Option[String]): Option[JDateTime] =
    s.flatMap(Helpers.parseOptDateTime _)
}

object AnwDateTime {
	lazy val dateTimeFmt =
	  DateTimeFormat.forPattern("dd.MM.yyyy'/'HH:mm:ss,SSS")

  def unapply(s:String): Option[JDateTime] =
    Try(dateTimeFmt.parseDateTime(s)).toOption
}

object AppServDateTime {
  import collection.JavaConversions._
  lazy val dateTimeFmt = 
    new DateTimeFormatterBuilder()
            .appendMonthOfYear(1)
            .appendLiteral('/')
            .appendDayOfMonth(1)
            .appendLiteral('/')
            .appendTwoDigitYear(1970)
            .appendLiteral(' ')
            .appendHourOfDay(1)
            .appendLiteral(':')
            .appendMinuteOfHour(2)
            .appendLiteral(':')
            .appendSecondOfMinute(2)
            .appendLiteral(':')
            .appendMillisOfSecond(3)
            .appendLiteral(' ')
            .appendTimeZoneShortName(Map("CEST" -> DateTimeZone.forID("Europe/Berlin"), "CET" -> DateTimeZone.forID("Europe/Berlin")))
            .toFormatter();
//	  DateTimeFormat.forPattern("M/D/yy H:mm:ss:SSS ZZZ")

  def unapply(s:String): Option[JDateTime] =
    Try(dateTimeFmt.parseDateTime(s)).toOption
}

object Duration {
  def unapply(s:String): Option[Period] = unapply(Some(s))
  def unapply(s:Option[String]): Option[Period] =
    s.filter(_.trim.nonEmpty).flatMap(x => Try(Helpers.parseDuration(x)).toOption)
}

object LogLevel {
  def unapply(s:String): Option[String] = Some(s) map {
    case "E" => "ERROR"
    case "W" => "WARN"
    case "I" => "INFO"
    case "A" => "AUDIT"
    case r => r
  } 
    
}
object JMXHelpers {
  implicit def str2qn(name:String):ObjectName = new ObjectName(name)
  def registerJmx(o:Object, on:ObjectName) = ManagementFactory.getPlatformMBeanServer.registerMBean(o, on)
}
