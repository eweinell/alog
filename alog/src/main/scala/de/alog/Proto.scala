package de.alog

import load.AppLogLoader
import load.JschLoader
import load.Messages.RegisterLogfile
import persist.LogPersister
import parser.LogParser
import rest.QueryService
import akka.actor._
import akka.routing.BroadcastPool
import akka.routing.SmallestMailboxPool
import akka.io.IO
import spray.can.Http
import com.typesafe.config.ConfigFactory
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.Path
import java.io.File
import akka.routing.RoundRobinPool
import akka.routing.ScatterGatherFirstCompletedPool
import akka.routing.BroadcastGroup
import akka.routing.BroadcastGroup
import scala.io.Source

object Proto extends App {
  
	implicit val sys = ActorSystem("alog", ConfigFactory.load())
	val persist_ = sys actorOf (Props[persist.LogPersister], "persist")
	val transform1_ =  sys actorOf (Props(classOf[parser.LogParser], persist_), "transform1") 
	val transform2_ =  sys actorOf (Props(classOf[parser.LdapParser], persist_), "transform2")
	val transform = sys actorOf(BroadcastGroup((transform2_ :: Nil).map(_.path.toStringWithoutAddress)).props(), "transform")
	val loaderSvc = sys actorOf (Props(classOf[JschLoader]), "loadBackend")
	val loadRouter = sys actorOf (BroadcastPool(2).props(AppLogLoader.props(transform, loaderSvc)), "loadRouter")
	val loadManager = sys actorOf (Props(classOf[load.AppLogManager], loadRouter), "loadManager")
	val srcmgr = sys actorOf (Props(classOf[load.PersistentSourceManager], loadManager), "persistSource")
	
	val queryService = sys actorOf (Props(classOf[QueryService], loadManager), "queryService")
	IO(Http) ! Http.Bind(queryService, "localhost", port = 8080)

	val root = new File("rootdir-of-logfiles")
	for (l <- listFiles(root).toSeq) yield {
	  loadManager ! RegisterLogfile(l.getAbsolutePath(), "type" -> "servertype", "host"->"hostname")
	}
	
	def listFiles(start:File): Array[File] = {
	  val loc = start.listFiles
	  loc ++ loc.filter(_.isDirectory()).flatMap(listFiles(_))
	}
	
}

