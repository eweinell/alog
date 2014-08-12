package de.alog
package rest

import load.Messages.RegisterLogfile
import util.{Helpers,LogDatabase}
import redis._
import cont.CometActor
import cont.Messages._
import api._
import akka.actor._
import akka.event.LoggingAdapter
import akka.dispatch.Futures
import akka.util.Timeout
import akka.pattern.ask
import spray.routing._
import spray.json._
import spray.http._
import HttpHeaders._
import MediaTypes._
import CacheDirectives._
import spray.httpx.SprayJsonSupport
import DefaultJsonProtocol._
import org.joda.time.{DateTime, Period}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.Future
import com.mongodb.casbah.Imports._

class QueryService(mgr:ActorRef) extends Actor with QueryServiceDeps with QueryServiceLike with LogDatabase with ActorLogging {

	val cometManager=context.actorOf(Props[CometActor])

	def actorRefFactory = context
  def logManager=mgr
  def receive = runRoute(route)
  
}

trait QueryServiceDeps extends LogQueryHelperDeps {
   def logManager:ActorRef
   def cometManager:ActorRef
}

trait QueryServiceLike extends HttpService with Helpers with SprayJsonSupport with LogQueryHelper {
  self: QueryServiceDeps =>
    
  private def completeQuery(baseInfo:List[JsField])(value: => Future[List[JsField]]) = 
    parameter('qformat ? "json") { (format) => 
      format match {
        case "json" =>
          respondWithMediaType(`application/json`) {
          	complete {
          		value.map(r => JsObject(baseInfo ++ r))
          	}
          }
        case "csv" =>
          respondWithMediaType(`text/csv`) {
          	complete {
          		value.map(r => json2csv(r).mkString("\n"))
          	}
          }
      }
  }
  
