package de.alog.parser

import akka.actor.ActorRef

object Messages {

  	case class RawMessage(logIdent: Map[String,String], msgs:Seq[String], onFinish:Option[(ActorRef, Any)]=None);

}