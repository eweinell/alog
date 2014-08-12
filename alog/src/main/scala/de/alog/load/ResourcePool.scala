package de.alog
package load

import akka.actor._
import akka.dispatch._
import akka.pattern.pipe
import scala.io._
import scala.collection._
import scala.concurrent._
import duration._
import java.net.URI
import scala.util.Try
import java.util.UUID
import akka.util.Helpers
import scala.util.Random
import scala.beans.BeanProperty

class ResourcePool(factory: () => Resource) extends Actor with ResourcePoolMBean with ActorLogging {
  
  val resourceTTL = 30 seconds
  val idleTTL = 10 seconds
  
  @BeanProperty var limit = 16
  
  private[load] val resourcePool = mutable.Queue[ResourceEntry]()
  
  var maxUse = 0;

  context.system.scheduler.schedule(idleTTL.plus(5 seconds), 2 seconds, self, CleanupTick)(context.system.dispatcher)
  context.system.scheduler.schedule(2 seconds, 2 seconds, self, Info)(context.system.dispatcher)
  case object Info
  
  def receive = {
    case ResourceRequest(uri) => 
      val requestor = sender
      
      implicit val ec = context.system.dispatchers.defaultGlobalDispatcher
      val recentFreed = resourcePool.reverseIterator.find(e => e.resource.compatible(uri) && !e.checkedOut)
      val r: Option[Future[Resource]] = resourcePool.dequeueFirst(e => recentFreed.map(e==).getOrElse(false)).map { r =>
        r.resource.uri$ = Some(uri)
        // passende Ressource vorhanden & verfügbar
        resourcePool.enqueue(ResourceEntry(r.resource, true, resourceTTL fromNow))
        log.debug(s"re-using resource ${r.resource.uid} for ${uri}")
        Future {
          r.resource.setup; 
          r.resource
        }
      } orElse { 
      	// Keine passende Ressource
        case object Good
        (resourcePool match {
          // Pool ist voll, leeren falls möglich
          case r if r.length >= limit => resourcePool.dequeueFirst(!_.checkedOut).map{r => Future{ r.resource.shutdown }; Good}
          // Pool ist nicht voll
          case _ => Some(Good)
        }).map { _ =>
            val res = factory()
            res.uri$ = Some(uri)
            log.debug(s"creating resource ${res.uid} for ${uri}")
            resourcePool.enqueue(ResourceEntry(res, true, resourceTTL.fromNow))
            Future {
              res.initialize
              res.setup
            	res
            }
        }
      }
      maxUse = Math.max(maxUse, resourcePool.count(_.checkedOut))
      r.getOrElse(Futures.failed(new RuntimeException("no resource available"))).pipeTo(sender)
      
    case ResourceReturned(res) => 
       resourcePool.dequeueFirst(_.resource.uid == res.uid).map { re =>
         implicit val ec = context.system.dispatchers.defaultGlobalDispatcher
         log.debug(s"recycling resource ${res.uid}")
         Future { res.teardown }
         resourcePool.enqueue(ResourceEntry(re.resource, false, idleTTL fromNow))
       }
      
    case CleanupTick =>
      val rs = resourcePool.dequeueAll(_.ttl.isOverdue)
      if (rs.nonEmpty) {
        implicit val ec = context.system.dispatchers.defaultGlobalDispatcher
        log.debug(s"shuttind down resources ${rs.map(_.resource.uid).mkString(", ")}")
        Future {
          rs.foreach{ r => Try { if (r.checkedOut) { r.resource.teardown }; r.resource.shutdown}}
        }
      }
    case Info =>
      log.debug(s"active resoures count: ${resourcePool.length}, peak resources in use: ${maxUse}")
      maxUse=resourcePool.count(_.checkedOut)
  }  
  
  override def preStart {
    register
  }
  
}

private case class ResourceEntry(resource:Resource, checkedOut:Boolean, ttl:Deadline)

private case object CleanupTick

case class ResourceRequest(uri:URI)
case class ResourceReturned(resource:Resource)

trait Resource {
  private[load] val uid = Helpers.base64(Random.nextLong)
  private[load] var uri$:Option[URI] = None 
  
  def uri = uri$.get
  def compatible(uri:URI): Boolean
  def initialize
  def setup
  def teardown
  def shutdown
}

trait ResourcePoolMBean {
  self: ResourcePool =>
  
  def register {
    import de.alog.util.JMXHelpers._
    registerJmx(self, "AlogResourcePool:name=ResourcePool")
  }
  
  def getConnectionsInUse(): Int = maxUse
  def getActiveConnections(): Int = resourcePool.size
  def getLimit(): Int
  def setLimit(limit:Int): Unit
  
}