	val route = 
	  path("log" / "entries" ) {
	    parameters('qoffset.as[Long] ? 0l, 'qlimit.as[Long] ? 1000l, 'qdetail ? "count") { (qoffset, qlimit, qdetail) =>
  	    parameterMap { params => 
  	      val helper = QueryHelper(params, Some((qoffset, qlimit)))
  	      implicit def d2s: DateTime => String = util.AnwDateTime.dateTimeFmt.print _
  	      val completeWith = completeQuery(List(
  	          ("start" -> JsString(helper.rangeStart)),
  	          ("end" -> JsString(helper.rangeEnd))))_

  	      qdetail match {
  	        case "id" => completeWith(getIds(helper, r => ("id" -> JsArray(r.map(JsString(_)):_*))))
  	        case "count" => completeWith(getCard(helper, r => ("count" -> JsNumber(r))))
  	        case "meta" => completeWith(getMeta(helper, r => ("meta" -> JsArray(r: _*))))
  	        case "full" => completeWith(getFull(helper, r => ("full" -> JsArray(r: _*))))
  	      }
  	    }
	    }
  	} ~
  	path("log" / "entries" / "(\\w{32,40})".r ) { id =>
  		get {
  		  parameterMap { params => 
    		  val f = getElement(QueryHelper(params), id.toUpperCase)
    		  onSuccess(f) { _ match {
    	      case Some(o) => 
              respondWithMediaType(`application/json`) {
                complete {
                 o  
                }
              }
    	      case _ =>
    	        respondWithStatus(StatusCodes.NotFound) {
    	          complete("requested log entry not found")
    	        }
    		    }
    		  }
    		}
  		} 
  	} ~
  	path("log" / "tail") {
  	  post {
  	    entity(as[String]) { rq =>
  	      requestUri { uri => 
    	      val params = rq.parseJson.asJsObject.fields.collect{ case (k,JsString(v)) => (k,v) }
    	      implicit val timeout = Timeout(5 seconds)
    	      onSuccess(cometManager ? Register(params, params.get("qtail"), params.get("qtailtopic"))) { _ match {
    	        case RegisterResult(cid,qid) => complete {
    	          JsObject(Map(
    	            "qtail" -> JsString(cid), 
    	            "qtailtopic" -> JsString(qid),
    	            "href" -> JsString(Uri(s"/comet/$cid").resolvedAgainst(uri).toString)))
    	        }
    	      }}
    	    }
  	    }
  	  }
  	} ~
  	path("comet" / "(\\w{10,20})".r ) { clid =>
  	  get {
  	    cometManager ! Poll(clid, _)
  	  }
  	} ~
  	path("comet" / "(\\w{10,20})".r ) { clid =>
  	  delete {
  		  cometManager ! DeregisterAll(clid)
  		  complete("")
  	  }
  	} ~  	
  	path("comet" / "(\\w{10,20})".r / "(\\w{5,10})".r) { (clid,qid) =>
  	  delete {
  	    cometManager ! Deregister(clid,qid)
  	    complete("")
  	  }
  	} ~
  	path("sources") {
  	  get {
    	  import load.Messages._
    	  implicit val timeout = Timeout(5 seconds)
    	  onSuccess(logManager ? GetRegistered) { _ match {
    	    case RegisteredLogfiles(ls) => respondWithStatus(StatusCodes.Found) { 
    	      complete {
    	        ls.map { l =>
    	          Map("file" -> l.file,
    	              "id" -> hash(l.file)) ++ 
      	          l.labels ++ 
      	          Map("nextRead" -> l.scheduled.timeLeft.toSeconds.toString) ++ 
      	          l.recentState.map(s => Map("status" -> s.state, "since" -> s.occurences.toString)).getOrElse(Map())
    	        }.toJson.prettyPrint
      	    }
    	    }
    	    case _ => respondWithStatus(StatusCodes.InternalServerError) {
    	      complete("unexpexted result")
    	    }
    	  }}
  	  } ~
  	  put {
  	    entity(as[String]) { rq =>
  	      val f = rq.parseJson.asJsObject.fields
  	      f.get("file") match {
  	        case Some(JsString(s)) =>
  	          logManager ! RegisterLogfile(s, f.filterKeys(_ != "file").map(f => (f._1, f._2 match {case JsString(s) => s})).to[Seq]:_*)
  	          respondWithStatus(StatusCodes.Created)
  	          complete("")
  	        case _ => 
  	          respondWithStatus(StatusCodes.BadRequest)
  	          complete("")
  	      }
  	    }
  	  }
  	} ~
  	path("sources" / "(\\w*)".r) { id =>
	    delete {
	      import load.Messages._
  	    implicit val timeout = Timeout(5 seconds)
  	    onSuccess(logManager ? GetRegistered) { _ match {
  	      case RegisteredLogfiles(ls) => 
  	        ls.find(_.file.hash == id).
  	        map ( f => logManager ! UnregisterLogfile(f.file) )
  	        complete("")
	      }}
  	  }
  	} ~
    path("features") {
  	  respondWithSingletonHeader(`Cache-Control`(`no-cache`)) {
    	  detach() {
          mongoDb("logMeta").findOne(MongoDBObject("type" -> "feature")).flatMap(_.getAs[List[String]]("keys")) match {
            case Some(l) => complete(JsArray(l.map(JsString(_))))
            case _ => complete(StatusCodes.NotFound)
          }
    	  }
  	  }
    } ~
    path("features" / "\\w+".r) { feature =>
      parameter('prefix) { prefix =>
        detach() {
          val res = mongoDb("logMeta").aggregate(List(
            MongoDBObject("$match" ->  MongoDBObject("type" -> "featureValue", "key" -> feature)),
            MongoDBObject("$project" -> MongoDBObject("values" -> 1)),
            MongoDBObject("$unwind" -> "$values"),
            MongoDBObject("$match" -> MongoDBObject("values" -> (s"""(?i)^${prefix}.*""").r)),
            MongoDBObject("$limit" -> 12)
         ), AggregationOptions(AggregationOptions.CURSOR)).toList
         respondWithSingletonHeader(`Cache-Control`(`no-cache`)) {
          	complete(JsArray(res.flatMap(_.getAs[String]("values")).map(JsString(_))))
          } 
        }
      }
    } ~ 
    path("features" / "\\w+".r) { feature =>
      respondWithSingletonHeader(`Cache-Control`(`no-cache`)) {
        detach() {
          val values = mongoDb("logMeta").findOne(MongoDBObject("type" -> "featureValue", "key" -> feature)).flatMap(_.getAs[List[String]]("values"))
          values match { 
            case Some(l) => complete(JsArray(l.map(JsString(_))))
            case _ => complete(StatusCodes.NotFound)
          }
        }
      }
    } ~ 
    pathPrefix("app") { 
      import RunMark._
      import spray.http.HttpHeaders._
      import CacheDirectives._
      unmatchedPath { path => 
        conditional(etag(path), lastModified) { 
          getFromResource("de/alog/app" + path)
        }
      }
    }
    
}

import Helpers._
import spray.http.Uri.Path

object RunMark {
  private val mark = scala.util.Random.nextString(8)
  val lastModified = spray.http.DateTime.now
  def etag(p:Path) = EntityTag(hash(mark + p.toString))  
}

