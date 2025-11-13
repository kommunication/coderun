package monitoring

import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.hotspot.DefaultExports

import java.io.StringWriter

object Metrics:

  // Initialize JVM metrics
  DefaultExports.initialize()

  private val registry = CollectorRegistry.defaultRegistry

  // Counter for total requests
  val requestsTotal: Counter = Counter
    .build()
    .name("braindrill_requests_total")
    .help("Total number of code execution requests")
    .labelNames("language", "status")
    .register(registry)

  // Histogram for execution duration
  val executionDuration: Histogram = Histogram
    .build()
    .name("braindrill_execution_duration_seconds")
    .help("Code execution duration in seconds")
    .labelNames("language")
    .buckets(0.1, 0.5, 1.0, 2.0, 5.0, 10.0)
    .register(registry)

  // Gauge for active executions
  val activeExecutions: Gauge = Gauge
    .build()
    .name("braindrill_active_executions")
    .help("Number of currently active code executions")
    .labelNames("language")
    .register(registry)

  // Counter for authentication failures
  val authFailures: Counter = Counter
    .build()
    .name("braindrill_auth_failures_total")
    .help("Total number of authentication failures")
    .register(registry)

  // Counter for rate limit hits
  val rateLimitHits: Counter = Counter
    .build()
    .name("braindrill_rate_limit_hits_total")
    .help("Total number of rate limit hits")
    .labelNames("api_key")
    .register(registry)

  // Counter for validation errors
  val validationErrors: Counter = Counter
    .build()
    .name("braindrill_validation_errors_total")
    .help("Total number of input validation errors")
    .labelNames("error_type")
    .register(registry)

  // Gauge for worker pool size
  val workerPoolSize: Gauge = Gauge
    .build()
    .name("braindrill_worker_pool_size")
    .help("Number of workers in the pool")
    .register(registry)

  def recordRequest(language: String, status: String): Unit =
    requestsTotal.labels(language, status).inc()

  def recordExecutionTime(language: String, durationSeconds: Double): Unit =
    executionDuration.labels(language).observe(durationSeconds)

  def incrementActiveExecutions(language: String): Unit =
    activeExecutions.labels(language).inc()

  def decrementActiveExecutions(language: String): Unit =
    activeExecutions.labels(language).dec()

  def recordAuthFailure(): Unit =
    authFailures.inc()

  def recordRateLimitHit(apiKey: String): Unit =
    rateLimitHits.labels(apiKey).inc()

  def recordValidationError(errorType: String): Unit =
    validationErrors.labels(errorType).inc()

  def setWorkerPoolSize(size: Int): Unit =
    workerPoolSize.set(size.toDouble)

  def getMetrics: String =
    val writer = new StringWriter()
    TextFormat.write004(writer, registry.metricFamilySamples())
    writer.toString
