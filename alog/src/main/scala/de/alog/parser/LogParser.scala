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
import scala.concurrent.duration._

class LogParser (messageReceiver:ActorRef) extends Actor with ActorLogging with Helpers {

  // Anwendungslog - captgroups: \1 - Anwendung; \2 - Loglevel; \3 - Zeitstempel; \4 - Lokation; \5 - Message
  val AnwLog = """\[(.*)\]\[([A-Z]*)\s*\] (\S*) (\S*) - (.*)""".r
  val AnwLogWithId = """\[(.*)\]\[([A-Z]*)\s*\] (\S*) (\S*) - \[(\w{8})\] (.*)""".r
  val Timestamp = """(\d\d)\.(\d\d)\.(\d{4})/(\d\d):(\d\d):(\d\d),(\d*)""".r
  
  implicit val MaxLineLen = 1 << 14
  val LogEntryMaxAge = 60 days
  
	def receive = {
	  case RawMessage(ident, msgs, cb) => {
	    val result = msgs.map(_ match {
	      case AnwLogWithId(anw,level,u.DateTime(dt),loc,uid,msg) =>
	        AnwLogEntry(dt.withZone(logTimezone), anw,level,loc,new StringBuilder(msg),Some(uid))
	      case AnwLog(anw,level,u.DateTime(dt),loc,msg) =>
	        AnwLogEntry(dt.withZone(logTimezone), anw,level,loc,new StringBuilder(msg))
	      case m => SubEntry(m)
	    })
	    .aggregate(collection.mutable.Buffer[AnwLogEntry]())(
	      (a,b) => b match {
				  case b:AnwLogEntry => a += b
				  case b:SubEntry if(a.nonEmpty && a.last.msg.length < MaxLineLen) => 
				    { a.last.msg += '\n'; a.last.msg ++ b.msg; a }
				  case _ => a      
	      },
	      (a,b) => a ++ b
	    )
	    .filter(le => new org.joda.time.Duration(le.timestamp, new DateTime).getStandardDays <= LogEntryMaxAge.toDays)
	    .map(le => LogEntry(
	        timestamp = le.timestamp,
	        Map("anw" -> le.anw, "level" -> le.level, "loc" -> le.loc) ++ ident ++ (le.errUid match {case Some(uid) => Map("uid" -> uid); case _ => Map()}),
	        msg = le.msg.toString.take(MaxLineLen))
	    )
	    messageReceiver ! LogEntries(result, cb)
	  }

	}

  case class SendToWrp[T](value:T) {
  	def sendTo(tgt:ActorRef) {
  		tgt.tell(value, self)
  	}
  }
}

sealed trait LogEntry
case class SubEntry(msg:String) extends LogEntry
case class AnwLogEntry(
		timestamp:DateTime,
    anw:String,
    level:String,
    loc:String,
    msg:StringBuilder,
    errUid:Option[String]=None) extends LogEntry

    