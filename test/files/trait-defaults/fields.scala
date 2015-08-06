trait T {
  val abs: String
  protected val protabs: String
  val pub = "public"
  protected val prot = "protected"
  private val privvy = "private"
  private[this] val privateThis = "private[this]"

  object NO {
    println(abs)
    println(pub)
    println(prot)
    println(protabs)
    println(privvy)
    println(privateThis)
  }

  trait NT {
    println(abs)
    println(pub)
    println(prot)
    println(protabs)
    println(privvy)
    println(privateThis)
  }

  class NC {
    println(abs)
    println(pub)
    println(prot)
    println(protabs)
    println(privvy)
    println(privateThis)
  }
}

class C extends T {
  val abs = "abstract"
  val protabs = "abstract protected"
}