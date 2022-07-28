/*
 * Scala classfile decoder (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala
package tools.scalap

import java.util._


/** a parser class for parsing meta type information in classfiles
 *  generated by pico.
 */
class MetaParser(meta: String) {
  val scanner = new StringTokenizer(meta, "()[], \t<;", true)
  var token: String = _
  val res = new StringBuffer

  private def nextToken: String = {
    do {
      token = scanner.nextToken().trim()
    } while (token.length() == 0)
    token
  }

  protected def parseType(): Unit = {
    if (token startsWith "?")
      res.append(token.substring(1))
    else
      res.append(token)
    nextToken
    if (token == "[") {
      do {
        res.append(if (token == ",") ", " else "[")
        nextToken
        parseType()
      } while (token == ",")
      nextToken
      res.append("]")
    }
    ()
  }

  def parse: Option[String] =
    if (scanner.hasMoreTokens()) {
      nextToken
      try {
        if (!scanner.hasMoreTokens())
          None
        else if (token == "class")
          Some(parseMetaClass)
        else if (token == "method")
          Some(parseMetaMethod)
        else if (token == "field")
          Some(parseMetaField)
        else if (token == "constr")
          Some(parseConstrField)
        else
          None
      } catch {
        case _: Exception => None
      }
    } else
      None

  protected def parseMetaClass: String = {
    nextToken
    if (token == "[") {
      do {
        res.append(if (token == "[") "[" else ", ")
        nextToken
        if (token == "+") {
          nextToken
          res.append('+')
        } else if (token == "-") {
          nextToken
          res.append('-')
        }
        res.append(token.substring(1))
        nextToken
        if (token == "<") {
          nextToken
          res.append(" <: ")
          parseType()
        }
      } while (token == ",")
      nextToken
      res.append("]")
    }
    if (token == "extends") {
      do {
        if (token == "extends")
          res.append(" extends ")
        else
          res.append(" with ")
        nextToken
        parseType()
      } while (token == "with")
    }
    res.toString()
  }

  protected def parseMetaMethod: String = {
    nextToken
    if (token == "[") {
      nextToken
      if (token == "]") {
        nextToken
      } else {
        var loop = true
        res.append("[")
        while (loop) {
          res.append(token.substring(1))
          nextToken
          if (token == "<") {
            nextToken
            res.append(" <: ")
            parseType()
          }
          if (token == ",") {
            nextToken
            res.append(", ")
          } else
            loop = false
        }
        nextToken
        res.append("]")
      }
    }
    if (token == "(") {
      do {
        if (token == ",") {
          nextToken
          if (token != ")")
            res.append(", ")
        } else {
          nextToken
          res.append("(")
        }
        if (token != ")") {
          if (token == "def") {
            nextToken
            res.append("def ")
          }
          parseType()
        }
      } while (token == ",")
      nextToken
      res.append("): ")
      parseType()
    } else {
      res.append(": ")
      parseType()
    }
    res.toString()
  }

  protected def parseMetaField: String = {
    nextToken
    res.append(": ")
    parseType()
    res.toString()
  }

  protected def parseConstrField: String = {
    nextToken
    if (token == "(") {
      do {
        res.append(if (token == "(") "(" else ", ")
        nextToken
        if (token != ")")
          parseType()
      } while (token == ",")
      nextToken
      res.append(")")
    } else {
    }
    res.toString()
  }
}
