package de.alog
package persist

import akka.actor._
import Messages._
import java.security.MessageDigest
import redis._
import org.joda.time.DateTime
import de.alog.util.Helpers
import scala.concurrent.Future
import util.DateTime.dateTimeFmt
import de.alog.util.LogDatabase
import com.mongodb.casbah.commons.MongoDBObject
import org.joda.time.Period
import org.joda.time.Duration
import scala.concurrent.Await
import com.mongodb.casbah.MongoDB

class LogPersister extends Actor with LogDatabase with ActorLogging with Helpers {
  
  val redisPersister = context.actorOf(Props(classOf[RedisPersister], redisDb))
  val mongoPersister = context.actorOf(Props(classOf[MongoPersister], mongoDb))
  
  def receive = {
    case LogEntries(l,cb) if l.nonEmpty => {
      val le = l.map(e => (e, hash(e.toString)))
      
      val respGatherer = Some(context.actorOf(ResponseGatherActor(2, cb, Some(l.length))), PersistResponse)
      redisPersister ! PersistRequest(le, respGatherer)
      mongoPersister ! PersistRequest(le, respGatherer)
      log.debug(s"archiving ${l.size} messages")
    }
    case LogEntries(Nil, cb) =>
      cb.foreach(c => c._1 ! c._2)
      
  }
	
}

class RedisPersister(db:RedisClient) extends Actor with ActorLogging with Helpers {
  def receive = {
    case PersistRequest(le,cb) =>
      val allFeatureValues = toFeatureValueMap(le) 
     
      val tx = db.transaction()
      implicit def logScore(e:LogEntry):Double = e.timestamp.getMillis.doubleValue
      tx.zadd("timeline.global", le.map { case (e, hashv) => (logScore(e), hashv) }: _*)
      le.foreach { case (e, hashv) =>
        e.features.map {
          case (logkey, value) => tx.zadd(s"timeline.${logkey}_${value}", (logScore(e), hashv)) 
        }
      }
  
      tx.sadd("feature.keys", allFeatureValues.keys.toSeq: _*)
      allFeatureValues.filter(_._2.nonEmpty).map { 
        case (logkey,values) => tx.sadd(s"feature.${logkey}.values", values.toSeq: _*)
      }
      implicit val ec = context.system.dispatcher
      tx.exec().onComplete(t => cb.map{ case (actor, value) => actor ! value } )
  }
  
  private def toFeatureValueMap(l:Seq[(LogEntry, String)]): scala.collection.Map[String,scala.collection.Set[String]] = {
    // Function-like impl, albeit somewhat expensive
//    l.flatMap(_._1.features).aggregate(Map[String,Seq[String]]())((b,a)=> {
//      val x = b.get(a._1).getOrElse(Seq[String]())
//      b - a._1 + ((a._1, x:+a._2))
//    }, (b1,b2)=> b1 ++ b2)
    import scala.collection._
    val res = mutable.Map[String,mutable.Set[String]]()
    l.flatMap(_._1.features).foreach { case (fk, fv) =>
      res.getOrElseUpdate(fk, mutable.Set()) += fv
    }
    res
  }
}


class MongoPersister(db:MongoDB) extends Actor with ActorLogging with Helpers {
    
  def receive = {
    case PersistRequest(le,cb) =>
      val redundant = le.sliding(16).toList.collect { case (e1, h1)::tail => tail.filter { case (e2, h2) => h1 == h2 }}.flatten.map(_._2).toSet
      log.debug(s"found ${redundant.size} redundant log entries in request")     
      
      import scala.concurrent.ExecutionContext.Implicits.global
      val mgf = Future {  
        storeEntries(le)
      }.onComplete(t => cb.map{ case (actor, value) => actor ! value } )
  }
  
  def storeEntries(le : Seq[(LogEntry, String)]) {
    val c = db("logEntries")
    val tx1 = c.initializeUnorderedBulkOperation
    val hashvs = le.map{ case (_, hashv) => hashv }
    hashvs.foreach { hashv =>
      tx1.find(MongoDBObject("_id" -> hashv)).removeOne
    }
    log.debug(s"deleting ${hashvs.length} values, ${hashvs.toSet.size} unique")
    tx1.execute()
    
    val tx2 = c.initializeUnorderedBulkOperation
    val seen = collection.mutable.Set[String]()
    le.collect { case (e, hashv) if (!seen.contains(hashv)) =>
      tx2.insert(MongoDBObject(
        (Seq(
          "_id" -> hashv,
          "message" -> e.msg,
          "timestamp" -> e.timestamp
        ) ++ e.features.toSeq) :_*            
      ))
      seen += hashv
    }
    log.debug("storing new values")
    tx2.execute()
    log.debug("updating indexes")
    features(le).foreach(c.ensureIndex(_))
    log.debug("processing finished")
  }
  
  def features(le : Seq[(LogEntry, String)]): Set[String] = {
    import scala.collection._
    val res = mutable.Set[String]()
    res ++= le.flatMap(_._1.features.keySet)
    res.toSet
  }
}

class ResponseGatherActor(expected: Int, cb: Option[(ActorRef,Any)], logcount: Option[Int]) extends Actor with ActorLogging {
  
  var received = 0
  
  val started = System.nanoTime
  
  def receive = {
    case PersistResponse =>
      received += 1
      if (received == expected) {
        logcount.foreach(count => log.info(s"storing ${count * (1e9.toLong) / (System.nanoTime - started)} entries/sec"))
        log.debug(s"signaling all db-ops being completed")
        cb.foreach(c => c._1 ! c._2)
        context.stop(self)
      }
  }
  
  override def preStart = {
    import scala.concurrent.duration._
    implicit val ec = context.system.dispatcher
    val ctx = context
    context.system.scheduler.scheduleOnce(30 seconds){ log.debug(s"actor stopping due to timeout:${self}"); ctx.stop(self) }
  }
}

object ResponseGatherActor {
  def apply(expected: Int, cb: Option[(ActorRef,Any)], logcount: Option[Int]=None) =
    Props(classOf[ResponseGatherActor], expected, cb, logcount)
}

case class PersistRequest(entries:Seq[(LogEntry, String)], cb:Option[(akka.actor.ActorRef, Any)])
case class PersistResponse()
