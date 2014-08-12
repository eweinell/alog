package de.alog.util

import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import org.junit.runner.RunWith
import org.joda.time.Period

import de.alog.util.Helpers;
import de.alog.util.OptDateTime;

@RunWith(classOf[JUnitRunner])
class HelpersTest extends Specification {

  "Date Helpers" should {
    "parse plain date" in {
      Some("02.03.2014") match {
        case OptDateTime(v) => {
          v.getDayOfMonth() must_== 2
          v.getMonthOfYear() must_== 3
          v.getYear() must_== 2014
          v.getHourOfDay() must_== 0
          v.getMinuteOfHour() must_== 0         
          v.getSecondOfMinute() must_== 0
          v.getMillisOfSecond() must_== 0
        }
      }
    }
    "parse date with time" in {
      Some("02.03.2014/01:02:03,111") match {
        case OptDateTime(v) => {
          v.getDayOfMonth() must_== 2
          v.getMonthOfYear() must_== 3
          v.getYear() must_== 2014
          v.getHourOfDay() must_== 1
          v.getMinuteOfHour() must_== 2          
          v.getSecondOfMinute() must_== 3
          v.getMillisOfSecond() must_== 111
        }
      }
    }
  }
  
  "Duration Helpers" should {
    "parse duration day" in {
      Helpers.parseDuration("1d") must_== new Period().plusDays(1)
    }
    "parse duration hour/minute" in {
      Helpers.parseDuration("1h30m") must_== new Period().plusHours(1).plusMinutes(30)
    }
  }
}