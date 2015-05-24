package org.allenai.pipeline

import org.allenai.common.Config._
import org.allenai.common.Logging
import org.allenai.pipeline.IoHelpers._
import org.allenai.pipeline.UrlToArtifact._

import com.typesafe.config.Config
import spray.json.DefaultJsonProtocol._
import spray.json.JsonFormat

import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag
import scala.util.control.NonFatal

import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date

/** A fully-configured end-to-end pipeline */
class Pipeline(
  val outputRootUrl: URI = new File(System.getProperty("user.dir")).toURI,
  artifactFactory: ArtifactFactory = ArtifactFactory(UrlToArtifact.handleFileUrls)
  ) extends Logging {

  /** Run the pipeline.  All steps that have been persisted will be computed, along with any upstream dependencies */
  def run(title: String) = {
    runPipelineReturnResults(title, persistedSteps.toSeq)
  }

  /** Common-case methods for persisting Producers */
  object Persist {

    /** Persist a collection */
    object Collection {
      def asText[T: StringSerializable : ClassTag](
        step: Producer[Iterable[T]],
        suffix: String = ".txt"
        )(): PersistedProducer[Iterable[T], FlatArtifact] =
        persist(step, LineCollectionIo.text[T], suffix)

      def asJson[T: JsonFormat : ClassTag](
        step: Producer[Iterable[T]],
        suffix: String = ".json"
        )(): PersistedProducer[Iterable[T], FlatArtifact] =
        persist(step, LineCollectionIo.json[T], suffix)
    }

    /** Persist a single object */
    object Singleton {
      def asText[T: StringSerializable : ClassTag](
        step: Producer[T],
        suffix: String = ".txt"
        )(): PersistedProducer[T, FlatArtifact] =
        persist(step, SingletonIo.text[T], suffix)

      def asJson[T: JsonFormat : ClassTag](
        step: Producer[T],
        suffix: String = ".json"
        )(): PersistedProducer[T, FlatArtifact] =
        persist(step, SingletonIo.json[T], suffix)
    }

    /** Persist an Iterator */
    object Iterator {
      def asText[T: StringSerializable : ClassTag](
        step: Producer[Iterator[T]],
        suffix: String = ".txt"
        ): PersistedProducer[Iterator[T], FlatArtifact] =
        persist(step, LineIteratorIo.text[T], suffix)

      def asJson[T: JsonFormat : ClassTag](
        step: Producer[Iterator[T]],
        suffix: String = ".json"
        )(): PersistedProducer[Iterator[T], FlatArtifact] =
        persist(step, LineIteratorIo.json[T], suffix)
    }

  }

  /** Create a persisted version of the given Producer
    * The producer is registered as a target of the pipeline, and will be computed
    * when the pipeline is run.
    * See Persist.Collection, Persist.Singleton, etc. utility methods above
    * @param original the non-persisted Producer
    * @param io the serialization format
    * @param suffix a file suffix
    * @return the persisted Producer
    */
  def persist[T, A <: Artifact : ClassTag, AO <: A](
    original: Producer[T],
    io: Serializer[T, A] with Deserializer[T, A],
    suffix: String = ""
    ): PersistedProducer[T, A] = {
    val path = s"data/${autoGeneratedPath(original, io)}$suffix"
    persist(original, io, absoluteOutputUrl(path))
  }

  def persist[T, A <: Artifact : ClassTag, AO <: A](
    original: Producer[T],
    io: Serializer[T, A] with Deserializer[T, A],
    url: URI
    ): PersistedProducer[T, A] = {
    persist(original, io, createArtifact[A](url))
  }

  /** Persist this Producer and add it to list of targets that will be computed when the pipeline is run */
  def persist[T, A <: Artifact](
    original: Producer[T],
    io: Serializer[T, A] with Deserializer[T, A],
    artifact: A
    ) = {
    val persisted = original.persisted(io, artifact)
    persistedSteps += persisted
    persisted
  }

  def absoluteOutputUrl(path: String): URI = ArtifactFactory.resolveUrl(outputRootUrl)(path)

  /** Create an Artifact at the given path.
    * The type tag determines the type of the Artifact, which may be an abstract type
    */
  def createArtifact[A <: Artifact : ClassTag](url: URI): A =
    artifactFactory.createArtifact[A](url)

  def createArtifact[A <: Artifact : ClassTag](path: String): A =
    artifactFactory.createArtifact[A](absoluteOutputUrl(path))

  def runOne[T, A <: Artifact : ClassTag](target: PersistedProducer[T, A], outputLocationOverride: Option[String] = None) = {
    val targetWithOverriddenLocation: Producer[T] =
      outputLocationOverride match {
        case Some(tmp) =>
          target.changePersistence(target.io, createArtifact[A](new URI(tmp)))
        case None => target
      }
    runOnly(
      targetWithOverriddenLocation.stepInfo.className,
      List(targetWithOverriddenLocation): _*
    ).head.asInstanceOf[T]
  }

  /** Run only specified steps in the pipeline.  Upstream dependencies must exist already.  They will not be computed */
  def runOnly(title: String, runOnlyTargets: Producer[_]*) = {
    val (persistedTargets, unpersistedTargets) = runOnlyTargets.partition(_.isInstanceOf[PersistedProducer[_, _]])
    val targets = persistedTargets ++
      unpersistedTargets.flatMap(s => persistedSteps.find(_.stepInfo.signature == s.stepInfo.signature))
    require(targets.size == runOnlyTargets.size, "Specified targets are not members of this pipeline")

    val persistedStepsInfo = (persistedSteps ++ persistedTargets).map(_.stepInfo).toSet
    val overridenStepsInfo = targets.map(_.stepInfo).toSet
    val nonPersistedTargets = overridenStepsInfo -- persistedStepsInfo
    require(
      nonPersistedTargets.size == 0,
      s"Running a pipeline without persisting the output: [${nonPersistedTargets.map(_.className).mkString(",")}]"
    )
    val allDependencies = targets.flatMap(Workflow.upstreamDependencies)
    val nonExistentDependencies =
      for {
        p <- allDependencies if p.isInstanceOf[PersistedProducer[_, _]]
        pp = p.asInstanceOf[PersistedProducer[_, _ <: Artifact]]
        if !overridenStepsInfo(pp.stepInfo)
        if !pp.artifact.exists
      } yield pp.stepInfo
    require(nonExistentDependencies.size == 0, {
      val targetNames = overridenStepsInfo.map(_.className).mkString(",")
      val dependencyNames = nonExistentDependencies.map(_.className).mkString(",")
      s"Cannot run steps [$targetNames]. Upstream dependencies [$dependencyNames] have not been computed"
    })
    runPipelineReturnResults(title, targets)
  }

  protected[this] def runPipelineReturnResults(rawTitle: String, outputs: Iterable[Producer[_]]) = {
    val result = try {
      val start = System.currentTimeMillis
      val result = outputs.map(_.get)
      val duration = (System.currentTimeMillis - start) / 1000.0
      logger.info(f"Ran pipeline in $duration%.3f s")
      result
    } catch {
      case NonFatal(e) =>
        logger.error("Untrapped exception", e)
        List()
    }

    val title = rawTitle.replaceAll( """\s+""", "-")
    val today = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new Date())

    val workflowArtifact = createArtifact[FlatArtifact](s"summary/$title-$today.workflow.json")
    val workflow = Workflow.forPipeline(outputs.toSeq: _*)
    SingletonIo.json[Workflow].write(workflow, workflowArtifact)

    val htmlArtifact = createArtifact[FlatArtifact](s"summary/$title-$today.html")
    SingletonIo.text[String].write(workflow.renderHtml, htmlArtifact)

    val signatureArtifact = createArtifact[FlatArtifact](s"summary/$title-$today.signatures.json")
    val signatureFormat = Signature.jsonWriter
    val signatures = outputs.map(p => signatureFormat.write(p.stepInfo.signature)).toList.toJson
    signatureArtifact.write { writer => writer.write(signatures.prettyPrint)}

    logger.info(s"Summary written to ${toHttpUrl(htmlArtifact.url)}")
    result
  }

  // Generate an output path based on the Producer's signature
  protected def autoGeneratedPath[T, A <: Artifact](p: Producer[T], io: Serializer[T, A] with Deserializer[T, A]) = {
    // Although the persistence method does not affect the signature
    // (the same object will be returned in all cases), it is used
    // to determine the output path, to avoid parsing incompatible data
    val signature = p.stepInfo.copy(
      dependencies = p.stepInfo.dependencies + ("io" -> io)
    ).signature
    s"${
      signature.name
    }.${
      signature.id
    }"
  }

  // Convert S3 URLs to an http: URL viewable in a browser
  def toHttpUrl(url: URI): URI = {
    url.getScheme match {
      case "s3" | "s3n" =>
        new java.net.URI("http", s"${
          url.getHost
        }.s3.amazonaws.com", url.getPath, null)
      case "file" =>
        new java.net.URI(null, null, url.getPath, null)
      case _ => url
    }
  }

  def dryRun(outputDir: File, rawTitle: String): Iterable[Any] = {
    val outputs = persistedSteps.toList
    val title = s"${
      rawTitle.replaceAll( """\s+""", "-")
    }-dryRun"
    val workflowArtifact = new FileArtifact(new File(outputDir, s"$title.workflow.json"))
    val workflow = Workflow.forPipeline(outputs: _*)
    SingletonIo.json[Workflow].write(workflow, workflowArtifact)

    val htmlArtifact = new FileArtifact(new File(outputDir, s"$title.html"))
    SingletonIo.text[String].write(workflow.renderHtml, htmlArtifact)

    val signatureArtifact = new FileArtifact(new File(outputDir, s"$title.signatures.json"))
    val signatureFormat = Signature.jsonWriter
    val signatures = outputs.map(p => signatureFormat.write(p.stepInfo.signature)).toJson
    signatureArtifact.write {
      writer => writer.write(signatures.prettyPrint)
    }

    logger.info(s"Summary written to $outputDir")
    List()
  }

  protected[this] val persistedSteps: ListBuffer[Producer[_]] = ListBuffer()
}

