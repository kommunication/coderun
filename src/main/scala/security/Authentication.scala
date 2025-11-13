package security

import pekko.http.scaladsl.server.Directives.*
import pekko.http.scaladsl.server.{Directive1, Route}
import pekko.http.scaladsl.model.StatusCodes
import pekko.http.scaladsl.model.headers.RawHeader
import monitoring.Metrics

import scala.util.Try

object Authentication:

  // API keys configuration - in production, load from secure config/database
  private val validApiKeys: Set[String] = Set(
    "dev-key-12345",           // Development key
    "prod-key-67890",          // Production key
    "test-key-abcde"           // Testing key
  )

  // Extract API key from header or query parameter
  private def extractApiKey: Directive1[Option[String]] =
    optionalHeaderValueByName("X-API-Key").flatMap { headerKey =>
      parameter("api_key".optional).map { paramKey =>
        headerKey.orElse(paramKey)
      }
    }

  // Authentication directive
  def authenticated: Directive1[String] =
    extractApiKey.flatMap {
      case Some(apiKey) if validApiKeys.contains(apiKey) =>
        provide(apiKey)
      case Some(_) =>
        Metrics.recordAuthFailure()
        complete(StatusCodes.Unauthorized -> "Invalid API key")
      case None =>
        Metrics.recordAuthFailure()
        complete(StatusCodes.Unauthorized -> "API key required. Provide X-API-Key header or api_key parameter")
    }

  // Check if API key is valid (for internal use)
  def isValidApiKey(apiKey: String): Boolean =
    validApiKeys.contains(apiKey)

  // Get all valid API keys (for testing/admin)
  def getValidApiKeys: Set[String] = validApiKeys
