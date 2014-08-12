package de.alog
package cont

import Messages._
import util.{Helpers,LogDatabase}
import akka.actor._
import rest._
import spray.json._
import com.mongodb.casbah.Imports._
import org.joda.time.DateTime
import scala.concurrent.Future
import scala.concurrent.duration._

class LogNotificationActor extends Actor with FSM[NotificationState, NotificationData] 
  with ActorLogging with Helpers with LogDatabase with LogQueryHelperDeps with LogQueryHelper {
  
  startWith(Free, Uninitialized)

  type NotificationRef = Map[ActorRef, Option[String]]
  
  var registry = Map[Query,NotificationRef]()
 
  when(Free) (baseHandlers.orElse {
    case Event(AddedLogEntries(ids), _) =>
      val registry_ = registry
      implicit val ec = context.system.dispatcher
      Future {
        registry_.foreach { case (query,clients) =>
          runQuery(query, ids).foreach( r =>
            clients.foreach { 
              case (c, None) => c ! r
              case (c, Some(v)) => c ! JsObject(v -> r)
            }
          )
        }
      }
      goto(Locked) using WaitingQueue()
  })
  
  when(Locked) (baseHandlers.orElse {
    case Event(AddedLogEntries(ids), WaitingQueue(waiting)) =>
      stay using WaitingQueue(waiting ++ ids)
    case Event(ReleaseLock, WaitingQueue(waiting)) =>
      if(waiting.nonEmpty) self ! AddedLogEntries(waiting)
      goto(Free) using Uninitialized
  })
  
  onTransition {
    case Free -> Locked => 
      setTimer("lockTimer", ReleaseLock, 1 second)
  }
  
  initialize()
  
  private case object ReleaseLock
  
  override def preStart() {
  	context.system.eventStream.subscribe(self, classOf[DeadLetter])
  	context.system.eventStream.subscribe(self, classOf[AddedLogEntries])
  }
  
  lazy val baseHandlers : StateFunction = {
    case Event(RegisterDBUpdate(query, tgt, queryId), _) =>
      registry = registry - query + (query -> (registry.getOrElse(query, Map()) + (tgt -> queryId)))
      stay
    case Event(UnregisterDBUpdate(tgt, Some(UnregisterQuery(query))), _) =>
      registry = registry.get(query).collect {
        case refs : NotificationRef if refs.size == 1 && refs.contains(tgt) => registry - query 
        case refs : NotificationRef if refs.contains(tgt) => registry - query + (query -> (registry.getOrElse(query, Map()) - tgt)) 
      }.getOrElse(registry)
      stay
    case Event(UnregisterDBUpdate(tgt, Some(UnregisterQueryId(qid))), _) =>
      doUnregisterWhen(tgt) {
        case (q, m) if m.get(tgt).flatten.exists(qid==) => q
      }
    case Event(UnregisterDBUpdate(tgt, None), _) =>
      doUnregisterWhen(tgt) {  
        case (q, m) if m.contains(tgt) => q
      }     
    case Event(DeadLetter(_, this.self, tgt), _) =>
      log.info(s"removing client actor $tgt due to dead letter")
      registry = registry.collect { 
        case (k,v) if !v.contains(tgt) => (k, v)
        case (k,v) if v.size > 1 => (k, v.filterNot(tgt==)) 
      }
      stay
  }
  
  private def runQuery(query:Query, ids:Seq[String]) = {
	  import com.mongodb.casbah.commons.{MongoDBObject=>M}
    implicit val helper = QueryHelper(query, None, Some(ids))
    logEntriesDb.aggregate(List(
      M("$match" -> standardRestriction),
      M("$project" -> M("_id" -> 1, "timestamp" -> 1)),
      M("$group" -> M(
        "_id" -> "result", 
        "count" -> M("$sum" -> 1),
        "first" -> M("$min" -> "$timestamp"),
        "last" -> M("$max" -> "$timestamp"),
        "ids" -> M("$addToSet" -> "$_id")))
    ), AggregationOptions(AggregationOptions.CURSOR))
    .toList.headOption.map{ r =>
      JsObject("type" -> JsString("push_update"),
        "count" -> JsNumber(r.getAs[Int]("count").getOrElse(0)),
        "first" -> r.getAs[DateTime]("first"),
        "last" -> r.getAs[DateTime]("last"),
        "ids" -> JsArray(r.getAs[List[String]]("ids").getOrElse(Nil).take(25).map(JsString(_))))
    }
  }
  
  private def doUnregisterWhen(tgt:ActorRef)(v : PartialFunction[(Query,NotificationRef),Query]) = {
    registry.collect(v).foreach(q => self ! UnregisterDBUpdate(tgt, Some(UnregisterQuery(q))))
    stay  
  }
  
}

sealed trait NotificationState
case object Free extends NotificationState
case object Locked extends NotificationState

sealed trait NotificationData
case object Uninitialized extends NotificationData
case class WaitingQueue(ids:Seq[String]=Seq()) extends NotificationData
