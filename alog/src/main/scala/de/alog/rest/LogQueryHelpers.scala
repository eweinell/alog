package de.alog
package rest

import akka.event.LoggingAdapter
import util._
import util.AnwDateTime.dateTimeFmt
import org.joda.time.{DateTime, Period, Duration=>JDuration}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import akka.dispatch.Futures
import scala.concurrent.ExecutionContext
import spray.json._
import DefaultJsonProtocol._
import spray.httpx.marshalling.Marshaller
import scala.util.Try
import com.mongodb.casbah.commons.{MongoDBObject=>M}
import com.mongodb.casbah.Imports._

trait LogQueryHelperDeps {
  def log:LoggingAdapter
  def mongoDb:MongoDB
}

object LogQueryHelper {
  val reserved = Set("qfrom", "qtill", "qduration", "qdetail", "qmessage", "qoffset", "qlimit", "qgroup", "qmaxlen", "qtail", "qtailtopic", "qformat")  
}
import LogQueryHelper._

trait LogQueryHelper extends Helpers {
  self : LogQueryHelperDeps =>
      
  def getIds(implicit helper:QueryHelper, resultAssembler:Seq[String] => JsField): Future[List[JsField]] = {
    Future {
      groupResult (
          d => limited(mongoDb("logEntries").find(standardRestriction, d(MongoDBObject("_id" -> 1, "timestamp" -> 1))).sort(MongoDBObject("timestamp" -> 1))), 
          r => "id" -> JsArray(r.flatMap(_.getAs[String]("_id").map(JsString(_))).toList)
      )
    }
  }
  
  def getCard(implicit helper:QueryHelper, resultAssembler:Long => JsField): Future[List[JsField]] = {
    Future {
    	def prepareResult(groupInfo: DBObject => List[JsField])(data: List[DBObject]): List[JsField] =
        ("group", JsArray(data.map(dbo => JsObject(
      		  groupInfo(dbo) :+ 
      			("count" -> JsNumber(dbo.getAs[Int]("count").getOrElse(0)))
    		)))) +:
    		(helper.queryGroupByDate.map(p => List(
    		  ("period", JsObject("description" -> JsString(p.toString), "seconds" -> JsNumber(p.toStandardSeconds.getSeconds)))
    		)).getOrElse(Nil)) :+
    		  ("count" -> JsNumber(mongoDb("logEntries").find(standardRestriction).count))
    		
    	val countByPeriod = (period:Long) => mongoDb("logEntries").aggregate(List(
        M("$match" -> standardRestriction),
        M("$project" -> M(
          "_id" -> 1, 
          "timestamp" -> 1,
          "_timestamp_mse" -> 1,
          "_timestamp_base_k" -> 
            M("$subtract" -> List(
              "$_timestamp_mse", 
              M("$mod" -> List(
                M("$subtract" -> List(
                  "$_timestamp_mse", 
                  helper.rangeStart.getMillis)),
                period))))
        )),
        M("$group" -> M(
          "_id" -> "$_timestamp_base_k", 
          "count" -> M("$sum" -> 1))),
        M("$sort" -> M(
          "_id" -> 1))
      ), AggregationOptions(AggregationOptions.CURSOR)).toList
      
    	def countByPeriodRes(period:Period) = {
    	  val res = countByPeriod(period.toStandardSeconds().getSeconds * 1000) 
    	  prepareResult(d => {
    	    val start = d.getAs[Long]("_id").map(new DateTime(_))
    	    val end = start.map(_.plus(period))
    	  	List ("groupStart" -> start, "groupEnd" -> end, "count" -> JsNumber(d.getAs[Long]("count").getOrElse(0l)))
    	  })(res)
    	}      	  
      val countByFeature = (feature:String) => mongoDb("logEntries").aggregate(List(
        M("$match" -> standardRestriction),
        M("$project" -> M("_id" -> 1, feature -> 1)),
        M("$group" -> M("_id" -> ("$"+feature), "count" -> M("$sum" -> 1))),
        M("$sort" -> M("count" -> -1))
      ), AggregationOptions(AggregationOptions.CURSOR)).toList
      
      def countByFeatureRes(feature:String) = 
        prepareResult(d => List(feature -> JsString(d.getAs[String]("_id").getOrElse(""))))(countByFeature(feature))
        
      val count = () => mongoDb("logEntries").find(standardRestriction).count
      
      groupResultBase (countByPeriodRes _, countByFeatureRes _, () => List(("count", JsNumber(count()))))
    }
  }
  
