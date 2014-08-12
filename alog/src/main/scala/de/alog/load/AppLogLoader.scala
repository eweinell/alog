package de.alog
package load

import Messages._
import Internal._
import parser.Messages._
import util.Helpers

import akka.actor._
import akka.dispatch._
import akka.pattern._
import scala.io._
import scala.collection._
import scala.concurrent._
import duration._
import scala.util._
import java.security.MessageDigest
import java.io.IOException
import java.util.concurrent.Executors

class AppLogLoader(rcv:ActorRef) extends Actor with FSM[LogLoaderState, LogLoaderData] with ActorLogging{
  
  startWith(Idle, Uninitialized)
  
  when(Idle) {
    case Event(WorkAvailable, _) => stay replying GetWork
    case Event(item:LogRequest, _) => startWork(item)
    case Event(Completed(_), _) => stay
  }
  
  when(Busy) {
    case Event(m @ Completed(LogRequest(_,_,_,rs)), WorkData(_,requestor)) => 
      log.debug(s"received completion notification with state ${rs.map(_.state).getOrElse("<unknown>")}, returning to idle")
      requestor ! m
      requestor ! GetWork
      goto(Idle) using Uninitialized
    case Event(WorkAvailable, _) => stay
    case Event(l:LogRequest, _) => stay replying Rejected(l)
    case Event(WorkTimeout, WorkData(le, _)) =>
      log.warning("processing timed out")
      self ! Completed(le update ReadStateHelpers.failed("processing timed out")_)      
      stay
  }
  
  onTransition {
    case Idle -> Busy => 
      setTimer("workTimeout", WorkTimeout, AppLogLoader.timeout)      
    case Busy -> Idle =>
      cancelTimer("workTimeout")
  }
  
  def startWork(l:LogRequest) = {
    import AppLogLoader._
  	implicit val ec = context.system.dispatchers.defaultGlobalDispatcher

  	Future firstCompletedOf ( 
      (
        Future {
          log.debug(s"about to read ${l.file}, known state is ${l.recentState}")
  		    val full = readLines(l.file).toSeq
  		    val res = l.recentState match {
  		      case Some(ReadState(Some(readMark),_,_,_)) => 
  		        full dropWhile(e => !(e._2 sameElements readMark)) match {
    		        case Nil => full
    		        case some => some drop 1
  		      }
  		      case _ => full
  		    }
  		    (res.lastOption.map(_._2), res.map(_._1))
    		}(ioExecutor) map {
    		  case (readmark:Option[Array[Byte]], msg:Seq[String]) =>
      	    val newLogState = l update ReadStateHelpers.succeeded(readmark)_
      	    val rm = parser.Messages.RawMessage(l.labels, msg, Some(self.actorRef, Completed(newLogState)))
      	    rcv ! rm
        	  log.debug(s"Sent ${rm.msgs.length} new messages over the wire")
    		}
  		) ::
  		after(timeout, context.system.scheduler) {
  		  Future failed TimeoutException()
  		} :: Nil
  	) andThen {
  	  case Failure(e) =>
  	    log.error(e, s"uncompleted work ${l}: ${e.getMessage}")
  	    self ! Completed(l update ReadStateHelpers.failed(e.getMessage)_)
  	}
    goto(Busy) using(WorkData(l, sender))
  }
  
	initialize
	
}


object AppLogLoader extends Helpers {
  
  val timeout = 16 seconds
  
  val ioExecutor:ExecutionContextExecutor  = 
    ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(4))
  
  val x = ThreadPoolConfig(corePoolSize=1, maxPoolSize=4)
  
  def props(rcv:ActorRef): Props = Props(new AppLogLoader(rcv))
  
  implicit val codec = Codec.ISO8859

  private def readLines(logFile:String): Iterator[(String, Array[Byte])] = { 
		def hashAlong(b:(String, Array[Byte]), s:String) = (s, hash(b._2, s.getBytes))
		Source.fromFile(logFile).getLines
			.scanLeft("", Array[Byte](0))(hashAlong)
			.drop(1)
	}


}

object Internal {
  sealed trait LogLoaderState
  case object Idle extends LogLoaderState
  case object Busy extends LogLoaderState
  
  sealed trait LogLoaderData
  case object Uninitialized extends LogLoaderData
  case class WorkData(logRequest:LogRequest, receiver:ActorRef) extends LogLoaderData
  
  case class TimeoutException() extends Exception
  case object WorkTimeout
}

object ReadStateHelpers {
    def succeeded(mark:Option[Array[Byte]])(s:Option[ReadState]) = update(mark, false, 
        (mark, s) match { 
          case (Some(m1), Some(ReadState(Some(m2), _,_,_))) if m1.sameElements(m2) => "unchanged" 
          case _ => "OK"}) (s)
    def failed(m:String)(s:Option[ReadState]) = update(s.flatMap(_.readMark), true, m)(s)
    
    private def update(newReadMark:Option[Array[Byte]], newFailed:Boolean, newState:String)(s:Option[ReadState]): ReadState = {
      ReadState(
        readMark=newReadMark.orElse(s.flatMap(_.readMark)).orElse(None), 
        failed=newFailed, 
        state=newState, 
        occurences=s match {
          case Some(ReadState(_,oldFailed,oldState,i)) if oldFailed == newFailed && oldState == newState => i + 1
          case _ => 1
        }
      )
    }
}