object Pipeline {
  // Create a Pipeline that writes output to the given directory
  def saveToFileSystem(rootDir: File) = {
    new Pipeline(rootDir.toURI, ArtifactFactory(handleFileUrls))
  }
}

class ConfiguredPipeline(
  val config: Config,
  artifactFactory: ArtifactFactory = ArtifactFactory(handleFileUrls))
  extends Pipeline(
    config.get[String]("output.dir").map(s => new URI(s))
      .getOrElse(new File(System.getProperty("user.dir")).toURI),
    artifactFactory) {

  protected[this] val persistedStepsByConfigKey =
    scala.collection.mutable.Map.empty[String, Producer[_]]

  private lazy val runOnlySteps = config.get[String]("runOnly").map(_.split(",").toSet).getOrElse(Set.empty[String])

  override def run(rawTitle: String) = {
    config.get[Boolean]("dryRun") match {
      case Some(true) => dryRun(new File(System.getProperty("user.dir")), rawTitle)
      case _ =>
        config.get[String]("runOnly") match {
          case Some(stepConfigKeys) =>
            val (matchedNames, unmatchedNames) = runOnlySteps.partition(persistedStepsByConfigKey.contains)
            unmatchedNames.size match {
              case 0 =>
                val matches = matchedNames.map(persistedStepsByConfigKey)
                runOnly(rawTitle, matches.toList: _*)
              case 1 =>
                sys.error(s"Unknown step name: ${
                  unmatchedNames.head
                }")
              case _ =>
                sys.error(s"Unknown step names: [${
                  unmatchedNames.mkString(",")
                }]")
            }
          case _ => super.run(rawTitle)
        }
    }
  }

  def optionallyPersist[T, A <: Artifact : ClassTag](
    original: Producer[T],
    stepName: String,
    io: Serializer[T, A] with Deserializer[T, A],
    suffix: String = ""
    ): Producer[T] = {
    val configKey = s"output.persist.$stepName"
    if (config.hasPath(configKey)) {
      config.getValue(configKey).unwrapped() match {
        case java.lang.Boolean.TRUE | "true" =>
          val p = persist(original, io, suffix)
          persistedStepsByConfigKey(stepName) = p
          p
        case path: String if path != "false" =>
          val p = persist(original, io, createArtifact[A](path))
          persistedStepsByConfigKey(stepName) = p
          p
        case _ => original
      }
    } else {
      original
    }
  }

  object OptionallyPersist {

    object Iterator {
      def asText[T: StringSerializable : ClassTag](
        step: Producer[Iterator[T]],
        stepName: String,
        suffix: String = ".txt"
        ): Producer[Iterator[T]] =
        optionallyPersist(step, stepName, LineIteratorIo.text[T], suffix)

      def asJson[T: JsonFormat : ClassTag](
        step: Producer[Iterator[T]],
        stepName: String,
        suffix: String = ".json"
        ): Producer[Iterator[T]] =
        optionallyPersist(step, stepName, LineIteratorIo.json[T], suffix)
    }

    object Collection {
      def asText[T: StringSerializable : ClassTag](
        step: Producer[Iterable[T]],
        stepName: String,
        suffix: String = ".txt"
        )(): Producer[Iterable[T]] =
        optionallyPersist(step, stepName, LineCollectionIo.text[T], suffix)

      def asJson[T: JsonFormat : ClassTag](
        step: Producer[Iterable[T]],
        stepName: String,
        suffix: String = ".json"
        )(): Producer[Iterable[T]] =
        optionallyPersist(step, stepName, LineCollectionIo.json[T], suffix)
    }

    object Singleton {
      def asText[T: StringSerializable : ClassTag](
        step: Producer[T],
        stepName: String,
        suffix: String = ".txt"
        )(): Producer[T] =
        optionallyPersist(step, stepName, SingletonIo.text[T], suffix)

      def asJson[T: JsonFormat : ClassTag](
        step: Producer[T],
        stepName: String,
        suffix: String = ".json"
        )(): Producer[T] =
        optionallyPersist(step, stepName, SingletonIo.json[T], suffix)
    }

  }

}