  def getMeta(implicit helper:QueryHelper, resultAssembler:Seq[JsValue] => JsField): Future[List[JsField]] =
   Future {
     groupResult (
         d => limited(mongoDb("logEntries").find(standardRestriction, MongoDBObject("message" -> 0)).sort(MongoDBObject("timestamp" -> 1))),
         r => "meta" -> JsArray(r.map(bson2json).toList)
     )
    }

  def getFull(implicit helper:QueryHelper, resultAssembler:Seq[JsObject] => JsField): Future[List[JsField]] =
    Future {
      groupResult (
         d => limited(mongoDb("logEntries").find(standardRestriction, MongoDBObject.empty).sort(MongoDBObject("timestamp" -> 1))),
         r => "full" -> JsArray(r.map(bson2json).toList)
     )
    }

  def getElement(implicit helper:QueryHelper, id:String): Future[Option[JsObject]] = 
    Future {
      mongoDb("logEntries").findOne("_id" $eq id).map(bson2json _)
    }
  
  private def groupResult(query: (MongoDBObject=>MongoDBObject)=>MongoCursor, aggr: Iterator[DBObject]=>JsField)(implicit helper:QueryHelper): List[JsField] = 
	  List(groupResultBase(groupByTimestamp(query, aggr)_, groupByFeature(query, aggr) , () => aggr(query(identity))))

	private def groupResultBase[T](groupByTimestamp$: Period => T, groupByFeature$: String => T, ungrouped: () => T)(implicit helper:QueryHelper) : T =
	  (helper.queryGroupByDate, helper.queryGroupByFeature) match {
		  case (Some(period), _) =>
  		  groupByTimestamp$(period)
		  case (_, Some(feature)) =>
  		  groupByFeature$(feature)
		  case (_, _) =>
	    	ungrouped()
  }
  
    
  private def groupByFeature(query:(MongoDBObject => MongoDBObject) => MongoCursor, aggr: Iterator[DBObject] => JsField)(feature:String)(implicit helper:QueryHelper): JsField = {
    val featured = query(enrich(feature))
    val grouped = featured.toSeq.groupBy ( o => Option(o.get(feature)).map(_.toString).getOrElse(""))
    ("group", JsArray(grouped.map(g => JsObject(feature -> JsString(g._1), aggr(g._2.toIterator))).toList))
  }

  private def groupByTimestamp(query:(MongoDBObject => MongoDBObject) => MongoCursor, aggr:Iterator[DBObject] => JsField)(period:Period)(implicit helper:QueryHelper): JsField = {
    def todate(o:DBObject): Option[DateTime] =
      Option(o.get("timestamp")).collect { case d:DateTime => d }
    
    val sorted = query(enrich("timestamp")).sort(MongoDBObject(("timestamp" -> 1))).toSeq
    val dates = Iterator.iterate(helper.rangeStart)(_.plus(period)).takeWhile(_.isBefore(helper.rangeEnd)) ++ List(helper.rangeEnd)
    val grouped = dates.sliding(2).collect { case Seq(from, till) if from isBefore till =>
      implicit def d2s(d: DateTime) : JsString = JsString(util.AnwDateTime.dateTimeFmt.print(d))
      val elems = sorted.zip(sorted.map(todate _)).collect { case (o,Some(date)) => (o,date) }
      val res = elems
        .dropWhile(_._2.isBefore(from))
        .takeWhile(_._2.isBefore(till))
        .map(_._1)
        
      JsObject("groupStart" -> from, "groupEnd" -> till, aggr(res.iterator))
    }
    ("group", JsArray(grouped.toList))
  }
  
  private def enrich(f: String)(o: MongoDBObject): MongoDBObject =
    if (o.exists(kv => kv._2 == 1)) MongoDBObject(o.toSeq.filterNot(_._1 == f) :+ (f -> 1): _*) else o

  def standardRestriction(implicit helper:QueryHelper) = {
    val timeRestr = "timestamp" $gte helper.rangeStart $lt helper.rangeEnd
    val textRestr = helper.queryText.map($text(_)).toSeq
    val idRestr = helper.idFilter.map("_id" $in _).toSeq
    $and(timeRestr +: (helper.effectiveParameters.map{case (k,v) => k $eq v} ++ textRestr ++ idRestr))
  }
  
