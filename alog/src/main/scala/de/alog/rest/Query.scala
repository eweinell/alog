package de.alog
package rest;

import load.Messages.RegisterLogfile
import util.Helpers
import akka.actor._
import akka.event.LoggingAdapter
import akka.dispatch.Futures
import akka.util.Timeout
import akka.pattern.ask
import spray.routing._
import spray.json._
import spray.http.StatusCodes
import spray.http.MediaTypes._
import spray.httpx.SprayJsonSupport
import DefaultJsonProtocol._
import org.joda.time.{DateTime, Period}
import redis._
import api._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import de.alog.util.LogDatabase
import scala.concurrent.Future

class QueryService(mgr:ActorRef) extends Actor with QueryServiceDeps with QueryServiceLike with LogDatabase with ActorLogging {

  def actorRefFactory = context
  def logManager=mgr
  def receive = runRoute(route)
  
}

trait QueryServiceDeps extends LogQueryHelperDeps {
   def logManager:ActorRef
}

trait QueryServiceLike extends HttpService with Helpers with SprayJsonSupport with LogQueryHelper {
  self: QueryServiceDeps =>
    
  private def completeWith(baseInfo:List[JsField])(value: => Future[JsField]) = 
    complete {
     value.map(r => JsObject(baseInfo :+ r)) 
    }
  
	val route = 
	  path("log" / "entries" ) {
	    parameters('qoffset.as[Long] ? 0l, 'qlimit.as[Long] ? 1000l) { (qoffset, qlimit) =>
  	    parameterMap { params => 
    	    respondWithMediaType(`application/json`) {
    	      val helper = QueryHelper(params, Some((qoffset, qlimit)))
    	      implicit def d2s: DateTime => String = util.DateTime.dateTimeFmt.print _
    	      val baseInfo:List[JsField] = ("query" -> JsString(helper.queryIdentifier))::("start" -> JsString(helper.rangeStart))::("end" -> JsString(helper.rangeEnd))::Nil
      	    parameter("qdetail" ! "id") {
        	    completeWith(baseInfo)(getIds(helper, r => ("id" -> JsArray(r.map(JsString(_)):_*))))
      	    } ~
      	    parameter("qdetail" ! "count") {
      	      completeWith(baseInfo)(getCard(helper, r => ("count" -> JsNumber(r))))
      	    } ~
      	    parameter("qdetail" ! "meta") {
      	      completeWith(baseInfo)(getMeta(helper, r => ("meta" -> JsArray(r: _*))))
      	    } ~
      	    parameter("qdetail" ! "full") {
      	      completeWith(baseInfo)(getFull(helper, r => ("full" -> JsArray(r: _*))))
      	    }
  	      }
  	    }
	    }
  	} ~
  	path("log" / "entries" / "(\\w{32,40})".r ) { id =>
  		get {
  		  val key = id.toUpperCase
  		  val f = getElement(key)
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
  //		  parameter('app) { (app) => 
  //		   complete(s"'app'")
  //		  }
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
      respondWithMediaType(`application/json`) { complete {
        redisDb.smembers[String]("feature.keys").map(s => JsArray(s.map(JsString(_)): _*))
      }} 
    } ~
    path("features" / "\\w+".r) { feature =>
      respondWithMediaType(`application/json`) { complete {
        redisDb.smembers[String](s"feature.${feature}.values").map(s => JsArray(s.map(JsString(_)): _*))
      }}
    }
	
}
