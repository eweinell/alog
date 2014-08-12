package de.alog.util

import akka.actor._
import com.mongodb.casbah.Imports._
import com.typesafe.config.Config

class LogDatabaseImpl(config: Config) extends Extension {
  
  lazy val mongoDb:MongoDB = createMongoDbConn
  
  private def createMongoDbConn = {
  	import com.mongodb.casbah.commons.conversions.scala._
	  RegisterJodaTimeConversionHelpers()
  	
    val host = config.getString("mongo.database_host")
    val port = config.getInt("mongo.database_port")
    val dbn = config.getString("mongo.database_name")
    
    val db = MongoClient(
      host=host,
      port=port
    )(dbn)
    db("logEntries").ensureIndex("timestamp")
    db("logEntries").ensureIndex(MongoDBObject("message" -> "text"))
    db("logMeta").ensureIndex("key")
    db("logMeta").ensureIndex("type")
    db
  }
}

object LogDatabaseExtension extends ExtensionId[LogDatabaseImpl] with ExtensionIdProvider {
  
  def createExtension(system: ExtendedActorSystem): LogDatabaseImpl = new LogDatabaseImpl(system.settings.config)

  def lookup = LogDatabaseExtension 
  
}

trait LogDatabase {
  self: Actor =>
    
  def mongoDb = LogDatabaseExtension(context.system).mongoDb
  def logEntriesDb = mongoDb("logEntries")
}