package de.alog
package parser

import de.alog.{util=>u}
import akka.actor._
import scala.collection.mutable.StringBuilder
import Messages._
import persist.Messages._
import scala.collection.immutable.StringLike
import de.alog.util.Helpers
import org.joda.time._
import scala.concurrent.Future
import scala.concurrent.duration._

class LogParser (messageReceiver:ActorRef) extends Actor with ActorLogging with Helpers {

  // Anwendungslog - captgroups: \1 - Anwendung; \2 - Loglevel; \3 - Zeitstempel; \4 - Lokation; \5 - Message
  val AnwLog = """\[(.*)\]\[([A-Z]*)\s*\] (\S*) (\S*) - (.*)""".r
  val AnwLogWithId = """\[(.*)\]\[([A-Z]*)\s*\] (\S*) (\S*) - \[(\w{8})\] (.*)""".r
  val AppServLog = """\[([^\]]*)\]\s+(\S+)\s+(\S+)\s+(\w)\s+(.*)""".r
  val Timestamp = """(\d\d)\.(\d\d)\.(\d{4})/(\d\d):(\d\d):(\d\d),(\d*)""".r
  
  implicit val MaxLineLen = 1 << 14
  val LogEntryMaxAge = 60 days
  
	def receive = {
	  case RawMessage(ident, msgs, cb) => {
	    implicit val ec = context.system.dispatcher
	    Future {
  	    val result = msgs.map(_ match {
  	      case AnwLogWithId(anw,level,u.AnwDateTime(dt),loc,uid,msg) =>
  	        PrimaryLine(dt.withZone(logTimezone), Map("anw" -> anw, "level" -> level, "loc" -> loc, "uid" -> uid), new StringBuilder(msg))
  	      case AnwLog(anw,level,u.AnwDateTime(dt),loc,msg) =>
  	        PrimaryLine(dt.withZone(logTimezone), Map("anw" -> anw, "level" -> level, "loc" -> loc), new StringBuilder(msg))
  	      case AppServLog(u.AppServDateTime(dt), msgcode, loc, u.LogLevel(level), msg) =>
  	        PrimaryLine(dt, Map("loc" -> loc, "level" -> level, "msgcode" -> msgcode), new StringBuilder(msg))
  	      case m => AdditionalLine(m)
  	    })
  	    .aggregate(collection.mutable.Buffer[PrimaryLine]())(
  	      (a,b) => b match {
  				  case b:PrimaryLine => a += b
  				  case b:AdditionalLine if(a.nonEmpty && a.last.msg.length < MaxLineLen) => 
  				    { a.last.msg.append('\n'); a.last.msg.append(b.msg); a }
  				  case _ => a
  	      },
  	      (a,b) => a ++ b
  	    )
  	    .filter(le => new org.joda.time.Duration(le.timestamp, new DateTime).getStandardDays <= LogEntryMaxAge.toDays)
  	    .map(le => LogEntry(
  	        timestamp = le.timestamp,
  	        le.features ++ ident,
  	        msg = le.msg.toString.take(MaxLineLen))
  	    )
  	    messageReceiver ! LogEntries(result, cb)
  	  }
	  }

	}

  case class SendToWrp[T](value:T) {
  	def sendTo(tgt:ActorRef) {
  		tgt.tell(value, self)
  	}
  }
}

sealed trait LogLine
case class AdditionalLine(msg:String) extends LogLine
case class PrimaryLine(
		timestamp:DateTime,
		features:Map[String,String],
    msg:StringBuilder) extends LogLine
