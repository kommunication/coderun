package security

import monitoring.Metrics

object InputValidator:

  // Configuration
  private val MaxCodeSizeBytes = 100 * 1024 // 100 KB
  private val MaxCodeSizeChars = 50000 // 50k characters

  private val SupportedLanguages = Set(
    "java",
    "python",
    "ruby",
    "perl",
    "javascript",
    "php"
  )

  // Potentially dangerous patterns to block
  private val DangerousPatterns = List(
    "rm -rf",
    "mkfs",
    "dd if=",
    ":/dev/",
    "wget",
    "curl",
    "nc -",
    "netcat"
  )

  sealed trait ValidationResult
  case object Valid extends ValidationResult
  final case class Invalid(reason: String) extends ValidationResult

  def validateRequest(code: String, language: String): ValidationResult =
    // Check language support
    if !SupportedLanguages.contains(language.toLowerCase) then
      Metrics.recordValidationError("unsupported_language")
      return Invalid(s"Unsupported language: $language. Supported: ${SupportedLanguages.mkString(", ")}")

    // Check code size (bytes)
    val codeBytes = code.getBytes("UTF-8")
    if codeBytes.length > MaxCodeSizeBytes then
      Metrics.recordValidationError("code_size_bytes")
      return Invalid(s"Code size exceeds maximum of ${MaxCodeSizeBytes / 1024} KB (got ${codeBytes.length / 1024} KB)")

    // Check code size (characters)
    if code.length > MaxCodeSizeChars then
      Metrics.recordValidationError("code_size_chars")
      return Invalid(s"Code exceeds maximum of $MaxCodeSizeChars characters (got ${code.length})")

    // Check for empty code
    if code.trim.isEmpty then
      Metrics.recordValidationError("empty_code")
      return Invalid("Code cannot be empty")

    // Check for dangerous patterns (basic security)
    val lowerCode = code.toLowerCase
    val foundDangerousPattern = DangerousPatterns.find(pattern =>
      lowerCode.contains(pattern.toLowerCase)
    )

    foundDangerousPattern match
      case Some(pattern) =>
        Metrics.recordValidationError("dangerous_pattern")
        Invalid(s"Code contains potentially dangerous pattern: $pattern")
      case None =>
        Valid

  def isSupportedLanguage(language: String): Boolean =
    SupportedLanguages.contains(language.toLowerCase)

  def getSupportedLanguages: Set[String] = SupportedLanguages
