package de.alog.cont

import akka.actor._
import akka.event.LoggingAdapter
import spray.json.JsObject
import de.alog.cont.Messages._
import spray.routing.RequestContext
import spray.json._
import spray.httpx.SprayJsonSupport
import DefaultJsonProtocol._
import scala.concurrent.duration._
import de.alog.util.Helpers
import scala.util.Random
import java.security.SecureRandom
import spray.http.StatusCodes
import spray.http.StatusCode
import spray.http.HttpResponse
import de.alog.rest.QueryHelper

class CometActor extends Actor with ActorLogging {

  val logn = context.actorOf(Props[LogNotificationActor])

  var registry:Map[String,ActorRef] = Map()
  
  def receive = {
    case Poll(clId, req) =>
      registry.get(clId) match {
        case Some(ref) => ref ! req
        case _  => req.complete{HttpResponse(status=StatusCodes.NotFound)}
      }
    case Register(q, Some(clId), qId) if registry.contains(clId) =>
      sender ! register(q, clId, qId)
    case Register(q, _, qId) =>
      val clId = createClId
      val act = context.actorOf(Props(classOf[ChildActor], clId))
      registry += (clId -> act)
      sender ! register(q, clId, qId)
    case Deregister(clId, qId) =>
      registry.get(clId).foreach(logn ! UnregisterDBUpdate(_, Some(UnregisterQueryId(qId))))
    case DeregisterAll(clId) =>
      registry.get(clId).foreach(_ ! ClientShutdown(clId))
    case ClientShutdown(clId) =>
      registry.get(clId).foreach(logn ! UnregisterDBUpdate(_, None))
      registry -= clId
  }
  
  def createClId = Random.alphanumeric.take(12).mkString
  def createQId = Random.alphanumeric.take(6).mkString
  
  def register(q:Query, clId:String, qId:Option[QueryId]) = {
    val reqId = qId.getOrElse(createQId)
    logn ! RegisterDBUpdate(QueryHelper(q).effectiveParameters.toMap, registry.get(clId).get, Some(reqId))
    RegisterResult(clId, reqId)
  }
  
}

class ChildActor(clid:String) extends Actor with FSM[CometState,CometData] with ActorLogging with SprayJsonSupport {
  
  import StatusCodes._
  
  val maxQueue = 5
  val heartbeat = 15 seconds
  val keepalive = 1 minute
  
  startWith(Disconnected, DisconnectedData())
  
  when(Disconnected) {
    case Event(notification:JsObject, DisconnectedData(p)) =>
      stay using DisconnectedData((if(p.length >= maxQueue) p.tail else p) :+ notification)
    case Event(newReq:RequestContext, DisconnectedData(p)) if p.nonEmpty =>
      newReq.complete(p.head)
      stay using DisconnectedData(p.tail)
    case Event(newReq:RequestContext, _) =>
      goto(Connected) using ConnectedData(newReq)
    case Event(c:ClientShutdown, _) => 
      context.stop(self)
      context.parent ! c
      stay using DisconnectedData()
  }
  
  when(Connected, heartbeat) {
    case Event(notification:JsObject, ConnectedData(conn)) =>
      conn.complete(notification)
      goto(Disconnected) using DisconnectedData()
    case Event(newReq:RequestContext, ConnectedData(old)) =>
      close(old, Conflict)
      stay using ConnectedData(newReq)
    case Event(StateTimeout, ConnectedData(req)) =>
      close(req, NoContent)
      goto(Disconnected) using DisconnectedData()
    case Event(c:ClientShutdown, ConnectedData(req)) =>
      close(req, Gone)
      self ! c
      goto(Disconnected) using DisconnectedData()
  }
  
  onTransition {
    case Connected -> Disconnected => startTOTimer
    case Disconnected -> Connected => cancelTimer("disconn_timeout")
  }
  
  private def startTOTimer { setTimer("disconn_timeout", ClientShutdown(clid), keepalive) }
  
  override def preStart {
    startTOTimer
  }
  
  initialize()
  
  def close(req:RequestContext, c:StatusCode) {
    import spray.routing._
    import spray.http._
    import HttpHeaders._
    import MediaTypes._
    import CacheDirectives._
    req.withHttpResponseHeadersMapped(_ :+ `Cache-Control`(`no-cache`)).complete{ {
      HttpResponse(status=c)
    }} 
  }
}

sealed trait CometState
private case object Disconnected extends CometState
private case object Connected extends CometState
private case object Init extends CometState

sealed trait CometData
private case class ConnectedData(req:RequestContext) extends CometData
private case class DisconnectedData(pending:List[JsObject]=Nil) extends CometData

case class ClientShutdown(clid:String)
