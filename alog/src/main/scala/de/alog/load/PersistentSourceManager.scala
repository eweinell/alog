package de.alog
package load

import akka.actor._
import de.alog.load.Messages.LogRequest
import spray.json._
import DefaultJsonProtocol._
import redis._
import util.LogDatabase
import util.Helpers._
import scala.concurrent.duration._
import de.alog.load.Messages.{GetRegistered,RegisteredLogfiles,LogRequest}
import scala.concurrent.Future
import de.alog.load.Messages.LoadStateRequest
import de.alog.load.Messages.LogRequest
import de.alog.load.Messages.ReadState
import de.alog.load.Messages.RestoreLogfileState
import scala.util.Try
import org.joda.time.DateTime
import com.mongodb.casbah.Imports._
import com.mongodb.BasicDBList

class PersistentSourceManager(loadManager:ActorRef) extends Actor with LogDatabase with PersistentSourceManagerLike with ActorLogging {

  val saveRate = 15 seconds

  implicit val ec = context.system.dispatcher
  context.system.scheduler.schedule(saveRate * 2, saveRate, self, SaveStateRequest)
  context.system.scheduler.scheduleOnce(0 seconds, self, LoadStateRequest)
      
  var dontRunBefore:Option[Deadline] = None
  
  def receive = {
    case SaveStateRequest if dontRunBefore.map(_.isOverdue()).getOrElse(true) =>
      loadManager ! GetRegistered
      dontRunBefore = Some((saveRate / 2).fromNow)
    case RegisteredLogfiles(queue) =>
      saveLogQueue(queue)
    case LoadStateRequest =>
      loadLogQueue.map(loadManager ! RestoreLogfileState(_)) 
  }
    
  private case object SaveStateRequest
  
}

trait PersistentSourceManagerLike {
  self : Actor with ActorLogging with LogDatabase =>

  def saveLogQueue(queue: Seq[LogRequest]) {
    implicit val ec = context.system.dispatcher
    Future {
      val logStates = mongoDb("sourceState")
      logStates.remove(MongoDBObject.empty)
      logStates.insert(
        queue.map { le => 
          MongoDBObject(
            "_id" -> le.file.hash,
            "type" -> "source",
            "file" -> le.file,
            "labels" -> le.labels.map{ case (k,v) => (k, JsString(v))},
            "state" -> le.recentState.flatMap(_.readMark.map(_.map("%02X".format(_)).mkString)).getOrElse("")
         )
      }:_*)
    }
  }
  
  def loadLogQueue() : Future[Seq[LogRequest]] = {
    implicit val ec = context.system.dispatcher
    Future {
      mongoDb("sourceState").find(MongoDBObject("type" -> "source")).map { o =>
        for (
         file <- o.getAs[String]("file");
         labels <- o.getAs[Map[String,Any]]("labels").map(_.mapValues { case l:BasicDBList if l.nonEmpty => l.getAs[String](0).getOrElse("")});
         state <- o.getAs[String]("state").map(hex2bytes _))
        yield {
          LogRequest(file, labels, Deadline.now, Some(ReadState(state, false, "loaded from saved state")))
        }
      }.collect { case Some(l) => l }.toSeq
    }
  }
  
  def hex2bytes(s:String): Option[Array[Byte]] = 
    if (s.isEmpty()) None else Try(s.sliding(2, 2).toArray.map(Integer.parseInt(_, 16).toByte)).toOption 
  
}

