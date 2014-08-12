package de.alog.persist

import org.joda.time.DateTime
import akka.actor.ActorRef

object Messages {
  
  case class LogEntries(entries:Seq[LogEntry], onFinish:Option[(ActorRef, Any)]=None)
  
	case class LogEntry(
	  timestamp:DateTime,
	  features:Map[String,String],
    msg:String)
}