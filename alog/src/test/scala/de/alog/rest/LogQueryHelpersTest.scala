package de.alog.rest

import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import org.junit.runner.RunWith
import org.joda.time.Period
import spray.testkit.Specs2RouteTest
import spray.routing._
import directives._
import de.alog.rest.QueryServiceLike
import de.alog.rest.QueryServiceDeps

@RunWith(classOf[JUnitRunner])
class LogQueryHelpersTest extends Specification with Specs2RouteTest with HttpService {

  def actorRefFactory = system
	val route =
		parameters('color, 'count.as[Int] ? 23) { (color, count) =>
		complete(s"The color is '$color' and you have $count of it.")
  }
  
  "route parser" should {
    "get given values" in {
      Get("/?color=blue&count=42") ~> route ~> check {
        responseAs[String] must_== "The color is 'blue' and you have 42 of it."
      }    
    }
    "get default values" in {
      Get("/?color=blue") ~> route ~> check {
        responseAs[String] must_== "The color is 'blue' and you have 23 of it."
      }    
    }
  }
  
}