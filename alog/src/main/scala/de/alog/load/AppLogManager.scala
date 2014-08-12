package de.alog.load

import Messages._
import akka.actor._
import akka.util._
import akka.pattern._
import scala.concurrent.duration._
import Deadline._
import scala.collection._
import mutable.Queue
import scala.math.Ordering.by
import scala.util.Sorting
import scala.util.Random
import scala.math.Numeric

class AppLogManager(workerClientRef:ActorRef) extends Actor with ActorLogging {

  implicit val ec = context.system.dispatcher
  
  val timeoutTime = 120 seconds
  val cycleTime = 30 seconds
  val scheduleSlackTime = 500 millis
  
  context.system.scheduler.schedule(2 seconds, 1 seconds, self, Tick(true))

  private var workQueue = Queue[LogRequest]() 
  private implicit val workQueueSorting = by[LogRequest,Deadline](_.scheduled)
  private var lastAnnounced:Option[LogRequest] = None
  
  def receive = {
    case RegisterLogfile(f, l@_*) => 
      workQueue.find(_.file == f) match {
        case None => 
          workQueue += LogRequest(f, l.toMap, workQueue.length * 500 millis fromNow, None)
          workQueue = workQueue.sorted
          self ! Tick(false)
        case _ =>
      }
    case RestoreLogfileState(states) =>
      val newFiles = states.map(_.file).toSet
      workQueue = (workQueue.filterNot(f => newFiles.contains(f.file)) ++ states).sorted
      log.info(s"restored log with ${states.length} entries")
      self ! Tick(false)
    case UnregisterLogfile(f) => {
      workQueue.dequeueFirst(_.file == f)
    }
    case GetRegistered => {
      sender() ! RegisteredLogfiles(workQueue.to[immutable.Seq])
    }
    
    case GetWork => 
      workQueue.headOption.filter(_.scheduled.isOverdue).foreach { item =>
        workQueue.dequeue
        sender ! item
        workQueue += reschedule(timeoutTime fromNow)(item)
        workQueue = workQueue.sorted
        self ! Tick
      }
    case Completed(req:LogRequest) =>
      workQueue.dequeueFirst(_.file == req.file)
      val waitTime = req.recentState.map(s => if (s.failed && s.occurences <= 3) timeoutTime else getTimeout(s)).getOrElse(timeoutTime)   
      workQueue += reschedule( waitTime.fromNow)(req)
      workQueue = workQueue.sorted      
    case Rejected(req:LogRequest) => 
      workQueue.dequeueFirst(_.file == req.file)
      reschedule(now)(req) +=: workQueue
    
    case Tick(h) => 
      workQueue match {
        case Queue(i1, rem@_*)
          if (i1.scheduled + scheduleSlackTime).isOverdue && (h || !lastAnnounced.exists(_==i1)) => {
          workerClientRef ! WorkAvailable
          lastAnnounced = Some(i1)
        }
        case _ =>
      }

  }
  
  private def reschedule(newSchedule:Deadline)(item:LogRequest) = LogRequest(item.file,item.labels,newSchedule,item.recentState)
  
  private val maxRate = 64
  private val ratio = 1.5 // expected ratio of reads / new content found - 1.5 yields the sequence 2/3 - 1/3 - 1/6 - 1/12
  
  private def getTimeout(r:ReadState): FiniteDuration = {
    import math._
    val time = System.currentTimeMillis
    val period = cycleTime.toMillis
    val level = r.history.foldLeft(maxRate)((a,b) => Math.min(a, floor((time - b) / (ratio * period)).toInt))
    cycleTime * (1 + level)
  } 
}


private case class Tick(heartbeat:Boolean)
