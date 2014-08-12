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
class LogParserSpecs extends Specification with NoTimeConversions {
  
  "LogParser" should {
  	"parse sysout message" in new AkkaSpecs2Support {
  	  val rcv = TestProbe()
  	  val parserActor = system.actorOf(Props(classOf[LogParser], rcv.ref))
  	  
  	  val test = """[8/10/14 5:48:03:845 CEST] 0000007c PropertiesFac I org.springframework.core.io.support.PropertiesLoaderSupport loadProperties Loading properties file from class path resource [internal/x.properties]"""
  	  parserActor ! RawMessage(Map(), Seq(test), None)
  	  rcv.expectMsgPF[Boolean](1 second) {
  	    case LogEntries(Seq(LogEntry(t,f,m)), _) => true
  	  } must_== true
  	}
  }
  
  "LogParser" should {
    "parse multi-line logs correctly" in new AkkaSpecs2Support {
      val test = """[9/8/14 5:41:15:504 CEST] 00000001 WsServerImpl  E   WSVR0009E: Error occurred during startup
                  |com.ibm.ws.exception.RuntimeError: com.ibm.ejs.EJSException: Could not register with Location Service Daemon, which could only reside in the NodeAgent. Make sure the NodeAgent for this node is up and running.; nested exception is: 
                  |	org.omg.CORBA.ORBPackage.InvalidName: LocationService:org.omg.CORBA.TRANSIENT: java.net.ConnectException: Connection refused:host=hostname,port=31109  vmcid: IBM  minor code: E02  completed: No
                  |	at com.ibm.ws.runtime.component.ORBImpl.start(ORBImpl.java:485)
                  |	at com.ibm.ws.runtime.component.ContainerHelper.startComponents(ContainerHelper.java:539)
                  |	at com.ibm.ws.runtime.component.ContainerImpl.startComponents(ContainerImpl.java:627)
                  |	at com.ibm.ws.runtime.component.ContainerImpl.start(ContainerImpl.java:618)
                  |	at com.ibm.ws.runtime.component.ServerImpl.start(ServerImpl.java:523)
                  |	at com.ibm.ws.runtime.WsServerImpl.bootServerContainer(WsServerImpl.java:310)
                  |	at com.ibm.ws.runtime.WsServerImpl.start(WsServerImpl.java:223)
                  |	at com.ibm.ws.runtime.WsServerImpl.main(WsServerImpl.java:686)""".stripMargin('|')
      
      val rcv = TestProbe()
  	  val parserActor = system.actorOf(Props(classOf[LogParser], rcv.ref))
  	  parserActor ! RawMessage(Map(), test.lines.toSeq, None)
      rcv.expectMsgPF[Boolean](1 second) {
  	    case LogEntries(Seq(LogEntry(t,f,m)), _) if m.length > 100 => true
  	  } must_== true
    }
  }
  
   "AppServDateTime" should {
    "parse syslog datetime" in {
      AppServDateTime.unapply("8/10/14 5:48:03:845 CEST") match {
        case Some(d) => {
          d.getDayOfMonth() must_==10
          d.getMonthOfYear() must_==8
          d.getYear() must_==2014
          d.getHourOfDay() must_==5
          d.getMillisOfSecond() must_==845
        }
      }
      AppServDateTime.unapply("8/10/14 5:48:03:845 CET") match {
        case Some(d) => {
          d.getHourOfDay() must_==5
        }
      }
    }
  }
}
