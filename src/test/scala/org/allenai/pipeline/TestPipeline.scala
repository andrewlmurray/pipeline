package org.allenai.pipeline

import java.io.File

import org.allenai.common.testkit.{ ScratchDirectory, UnitSpec }

class TestPipeline extends UnitSpec with ScratchDirectory {
  case class AddOne(input: Producer[Int]) extends Producer[Int] with Ai2StepInfo {
    override def create = input.get + 1
  }
  "Pipeline" should "run all persisted targets" in {
    val p1 = Producer.fromMemory(1)
    val p2 = Producer.fromMemory(2)
    val p3 = Producer.fromMemory(3)
    val p4 = Producer.fromMemory(4)
    val outputDir = new File(scratchDir, "test1")
    val pipeline = Pipeline(outputDir)

    import IoHelpers._
    val p1File = new File(pipeline.Persist.Singleton.asText(p1, "One").artifact.url)
    val p2File = new File(pipeline.Persist.Singleton.asText(p2, "Two").artifact.url)
    val p3File = new File(pipeline.Persist.Singleton.asText(p3, "Three").artifact.url)
    val p4Persisted = pipeline.Persist.Singleton.asText(p4, "Four")
    val p4File = new File(p4Persisted.artifact.url)

    an[IllegalArgumentException] should be thrownBy {
      val p5 = AddOne(p4Persisted)
      pipeline.Persist.Singleton.asText(p5, "Five")
      // p5 has p4 as a dependency, but p4 has not been computed yet
      pipeline.runOnly("test", "Five")
    }

    pipeline.runOnly("test", "One")
    p1File should exist
    p2File should not(exist)

    pipeline.run("test")
    p2File should exist
    p3File should exist
    p4File should exist

    a[RuntimeException] should be thrownBy {
      // No such step
      pipeline.runOnly("test", "Six")
    }

    val p5 = new Producer[Int] with Ai2SimpleStepInfo {
      override def create = 5
      override def stepInfo = super.stepInfo.addParameters(("upstream", p4))
    }
    pipeline.Persist.Singleton.asText(p5)
    // p5 is persisted and its dependency exists.  All clear!
    pipeline.runOnly("test", "Five")
  }

}
