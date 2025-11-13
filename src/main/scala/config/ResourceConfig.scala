package config

import com.typesafe.config.Config
import scala.concurrent.duration.*
import scala.util.Try

object ResourceConfig:

  final case class ResourceLimits(
      cpus: Int,
      memoryMb: Int,
      timeoutSeconds: Int
  ):
    def memoryString: String = s"${memoryMb}m"
    def timeout: FiniteDuration = timeoutSeconds.seconds

  private val defaultLimits = ResourceLimits(
    cpus = 1,
    memoryMb = 20,
    timeoutSeconds = 2
  )

  // Per-language resource profiles
  private val languageProfiles: Map[String, ResourceLimits] = Map(
    "java" -> ResourceLimits(cpus = 2, memoryMb = 256, timeoutSeconds = 10),
    "python" -> ResourceLimits(cpus = 1, memoryMb = 50, timeoutSeconds = 5),
    "javascript" -> ResourceLimits(cpus = 1, memoryMb = 50, timeoutSeconds = 5),
    "ruby" -> ResourceLimits(cpus = 1, memoryMb = 30, timeoutSeconds = 5),
    "perl" -> ResourceLimits(cpus = 1, memoryMb = 20, timeoutSeconds = 3),
    "php" -> ResourceLimits(cpus = 1, memoryMb = 40, timeoutSeconds = 5)
  )

  def getLimitsForLanguage(language: String): ResourceLimits =
    languageProfiles.getOrElse(language.toLowerCase, defaultLimits)

  def loadFromConfig(cfg: Config): Map[String, ResourceLimits] =
    Try {
      val languages = List("java", "python", "javascript", "ruby", "perl", "php")
      languages.flatMap { lang =>
        Try {
          val cpus = cfg.getInt(s"resources.$lang.cpus")
          val memoryMb = cfg.getInt(s"resources.$lang.memory-mb")
          val timeoutSeconds = cfg.getInt(s"resources.$lang.timeout-seconds")
          lang -> ResourceLimits(cpus, memoryMb, timeoutSeconds)
        }.toOption
      }.toMap
    }.getOrElse(Map.empty)

  def getAllProfiles: Map[String, ResourceLimits] = languageProfiles
