package de.alog.testutil

import org.specs2.mutable._
import org.specs2.Specification
import org.specs2.time.NoTimeConversions
import akka.testkit._
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

trait ActorSpec extends Specification with NoTimeConversions {
}

abstract class AkkaSpecs2Support extends TestKit(ActorSystem("alog", ConfigFactory.load())) with After with ImplicitSender {
  def after = system.shutdown()
}