  private def limited(c:MongoCursor)(implicit helper:QueryHelper) =
    helper.limit match {
      case None => c
      case Some((offset, limit)) => c.skip(offset.toInt).limit(limit.toInt)
   }

  implicit def date2Js(d:Option[DateTime]):JsString = JsString(d.map(dateTimeFmt.print(_)).getOrElse(""))
  
  private def bson2json(dbo:DBObject)(implicit helper:QueryHelper) = 
		JsObject(dbo.filterKeys(!_.startsWith("_")).map {
    		case (k, d:DateTime) => (k, JsString(util.AnwDateTime.dateTimeFmt.print(d)))
    		case (k@"message", m:String) => (k, JsString(helper.messageMaxlen.filter(_ < m.length).map(m.take(_) + "\u00a0\u2026").getOrElse(m)))
    		case (k, r) => (k, JsString(r.toString))
  		}.toList ++ 
  		List(
  		  "__logId" -> JsString(dbo.getAs[String]("_id").getOrElse("")),
  		  "__features" -> JsArray(dbo.collect { case (k,_) if !k.startsWith("_") && !Set("timestamp","message").contains(k) => JsString(k)}.toList)
  		)
  )
  
  def json2csv(in: List[JsField]): List[String] = {
    val arr = in.headOption.flatMap(_._2 match { case JsArray(a) => Some(a); case _ => None }).getOrElse(Nil)
    val features = arr.flatMap(_.asJsObject.fields.keySet).filterNot(_.startsWith("__")).toSet.toList.sorted
    val res = "#" + features.mkString(";")
    val r = arr.map { l =>
      features.map { f  => 
        l.asJsObject.fields.getOrElse(f, "")
      }.mkString(";")
    }
    res +: r
  }
  
  import spray.http.HttpEntity
  import spray.http.MediaTypes._
  
  implicit val Json2CsvMarshaller = Marshaller.of[JsObject] (`text/csv`) { (value, contentType, ctx) =>
    val fs = value.fields.toList.drop(2)
    ctx.marshalTo(HttpEntity(contentType, json2csv(fs).mkString("\n")))
  }
    
  
}

import util.Helpers._

case class QueryHelper(parameters:Map[String,String], limit:Option[(Long, Long)]=None, idFilter:Option[Iterable[String]]=None) {
    
  lazy val effectiveParameters:Seq[(String,String)] = parameters.filter { case (k,v) => !reserved.contains(k) && Option(v).filter(_.trim.nonEmpty).isDefined }.toSeq
  
  lazy val (rangeStart:DateTime, rangeEnd:DateTime) = (parameters.get("qfrom"), parameters.get("qtill"), parameters.get("qduration")) match {
    case (OptDateTime(from), OptDateTime(till), _) if till.isAfter(from) => 
      (from, till)
    case (OptDateTime(from), _, Duration(dur)) =>
      (from, from.plus(dur))
    case (_, OptDateTime(till), Duration(dur)) =>
      (till.minus(dur), till)
    case (OptDateTime(from), _, _) =>
      (from, DateTime.now)
    case (_, OptDateTime(till), _) =>
      (till.minus(Period.hours(24)), till)        
    case (_, _, Duration(dur)) =>
      (DateTime.now.minus(dur), DateTime.now)
    case (_, _, _) =>
      (DateTime.now.minus(Period.hours(24)), DateTime.now)
  }
  
  lazy val messageMaxlen:Option[Int] = parameters.get("qmaxlen").flatMap(s => Try(s.toInt).toOption)
  
  lazy val queryText:Option[String] = parameters.get("qmessage").filter(_.trim.nonEmpty)
  
  private def periodFor(splits:Int) = nextStandardDuration(new JDuration(rangeStart, rangeEnd).getMillis / splits)
  lazy val (standardPeriod, minPeriod) = (periodFor(80), periodFor(320))

  private val groupBySpecificTime = "timestamp:(.+)".r
  lazy val queryGroupByDate: Option[Period] = parameters.get("qgroup") collect { 
    case groupBySpecificTime(Duration(d)) => if (d.toStandardSeconds().getSeconds() >= minPeriod.toSeconds) d else minPeriod
    case "timestamp" => standardPeriod
  }
  
  lazy val queryGroupByFeature: Option[String] = if (queryGroupByDate.isDefined) None else parameters.get("qgroup")
  
}
