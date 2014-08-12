package de.alog.cont

import de.alog.rest.QueryHelper
import spray.http.HttpRequest
import akka.actor.ActorRef
import spray.routing.RequestContext

package object Messages {

  type Query = Map[String,String]
  type QueryId = String
  type ClientId = String
  
  case class Register(query:Query, clId:Option[ClientId]=None, qId:Option[QueryId]=None)
  case class RegisterResult(clId:String, qId:String)
  case class Deregister(clId:ClientId, qId:String)
  case class DeregisterAll(clId:ClientId)
  case class Poll(clId:ClientId, req:RequestContext)
  
  case class AddedLogEntries(entryIds:Seq[String])
  case class RegisterDBUpdate(query:Query, notifyTo:ActorRef, queryId:Option[QueryId]=None)
  case class UnregisterDBUpdate(notifyTo:ActorRef, unreg:Option[UnregisterClassifier])
  trait UnregisterClassifier
  case class UnregisterQuery(query:Query) extends UnregisterClassifier
  case class UnregisterQueryId(queryId:QueryId) extends UnregisterClassifier
}