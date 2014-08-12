package de.alog.parser

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

@RunWith(classOf[JUnitRunner])
class LdapParserSpecs extends Specification with NoTimeConversions {
  
  "LdapParser" should {
    "parse timestamp" in {
      val dt = LdapDateTime.unapply("2014-09-23-13:31:30.755+00:00DST")
      dt
        .filter(_.dayOfMonth.get must_== 23)
        .filter(_.monthOfYear.get must_== 9)
        .filter(_.hourOfDay.get must_== 13)
        .isDefined must_== true        
    }
  }
  
  "LdapParser" should {
    "parse log line" in new AkkaSpecs2Support {
      val rcv = TestProbe()
      val parserActor = system.actorOf(Props(classOf[LdapParser], rcv.ref))
      
      val test = """AuditV3--2014-09-23-13:32:54.133+00:00DST--V3 Search--bindDN: uid=binduser--client: 10.232.120.252:28078--connectionID: 1712829--received: 2014-09-23-13:32:53.802+00:00DST--Success"""
      parserActor ! RawMessage(Map(), Seq(test), None)
  	  rcv.expectMsgPF[Boolean](1 second) {
  	    case LogEntries(Seq(LogEntry(t,f,m)), _) if f.size == 9 => true
  	    case x => println(x); false
  	  } must_== true      
    }
  }
  
  "LdapParser" should {
    "parse multi line log " in new AkkaSpecs2Support {
      val rcv = TestProbe()
      val parserActor = system.actorOf(Props(classOf[LdapParser], rcv.ref))
      
      val test = """AuditV3--2014-09-23-13:31:30.755+00:00DST--V3 Search--bindDN: uid=binduser--client: 10.232.120.252:3437--connectionID: 1957898--received: 2014-09-23-13:31:30.755+00:00DST--Success
										|controlType: 2.16.840.1.113730.3.4.2
										|criticality: false
										|base: o=base
										|scope: wholeSubtree
										|derefAliases: derefAlways
										|typesOnly: false
										|filter: (&(objectclass=OBJCLS)(uid=UID))
										|attributes: a, b, c, d, e""".stripMargin('|')
      parserActor ! RawMessage(Map(), test.lines.toSeq, None)
  	  rcv.expectMsgPF[Boolean](1 second) {
  	    case LogEntries(Seq(LogEntry(t,f,m)), _) if m.length > 250 => true
  	    case x => println(x); false
  	  } must_== true      
    }
  }
}