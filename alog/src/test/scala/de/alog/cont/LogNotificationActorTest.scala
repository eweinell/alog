package de.alog.cont

import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import org.junit.runner.RunWith
import akka.actor._
import akka.testkit._
import spray.json._
import DefaultJsonProtocol._
import scala.collection.mutable
import scala.concurrent.duration._
import de.alog.parser.Messages.RawMessage
import org.specs2.time.NoTimeConversions
import de.alog.persist.Messages._
import de.alog.util.AppServDateTime
import de.alog.testutil.ActorSpec
import de.alog.testutil.AkkaSpecs2Support
import de.alog.cont.Messages._
import com.typesafe.config.ConfigFactory

@RunWith(classOf[JUnitRunner])
class LogNotificationActorTest extends Specification with NoTimeConversions {
  sequential
   
  "notificationActor" should {
    "send notification" in new AkkaSpecs2Support {
      val in = "A0002C08794C2E52DDFFEFAE256B3397"
      val rcv = TestProbe()
      
      val tested = system.actorOf(Props(classOf[LogNotificationActor]), "logNotification")
      tested ! RegisterDBUpdate(Map("qduration" -> "1000d"), rcv.ref)
      tested ! AddedLogEntries(Seq(in))
      tested ! UnregisterDBUpdate(rcv.ref, Some(UnregisterQuery(Map("qduration" -> "1000d"))))
      
      rcv.expectMsgPF[Boolean](3 seconds) {
  	    case JsObject(r) => {
  	      r("ids") match {
  	        case Seq(e) => e must_== in
  	        case _ => false
  	      }
  	    }
  	  } 
    } 
  }
  
}