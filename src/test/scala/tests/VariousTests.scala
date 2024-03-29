package tests

class LazyImplicitTests extends BaseTests {

  def f[A](implicit i: () => A) = i

  implicit def toLazy[A](implicit i: A): () => A = () => i

  describe("Abs") {
    implicit val i = 10
    f[Int]
  }
}
