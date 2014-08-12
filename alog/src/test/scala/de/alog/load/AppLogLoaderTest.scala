package de.alog.load

import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import org.junit.runner.RunWith

import spray.json._
import DefaultJsonProtocol._

@RunWith(classOf[JUnitRunner])
class AppLogLoaderSpecs extends Specification {
  
  "JsonParser" should {
  	"parse json value" in {
  	  val js:JsValue = """
				{
					"a": "a1",
					"b": ["b1", "b2"],
					"c": { "c1": "c11", "c2": "c21" } 
				}
			""".parseJson
			
			val o = js match {
  	    case JsObject(f) => Some(f)
  	    case _ => None
  	  }
			o must_!= null
//  	  val f = AppLogLoader.readLines("C:/Users/erhard/tmp/logstuff/console_VorgangsverwaltungServiceProviderEAR.log").toSeq
//  	  f.length must_== 11434
  	}
  }
}

object Test extends App {
    	  val js:JsValue = """
				[{
					"a": "a1",
					"b": ["b1", "b2"],
					"c": { "c1": "c11", "c2": "c21" } 
				}]
			""".parseJson
			val o = js.asJsObject("") 
			
			val x = Map("a" -> 1, "b" -> 2)
}