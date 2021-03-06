package nodescala

import scala.language.postfixOps
import scala.util.{Try, Success, Failure}
import scala.collection._
import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.async.Async.{async, await}
import org.scalatest._
import NodeScala._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class NodeScalaSuite extends FunSuite {

  test("A Future should always be completed") {
    val always = Future.always(517)

    assert(Await.result(always, 0 nanos) == 517)
  }
  test("A Future should never be completed") {
    val never = Future.never[Int]

    try {
      Await.result(never, 1 second)
      assert(false)
    } catch {
      case t: TimeoutException => // ok!
    }
  }

  test("Future all must return all") {
    val f1 = Future.always(1)
    val f2 = Future.always(2)

    val list = Future.all(List(f1,f2))

    val listRes = Await.result(list, 1 seconds)
    val listExpected = List(1,2)

    assert(listRes.size == 2)
    assert(listRes.equals(listExpected))
  }

  test("Future any can return the first finished") {
    val f1 = Future.always(1)
    val fNever = Future.never

    Future.any(List(f1, fNever)) onComplete {
      case Success(v) => assert(v == 1)
      case _ => assert(false)
    }
  }

  test("Future delays must be handled") {
    val delay = Future.delay(1 seconds)

    Await.ready(delay, 3 seconds)
  }

  test("A Future should not complete after 2s when using a delay of 5s") {
    try {
      val p = Future.delay(5 second)
      val z = Await.ready(p, 2 second) // block for future to complete
      assert(false)
    } catch {
      case _: TimeoutException => // Ok!
    }
  }

  test("A Future should not complete after 1s when using a delay of 3s") {
    val p = Promise[Unit]()

    Future {
      blocking {
        Future.delay(3 second) onSuccess {
          case _ => p.complete(Try(()))
        }
      }
    }

    try {
      Await.result(p.future, 1 second)
      assert(false)
    } catch {
      case t: TimeoutException => // ok!
    }
  }

  test("Future always can return now") {
    val f = Future.always(1)

    val res = f.now

    assert(res == 1)
  }

  test("Future never can not return from now") {
    val f = Future.never

    try{
      val res = f.now
      assert(false)
    }
    catch {
      case _: NoSuchElementException => //ok
    }
  }

  class DummyExchange(val request: Request) extends Exchange {
    @volatile var response = ""
    val loaded = Promise[String]()
    def write(s: String) {
      response += s
    }
    def close() {
      loaded.success(response)
    }
  }

  class DummyListener(val port: Int, val relativePath: String) extends NodeScala.Listener {
    self =>

    @volatile private var started = false
    var handler: Exchange => Unit = null

    def createContext(h: Exchange => Unit) = this.synchronized {
      assert(started, "is server started?")
      handler = h
    }

    def removeContext() = this.synchronized {
      assert(started, "is server started?")
      handler = null
    }

    def start() = self.synchronized {
      started = true
      new Subscription {
        def unsubscribe() = self.synchronized {
          started = false
        }
      }
    }

    def emit(req: Request) = {
      val exchange = new DummyExchange(req)
      if (handler != null) handler(exchange)
      exchange
    }
  }

  class DummyServer(val port: Int) extends NodeScala {
    self =>
    val listeners = mutable.Map[String, DummyListener]()

    def createListener(relativePath: String) = {
      val l = new DummyListener(port, relativePath)
      listeners(relativePath) = l
      l
    }

    def emit(relativePath: String, req: Request) = this.synchronized {
      val l = listeners(relativePath)
      l.emit(req)
    }
  }
  test("Server should serve requests") {
    val dummy = new DummyServer(8191)
    val dummySubscription = dummy.start("/testDir") {
      request => for (kv <- request.iterator) yield (kv + "\n").toString
    }

    // wait until server is really installed
    Thread.sleep(500)

    def test(req: Request) {
      val webpage = dummy.emit("/testDir", req)
      val content = Await.result(webpage.loaded.future, 1 second)
      val expected = (for (kv <- req.iterator) yield (kv + "\n").toString).mkString
      assert(content == expected, s"'$content' vs. '$expected'")
    }

    test(immutable.Map("StrangeRequest" -> List("Does it work?")))
    test(immutable.Map("StrangeRequest" -> List("It works!")))
    test(immutable.Map("WorksForThree" -> List("Always works. Trust me.")))

    dummySubscription.unsubscribe()
  }

}




