package scala.concurrent

import org.junit.Assert._
import org.junit.Test

import scala.tools.testkit.AssertUtil._
import scala.util.Try

class FutureStackTraces {

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
  def statusQuo(): Unit = {
    implicit val ec: ExecutionContext = ExecutionContext.global
    val f = Future(throw new RuntimeException)
    val ex = Try(await(f)).failed.get
    val lines = trimmedStackTrace(ex)
    assertEquals("java.lang.RuntimeException", lines(0))
    assertEquals("at apply @ scala.concurrent.FutureStackTraces.statusQuo(FutureStackTraces.scala:25)", lines(1))
    assertEquals("""|java.lang.RuntimeException
                    |at apply @ scala.concurrent.FutureStackTraces.statusQuo(FutureStackTraces.scala:25)
                    |at scala.concurrent.FutureStackTraces.$anonfun$statusQuo$1(FutureStackTraces.scala:25)
                    |at scala.concurrent.Future$.$anonfun$apply$1(Future.scala:674)
                    |at scala.concurrent.impl.Promise$Transformation.run(Promise.scala:433)
                    |at java.util.concurrent.ForkJoinTask$RunnableExecuteAction.exec(ForkJoinTask.java:1402)
                    |at java.util.concurrent.ForkJoinTask.doExec(ForkJoinTask.java:289)
                    |at java.util.concurrent.ForkJoinPool$WorkQueue.runTask(ForkJoinPool.java:1056)
                    |at java.util.concurrent.ForkJoinPool.runWorker(ForkJoinPool.java:1692)
                    |at java.util.concurrent.ForkJoinWorkerThread.run(ForkJoinWorkerThread.java:175)""".stripMargin,
      lines.mkString("\n"))
  }

  // TODO: test `Future#failed`

}
