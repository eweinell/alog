package de.alog

import load.AppLogLoader
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

object Proto extends App {
  
	implicit val sys = ActorSystem("alog", ConfigFactory.load())
	val persist_ = sys actorOf (RoundRobinPool(1).props(Props[persist.LogPersister]), "persist")
	val transform_ = sys actorOf (RoundRobinPool(1).props(Props(classOf[parser.LogParser], persist_)), "transform") 
	val loadRouter = sys actorOf (BroadcastPool(1).props(AppLogLoader.props(transform_)), "loadRouter")
	val loadManager = sys actorOf (Props(classOf[load.AppLogManager], loadRouter), "loadManager")
	val srcmgr = sys actorOf (Props(classOf[load.PersistentSourceManager], loadManager), "persistSource")
	
	val queryService = sys actorOf (Props(classOf[QueryService], loadManager), "queryService")
	IO(Http) ! Http.Bind(queryService, "localhost", port = 8080)

//	loadManager ! RegisterLogfile("C:\\Users\\erhard\\tmp\\logstuff\\console_VorgangsverwaltungServiceProviderEAR.log", "zelle" -> "local", "host"-> "localhost")

	val logs = listFiles(new File("C:\\Users\\erhard\\tmp\\logstuff")).toSeq.filter(_.isFile()).filter(_.getName().matches(".*\\.log(\\.\\d)?")).filter(_.getName.startsWith("console_"))
	for (l <- logs) yield {
	  val t = ".*_".r.findFirstIn(l.getName).map(_.stripSuffix("_")).getOrElse(l.getName.replaceAll("\\.log(\\.\\d)?", ""))
	  loadManager ! RegisterLogfile(l.getAbsolutePath, "zelle" -> "local", "type"-> t)
	}
	
	def listFiles(start:File): Array[File] = {
	  val loc = start.listFiles
	  loc ++ loc.filter(_.isDirectory()).flatMap(listFiles(_))
	}
	
}