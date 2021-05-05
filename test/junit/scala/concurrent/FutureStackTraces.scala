package scala.concurrent

import org.junit.Assert._
import org.junit.Test
import scala.util.Try

class FutureStackTraces {

  implicit val ec: ExecutionContext = ExecutionContext.global

  def await[T](f: Future[T]): T =
    Await.result(f, duration.Duration.Inf)

  def stackTraceString(t: Throwable): String = {
    val sw = new java.io.StringWriter
    val out = new java.io.PrintWriter(sw)
    t.printStackTrace(out)
    out.close()
    sw.toString
  }

  def throws = throw new RuntimeException // line 22

  @Test
  def testApply(): Unit = {
    val f = Future {                      // line 26
      throws                              // line 27
    }
    val ex = Try(await(f)).failed.get
    assertEquals("""|java.lang.RuntimeException
                    |	at scala.concurrent.FutureStackTraces.throws(FutureStackTraces.scala:22)
                    |	at scala.concurrent.FutureStackTraces.$anonfun$testApply$1(FutureStackTraces.scala:27)
                    |	at apply @ scala.concurrent.FutureStackTraces.testApply(FutureStackTraces.scala:26)
                    |""".stripMargin,
      stackTraceString(ex))
  }

  @Test
  def testMapAndFlatMap(): Unit = {
    val f =
      Future(0)                          // line 41
        .map(_ + 1)                      // line 42
        .flatMap(n =>                    // line 43
          Future(n * 2))                 // line 44
        .map(_ =>                        // line 45
          throws)                        // line 46
    val ex = Try(await(f)).failed.get
    ex.printStackTrace()
    assertEquals("""|java.lang.RuntimeException
                    |	at scala.concurrent.FutureStackTraces.throws(FutureStackTraces.scala:22)
                    |	at scala.concurrent.FutureStackTraces.$anonfun$testMapAndFlatMap$5(FutureStackTraces.scala:46)
                    |	at scala.concurrent.FutureStackTraces.$anonfun$testMapAndFlatMap$5$adapted(FutureStackTraces.scala:45)
                    |	at map @ scala.concurrent.FutureStackTraces.testMapAndFlatMap(FutureStackTraces.scala:45)
                    |	at flatMap @ scala.concurrent.FutureStackTraces.testMapAndFlatMap(FutureStackTraces.scala:43)
                    |	at map @ scala.concurrent.FutureStackTraces.testMapAndFlatMap(FutureStackTraces.scala:42)
                    |	at apply @ scala.concurrent.FutureStackTraces.testMapAndFlatMap(FutureStackTraces.scala:41)
                    |""".stripMargin,
      stackTraceString(ex))
  }

  // TODO: other examples at https://github.com/jrudolph/future-exception-aspects/blob/master/Expected.md

}
