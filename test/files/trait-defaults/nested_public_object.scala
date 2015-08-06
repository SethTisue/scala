trait NestedObj {
  object O { println("NO") }
}

class C extends NestedObj {
  def O = ???
}