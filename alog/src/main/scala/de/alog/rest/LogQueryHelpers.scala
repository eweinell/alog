package de.alog
package rest

import akka.event.LoggingAdapter
import redis._
import util._
import org.joda.time.{DateTime, Period}
import redis.api._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import akka.dispatch.Futures
import scala.concurrent.ExecutionContext
import spray.json._
import DefaultJsonProtocol._
import scala.util.Try
import com.mongodb.casbah.MongoDB
import com.mongodb.casbah.Imports._

trait LogQueryHelperDeps {
  def log:LoggingAdapter
  def redisDb:RedisClient
  def mongoDb:MongoDB
}

object LogQueryHelper {
  val reserved = Set("qfrom", "qtill", "qduration", "qdetail", "qmessage", "qoffset", "qlimit", "qgroup")  
}
import LogQueryHelper._

trait LogQueryHelper extends Helpers {
  self : LogQueryHelperDeps =>
  
  private def Incl(d:DateTime) = Limit(d.getMillis(), true)
  private def Excl(d:DateTime) = Limit(d.getMillis(), false)
      
  def getIds(helper:QueryHelper, resultAssembler:Seq[String] => JsField): Future[JsField] = {
    runQuery(helper, (key,helper) => helper.queryMessage match {
      case Some(v) if v.nonEmpty => metaFetcher(false)(key, helper).map(_.map(_.fields.get("__logId") match { case Some(JsString(id)) => id; case _ => ""})) 
      case _ => redisDb.zrangebyscore[String](key, Incl(helper.rangeStart), Excl(helper.rangeEnd), helper.limit) 
    }, resultAssembler)
  }
      
  def getCard(helper:QueryHelper, resultAssembler:Long => JsField): Future[JsField] = {
    runQuery(helper, (key,helper) => helper.queryMessage match { 
      case Some(v) if v.nonEmpty => metaFetcher(false)(key, helper).map(_.length.toLong)
      case _ => redisDb.zcount(key, Incl(helper.rangeStart), Excl(helper.rangeEnd))
    }, resultAssembler)
  }
  
  def getMeta(helper:QueryHelper, resultAssembler:Seq[JsValue] => JsField): Future[JsField] = {
   runQuery(helper, metaFetcher(false), resultAssembler)
  }

  def getFull(helper:QueryHelper, resultAssembler:Seq[JsObject] => JsField): Future[JsField] = {
     runQuery(helper, metaFetcher(true), resultAssembler)
  }

  def getElement(id:String): Future[Option[JsObject]] = 
    Future {
      mongoDb("logEntries").findOne("_id" $eq id).map(bson2json(true)_)
    }
  
  def test(helper:QueryHelper) = {
    val timeRestr = "timestamp" $gte helper.rangeStart $lt helper.rangeEnd
    val query = $and(timeRestr +: helper.effectiveParameters.map{case (k,v) => k $eq v})
    
    mongoDb("logEntries").find(query, MongoDBObject("id" -> 1, "timestamp" -> 1)).sort(MongoDBObject("timestamp" -> 1))
  }
  
  
  private[rest] def metaFetcher(includeMessage:Boolean)(key:String, helper:QueryHelper): Future[Seq[JsObject]] =
		redisDb.zrangebyscore[String](key, Incl(helper.rangeStart), Excl(helper.rangeEnd), helper.limit).flatMap { 
		case ids if ids.nonEmpty => 
		Future {
			val query = helper.queryMessage match {
			case Some(q) if q.nonEmpty => $and("_id" $in ids, $text(helper.queryMessage.get))
			case _ => "_id" $in ids
			}
			mongoDb("logEntries").find(query).sort(MongoDBObject("timestamp" -> 1)).map(bson2json(includeMessage)_).toSeq
		}
		case _ => Futures.successful(Nil)
  }
  
  private def bson2json(includeMessage:Boolean)(dbo:DBObject) = 
		JsObject(dbo.mapValues {
    		case d:DateTime => JsString(util.DateTime.dateTimeFmt.print(d))
    		case r@_ => JsString(r.toString)
  		}.filterKeys(if (includeMessage) _ != "_id" else !Set("_id", "message").contains(_) ).toList ++ 
  		List("__logId" -> JsString(dbo.getAs[String]("_id").getOrElse("")))
  )
  
