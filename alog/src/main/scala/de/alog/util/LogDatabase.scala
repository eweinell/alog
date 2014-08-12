package de.alog.util

import akka.actor.Actor
import redis.RedisClient
import com.mongodb.casbah.Imports._

trait LogDatabase {
  self: Actor =>
  
  private val config = context.system.settings.config
  
  lazy val redisDb:RedisClient = RedisClient(
      host=config.getString("redis.database_host"),
      port=config.getInt("redis.database_port")
  )(context.system)
  
  lazy val mongoDb:MongoDB = createMongoDbConn
  
  private def createMongoDbConn = {
    val db = MongoClient(
      host=config.getString("mongo.database_host"),
      port=config.getInt("mongo.database_port")
    )(config.getString("mongo.database_name"))
    db("logEntries").ensureIndex("timestamp")
    db("logEntries").ensureIndex(MongoDBObject("message" -> "text"))
    db
  }
  
  import com.mongodb.casbah.commons.conversions.scala._
  RegisterJodaTimeConversionHelpers()
  
}