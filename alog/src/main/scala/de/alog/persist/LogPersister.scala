package de.alog
package persist

import akka.actor._
import Messages._
import java.security.MessageDigest
import redis._
import org.joda.time.DateTime
import de.alog.util.Helpers
import scala.concurrent.Future
import util.AnwDateTime.dateTimeFmt
import de.alog.util.LogDatabase
import org.joda.time.Period
import org.joda.time.Duration
import scala.concurrent.Await
import com.mongodb.casbah.Imports._
import de.alog.cont.Messages.AddedLogEntries


class LogPersister extends Actor with LogDatabase with ActorLogging with Helpers {
  
  val mongoLogPersister = context.actorOf(Props(classOf[MongoLogPersister], mongoDb))
  val mongoFeaturePersister = context.actorOf(Props(classOf[MongoFeaturePersister], mongoDb))
  val notificationEmitter = context.actorOf(Props(classOf[NotifiactionEmitter]))
  
  def receive = {
    case LogEntries(l,cb) if l.nonEmpty => {
      val le = l.map(e => (e, hash(e.toString)))
      val ne = (notificationEmitter, AddedLogEntries(le.map(_._2)))
      
      val respGatherer = Some(context.actorOf(ResponseGatherActor(2, cb.toSeq :+ ne, Some(l.length))), PersistResponse)
      mongoFeaturePersister ! PersistRequest(le, respGatherer)
      mongoLogPersister ! PersistRequest(le, respGatherer)
      log.debug(s"archiving ${l.size} messages")
    }
    case LogEntries(Nil, cb) =>
      cb.foreach(c => c._1 ! c._2)
  }
  
}

class MongoFeaturePersister(db:MongoDB) extends Actor with ActorLogging with Helpers {
  
  val whiteList = Set("bindDN","sourceIP","status","durationRange")
  
  def receive = {
    case PersistRequest(le,cb) =>
      val allFeatureValues = toFeatureValueMap(le) 
     
      implicit val ec = context.system.dispatcher
      Future {
    	  val coll = db("logMeta")
        coll.update(MongoDBObject("type" -> "feature"), $addToSet("keys") $each (allFeatureValues.keys.toSeq: _*), upsert=true)      
        allFeatureValues.filter(_._2.nonEmpty).map { 
          case (logkey,values) if whiteList.contains(logkey) => 
            coll.update(MongoDBObject("type" -> "featureValue", "key" -> logkey), $addToSet("values") $each (values.toSeq: _*), upsert=true)
        }
    	  allFeatureValues.keys.filter(whiteList.contains).foreach {
    	     coll.ensureIndex
    	  }
      }.onComplete(t => cb.map{ case (actor, value) => actor ! value } )
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


class MongoLogPersister(db:MongoDB) extends Actor with ActorLogging with Helpers {
    
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
    val tx = c.initializeOrderedBulkOperation
    val hashvs = le.map{ case (_, hashv) => hashv }
//    hashvs.foreach { hashv =>
//      tx.find(MongoDBObject("_id" -> hashv)).removeOne
//    }
    val seen = collection.mutable.Set[String]()
    le.collect { case (e, hashv) if (!seen.contains(hashv)) =>
      tx.insert(MongoDBObject(
        (Seq(
          "_id" -> hashv,
          "message" -> e.msg,
          "timestamp" -> e.timestamp,
          "_timestamp_mse" -> e.timestamp.getMillis
        ) ++ e.features.toSeq) :_*            
      ))
      seen += hashv
    }
    tx.execute()
    log.debug("processing finished")
  }
  
  def features(le : Seq[(LogEntry, String)]): Set[String] = {
    import scala.collection._
    val res = mutable.Set[String]()
    res ++= le.flatMap(_._1.features.keySet)
    res.toSet
  }
}

class NotifiactionEmitter extends Actor {
  def receive = {
    case a:AddedLogEntries => 
      context.system.eventStream.publish(a)
  }
}

class ResponseGatherActor(expected: Int, cb: Seq[(ActorRef,Any)], logcount: Option[Int]) extends Actor with ActorLogging {
  
  var received = 0
  
  val started = System.nanoTime
  
  def receive = {
    case PersistResponse =>
      received += 1
      if (received == expected) {
        logcount.foreach(count => log.info(s"storing $count entries at ${count * (1e9.toLong) / (System.nanoTime - started)} entries/sec"))
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
  def apply(expected: Int, cb: Seq[(ActorRef,Any)], logcount: Option[Int]=None) =
    Props(classOf[ResponseGatherActor], expected, cb, logcount)
}

case class PersistRequest(entries:Seq[(LogEntry, String)], cb:Option[(akka.actor.ActorRef, Any)])
case class PersistResponse()
