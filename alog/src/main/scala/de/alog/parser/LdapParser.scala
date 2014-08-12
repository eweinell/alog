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
import org.joda.time.{DateTime=>JDateTime}
import format._
import scala.util.Try
import org.joda.time.Duration

class LdapParser (messageReceiver:ActorRef) extends Actor with ActorLogging with Helpers {

  // \1 timestamp, \2 op \3 bindDN, \4 sourceIP \5 sourcePort \6 connId \7 begin \8 status
  val LdapLog = """AuditV3--(.*)--V3 (.*)--bindDN: (.*)--client: (.*):(.*)--connectionID: (.*)--received: (.*)--(.*)""".r
  
  
	def receive = {
	  case RawMessage(ident, msgs, cb) => {
	    implicit val ec = context.system.dispatcher
	    Future {
  	    val result = msgs.map(_ match {
  	      case m@ LdapLog(LdapDateTime(finishDate),op,bindDN,sourceIP,sourcePort,connId,startTS@LdapDateTime(startTimestamp),status) =>
  	        PrimaryLine(finishDate.withZone(logTimezone), 
  	          Map("op"->op, 
  	              "bindDN"->bindDN, 
  	              "sourceIP"->sourceIP, "sourcePort"->sourcePort,
  	              "connId"->connId,
  	              "startTimestamp"->startTS,
  	              "status"->status,
  	              "duratuion"->new Duration(startTimestamp,finishDate).getMillis.toString,
  	              "durationRange"->range(new Duration(startTimestamp,finishDate).getMillis)), 
  	          new StringBuilder(m))
  	      case m => AdditionalLine(m)
  	    })
  	    .aggregate(collection.mutable.Buffer[PrimaryLine]())(
  	      (a,b) => b match {
  				  case b:PrimaryLine => a += b
  				  case b:AdditionalLine if(a.nonEmpty) => 
  				    { a.last.msg.append('\n'); a.last.msg.append(b.msg); a }
  				  case _ => a
  	      },
  	      (a,b) => a ++ b
  	    )
  	    .map(le => LogEntry(
  	        timestamp = le.timestamp,
  	        le.features ++ ident,
  	        msg = le.msg.toString)
  	    )
  	    messageReceiver ! LogEntries(result, cb)
  	  }
	  }
	}
  
  def range(millis:Long) = millis match {
    case a if a < 10 => "< 10"
    case a if a < 20 => "10...20"
    case a if a < 100 => "20...100"
    case a if a < 1000 => "100...1.000"
    case a if a < 10000 => "1.000...10.000"
    case _ => ">= 10.000"
  }
    
  
}

object LdapDateTime {
	lazy val dateTimeFmt =
	  DateTimeFormat.forPattern("yyyy-MM-dd-HH:mm:ss.SSS")

  def unapply(s:String): Option[JDateTime] =
    Try(dateTimeFmt.parseDateTime(s.replaceAll("[+].*$", ""))).toOption
}