  def runQuery[T] (helper:QueryHelper, resultFetcher:(String,QueryHelper)=>Future[T], resultAssembler: T=>JsField): Future[JsField] = {
    val wrappedProcessor = fnForQGroup(resultFetcher, resultAssembler) _
    
    def key(s:(String,String)) = s"timeline.${s._1}_${s._2}"  
    implicit val dc= redis.ByteStringDeserializer.String
  
    (helper.effectiveParameters.toList match {
      case Nil => 
        wrappedProcessor("timeline.global", helper)
      case h :: t => 
        val tmpKey = s"timeline_temp.${helper.queryIdentifier}"
        redisDb
          .zinterstore(tmpKey, key(h), t.map(key(_)), MAX)
          .map {z => redisDb.expire(tmpKey, 300l); z}
          .flatMap(_ => wrappedProcessor(s"timeline_temp.${helper.queryIdentifier}", helper))
      case r@_ =>
        log.error("unmatched: "+r)
        Futures.failed(new RuntimeException)
    })
  }
  
  def fnForQGroup[T] (resultFetcher:(String,QueryHelper) => Future[T], resultAssembler: T=>JsField)(key:String,helper:QueryHelper) : Future[JsField] =
    (helper.queryGroupByDate, helper.queryGroupByFeature) match {
      case (None, None) => 
        resultFetcher(key, helper).map(resultAssembler(_))
      case (Some(p), _) =>
        val dates =  Iterator.iterate[DateTime](helper.rangeStart)(_.plus(p)).takeWhile(_.isBefore(helper.rangeEnd)) ++ List(helper.rangeEnd)
        val fs = for(Seq(from, to) <- dates.sliding(2, 1).toSeq if from != to) yield {
          implicit def d2s: DateTime => String = util.DateTime.dateTimeFmt.print _
          val h = QueryHelper(helper.parameters.filterKeys(_!="qgroup"), helper.limit, Some((from, to)))
          resultFetcher(key, h).map(resultAssembler).map(e => JsObject(List(("groupStart" -> JsString(from)), ("groupEnd" -> JsString(to))) :+ e))
        }
        Future.sequence(fs).map(e => ("group" -> JsArray(e.toList)))
      case(_, Some(groupField)) =>
        metaFetcher(false)(key, helper).flatMap { metas =>
          import scala.collection._
          val grouped = mutable.LinkedHashMap[String,mutable.Set[String]]()
          metas.map(_.getFields(groupField, "__logId")).foreach { case Seq(JsString(group), JsString(logId)) => 
            grouped.getOrElseUpdate(group, mutable.Set()) += logId
          }
                        
          val h = QueryHelper(helper.parameters.filterKeys(_!="qgroup"), helper.limit)
          val fs = grouped.collect { 
            case (group, ids) =>
              val tempKey = s"timeline_temp.${helper.queryIdentifier}_group:${group}"
              redisDb.zadd(tempKey, ids.toSeq.map(id => (h.rangeStart.getMillis.toDouble, id)): _*).flatMap { _ =>
                redisDb.expire(tempKey, 300)
                resultFetcher(tempKey, h).map(resultAssembler).map(e => JsObject(List((groupField -> JsString(group))) :+ e))
              }  
          }.toSeq
          Future.sequence(fs).map(e => ("group" -> JsArray(e.toList)))
        }
    }
  
}

import util.Helpers._
sealed case class QueryHelper(parameters:Map[String,String], limit:Option[(Long, Long)]=None, private val range:Option[(DateTime,DateTime)]=None) {
    
  lazy val effectiveParameters:Seq[(String,String)] = parameters.filter { case (k,v) => !reserved.contains(k) && v != null && v.nonEmpty }.toSeq
  
  lazy val queryIdentifier:String = hash((if (parameters.contains("qmessage")) parameters.toSeq else effectiveParameters).map{ case (a,b) => s"${a}:::${b}".getBytes } : _*).map("%02x".format(_)).mkString
  
  lazy val (rangeStart:DateTime, rangeEnd:DateTime) = range getOrElse ((parameters.get("qfrom"), parameters.get("qtill"), parameters.get("qduration")) match {
    case (OptDateTime(from), OptDateTime(till), _) if till.isAfter(from) => 
      (from, till)
    case (OptDateTime(from), _, Duration(dur)) =>
      (from, from.plus(dur))
    case (_, OptDateTime(till), Duration(dur)) =>
      (till.minus(dur), till)
    case (OptDateTime(from), _, _) =>
      (from, from.plus(Period.hours(24)))
    case (_, OptDateTime(till), _) =>
      (till.minus(Period.hours(24)), till)        
    case (_, _, Duration(dur)) =>
      (DateTime.now.minus(dur), DateTime.now)
    case (_, _, _) =>
      (DateTime.now.minus(Period.hours(24)), DateTime.now)
  })
  
  lazy val queryMessage:Option[String] = parameters.get("qmessage")
  
  lazy val queryGroupByDate: Option[Period] = parameters.get("qgroup") collect { case Duration(d) if d.toStandardSeconds().getSeconds() > 0 => d }
  
  lazy val queryGroupByFeature: Option[String] = if (queryGroupByDate.isDefined) None else parameters.get("qgroup")
  
}
