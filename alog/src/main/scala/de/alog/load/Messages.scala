package de.alog.load

import scala.concurrent.duration.Deadline

object Messages {
  
  case class ReadState(readMark:Option[Array[Byte]], failed:Boolean, state:String, occurences:Int=1, history:List[Long]=Nil) 
  
	case class LogRequest(file:String, labels:Map[String,String], scheduled:Deadline, recentState:Option[ReadState]) {
    def update(f:Option[ReadState]=>ReadState) = LogRequest(file=this.file, labels=this.labels, scheduled=this.scheduled, Some(f(this.recentState)))
  }
	
  case object WorkAvailable
  case object GetWork
  case class Completed(req: LogRequest)
  case class Rejected(req: LogRequest)
  	  	
  case class RegisterLogfile(file:String, labels:(String,String)*)
  case class RestoreLogfileState(requests:Seq[LogRequest])
  case class UnregisterLogfile(file:String)
  case object GetRegistered
  case class RegisteredLogfiles(entries:Seq[LogRequest])
  
  case object LoadStateRequest
  
}