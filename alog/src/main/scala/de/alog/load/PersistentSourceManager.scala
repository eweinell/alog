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
    val lWithId = queue.map(l => (l.file.hash, l))
    redisDb.smembers[String]("sources").map { currm => 
      val (currms, newms) = (currm.toSet, lWithId.map(_._1).toSet)
      val todel = currms -- newms
      val toadd = newms -- currms
      if (todel.nonEmpty) 
        redisDb.del(todel.map(id => s"sources.${id}").toSeq : _*)
      if (todel.nonEmpty || toadd.nonEmpty) {
        redisDb.del("sources").onSuccess {
          case _ if lWithId.nonEmpty => 
            redisDb.sadd("sources", lWithId.map(_._1).toSeq: _*)
        }
      }
    }
    val tosafe = lWithId.map {
      case (key, l) => (s"sources.${key}", JsObject(Map(
        "file" -> JsString(l.file),
        "labels" -> JsObject(l.labels.map{ case (k,v) => (k, JsString(v))}),
        "state" -> JsString(
            l.recentState.flatMap(
                _.readMark.map(_.map("%02X".format(_)).mkString))
            .getOrElse(""))
        )).compactPrint)
    }
    redisDb.mset(tosafe.toMap).foreach { _ =>
      log.info("saved log state")
    }
  }
  
  def loadLogQueue() : Future[Seq[LogRequest]] = {
    implicit val ec = context.system.dispatcher
    val raws = redisDb.smembers[String]("sources").flatMap(ids => redisDb.mget[String](ids.map(id => s"sources.${id}"): _*))
    raws.map(_.collect {case Some(raw) => raw.parseJson.asJsObject
      .getFields("file", "labels", "state") match {
      case Seq(JsString(file), JsObject(labels), JsString(state)) =>
        LogRequest(file, labels.map {case (k,JsString(v)) => (k,v)}, Deadline.now, Some(ReadState(hex2bytes(state), false, "loaded from saved state")))
      }
    })
  }
  
  def hex2bytes(s:String): Option[Array[Byte]] = 
    if (s.isEmpty()) None else Try(s.sliding(2, 2).toArray.map(Integer.parseInt(_, 16).toByte)).toOption 
  
}

