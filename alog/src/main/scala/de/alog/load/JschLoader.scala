package de.alog
package load

import akka.actor._
import akka.dispatch._
import akka.pattern.{pipe,ask}
import akka.util.Timeout
import Timeout._
import scala.io._
import scala.collection._
import scala.concurrent.duration._
import java.net.URI
import com.jcraft.jsch._
import scala.util.Try
import java.util.UUID
import java.io.InputStream
import java.io.OutputStream
import com.typesafe.config.Config
import scala.concurrent.Future
import JschResource._
import scala.concurrent.ExecutionContext
import java.io._

class JschLoader extends Actor with ActorLogging {

  val cfg = context.system.settings.config.getConfig("alog.loader.scp")
	val resourceFactory = () => new JschResource(cfg)
	
  val pool = context.actorOf(Props(classOf[ResourcePool], resourceFactory))
  
  def receive = {
     case LoadRequest(u,c) => {
       implicit val t = Timeout(6 seconds)
       implicit val ec = context.system.dispatcher
       pool ? ResourceRequest(u) flatMap { 
         case res : JschResource => loadResource(res,c).andThen { case _ => pool ! ResourceReturned(res) } 
         case _ => Futures.failed(new RuntimeException("unexpected result from request pool"))
       } map (LoadResult(_)) pipeTo sender 
     } 
  }
	
	def loadResource(r:JschResource, c:Codec)(implicit ec:ExecutionContext): Future[Seq[String]] = {
	  r.run.map { 
	    case RunState(in,out,_) => Future {
	      def poke = { out.write(0.toByte); out.flush }
	      val buf = new Array[Byte](256)
	      poke
	      if (in.read(buf) > 0 && buf(0).toChar == 'C') { // read state;mode;length;filename
	        poke
	        val bytestream = Stream.continually(in.read).takeWhile(_ > 0).map(_.toByte).toArray
	        val res = Source.fromBytes(bytestream)(c).getLines.toSeq
	        res
	      } else {
	        throw new RuntimeException("illegal reply from ssh channel")
	      }
	    }
	  }.getOrElse(Futures.failed(new IllegalStateException("secure shell not running")))
	}
	
}

case class LoadRequest(uri:URI, code:Codec)
case class LoadResult(lines:Seq[String])

private[load] class JschResource(cfg:Config) extends Resource {
  
  var run: Option[RunState] = None
  var conn: Option[ConnState] = None


  def initialize: Unit = {
    val UserPass = "([^:]+):([^:]+)".r
    val (host,port) = (uri.getHost, uri.getPort match { case -1 => 22; case i => i  }) 
    val (user,pass) = uri.getUserInfo match {
      case UserPass(u,p) => (u,p)
      case u => (u, cfg.getString(s""""$u@$host:$port""""))
    }
    val sshSession = new JSch().getSession(user, host, port)
    sshSession.setUserInfo(ScpUser(user, pass))
    sshSession.setTimeout(sslTimeout.toMillis.toInt)
    sshSession.connect()
    conn = Some(ConnState(sshSession))
  }

  def setup: Unit = {
    run = conn.map { c => 
      val chl = c.session.openChannel("exec")
      chl.asInstanceOf[ChannelExec].setCommand("scp -f " + uri.getPath)
      val res = RunState(chl.getInputStream, chl.getOutputStream, chl)
      chl.connect
      res
    }
  }

  def teardown {
    run.map { r =>
      r.in.close
      r.out.close
      r.channel.disconnect
    }
    run = None
  }

  def shutdown {
  	conn.map { _.session.disconnect }
  	conn = None
  }

  def compatible(uri: URI): Boolean = 
    this.uri.getScheme == uri.getScheme && 
      this.uri.getHost == uri.getHost && 
      this.uri.getPort == uri.getPort && 
      this.uri.getUserInfo == uri.getUserInfo
}

private[load] object JschResource {
  
  val sslTimeout = 3 seconds
    
  case class RunState(in:InputStream, out:OutputStream, channel:Channel)
  case class ConnState(session:Session)
  case class ScpUser(user:String, pass:String) extends UserInfo {
    def getPassphrase = null
    def getPassword = pass
    def promptPassword(message:String) = true
    def promptPassphrase(message:String) = true
    def promptYesNo(message:String) = true
    def showMessage(message:String) {}
  }
  
}