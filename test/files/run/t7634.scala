import java.io.File
import scala.tools.partest.ReplTest
import scala.util.Properties.propOrElse

object Test extends ReplTest {
  def java = propOrElse("javacmd", "java")
  println(s"java = $java")
  def code = s""":sh $java -classpath $testOutput hello.Hello
                |.lines""".stripMargin
  println(s"code = $code")
}

package hello {
  object Hello {
    def main(a: Array[String]) {
      System.out.println("shello, world.")
    }
  }
}

