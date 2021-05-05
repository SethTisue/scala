package scala.concurrent

import org.junit.Assert._
import org.junit.Test
import scala.util.Try

class FutureStackTraces {

  implicit val ec: ExecutionContext = ExecutionContext.global

  def await[T](f: Future[T]): T =
    Await.result(f, duration.Duration.Inf)

  def trimmedStackTrace(t: Throwable): List[String] = {
    val sw = new java.io.StringWriter
    val out = new java.io.PrintWriter(sw)
    t.printStackTrace(out)
    out.close()
    sw.toString.linesIterator.map(_.trim).toList
  }

  @Test
  def testApply(): Unit = {
    def throws = throw new RuntimeException
    val f = Future {  // line 24
      throws          // line 25
    }                 // line 26
    val ex = Try(await(f)).failed.get
    val lines = trimmedStackTrace(ex)
    assertEquals("java.lang.RuntimeException", lines(0))
    assertEquals("""|java.lang.RuntimeException
                    |at scala.concurrent.FutureStackTraces.throws$1(FutureStackTraces.scala:24)
                    |at scala.concurrent.FutureStackTraces.$anonfun$testApply$1(FutureStackTraces.scala:26)
                    |at apply @ scala.concurrent.FutureStackTraces.testApply(FutureStackTraces.scala:25)""".stripMargin,
      lines.mkString("\n"))
  }

  // TODO: other examples at https://github.com/jrudolph/future-exception-aspects/blob/master/Expected.md

}
