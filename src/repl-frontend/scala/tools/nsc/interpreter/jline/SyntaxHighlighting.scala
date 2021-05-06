/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala.tools.nsc
package interpreter
package jline

// credits: there is considerable borrowing here from
// https://github.com/lampepfl/dotty/blob/master/compiler/src/dotty/tools/dotc/printing/SyntaxHighlighting.scala
// (which in turn may have some borrowing from Ammonite?)

import ast.parser.Tokens
import scala.io.AnsiColor

object SyntaxHighlighting {

  val         NoColor: String = AnsiColor.RESET
  val    KeywordColor: String = AnsiColor.YELLOW
  val    LiteralColor: String = AnsiColor.RED
  val    CommentColor: String = AnsiColor.BLUE      // TODO
  val     ValDefColor: String = AnsiColor.CYAN      // TODO; needs parsing
  val       TypeColor: String = AnsiColor.MAGENTA   // TODO; needs parsing
  val AnnotationColor: String = AnsiColor.MAGENTA   // TODO? Ammonite does, Scala 3 doesn't

  def highlight(in: String, tokens: List[TokenData]): String = {
    val colorAt = Array.fill(in.length)(NoColor)
    def highlightRange(from: Int, to: Int, color: String) =
      java.util.Arrays.fill(colorAt.asInstanceOf[Array[AnyRef]], from, to, color)
    for (tokenData <- tokens)
      if (tokenData.start >= 0 && tokenData.end < in.length && tokenData.end > tokenData.start)
        highlightRange(tokenData.start, tokenData.end, color(tokenData.token))
    ansify(in, colorAt)
  }

  def ansify(in: String, colorAt: Array[String]): String = {
    val highlighted = new StringBuilder
    for (idx <- colorAt.indices) {
      val prev = if (idx == 0) NoColor else colorAt(idx - 1)
      val curr = colorAt(idx)
      if (curr != prev)
        highlighted.append(curr)
      highlighted.append(in(idx))
    }
    if (colorAt.lastOption != Some(NoColor))
      highlighted.append(NoColor)
    highlighted.toString
  }

  def color(token: Int): String =
    if (Tokens.isLiteral(token) || token == Tokens.TRUE || token == Tokens.FALSE || token == Tokens.NULL)
      LiteralColor
    else if (isKeyword(token))
      KeywordColor
    else
      NoColor

  // I couldn't find a source of truth we could use for this
  val isKeyword = {
    import Tokens._
    Set[Int](
      ABSTRACT, ARROW, AT, CASE, CATCH, CLASS, DEF, DO, ELSE, EQUALS, EXTENDS, FINAL, FINALLY, FOR,
      FORSOME, HASH, IF, IMPLICIT, IMPORT, LARROW, LAZY, MATCH, NEW, OBJECT, OVERRIDE, PACKAGE,
      PRIVATE, PROTECTED, RETURN, SEALED, SUBTYPE, SUPER, SUPERTYPE, THIS, THROW, TRAIT, TRY, TYPE,
      VAL, VAR, VIEWBOUND, WHILE, WITH, YIELD,
    )
  }

}
