package jobs

import java.time.Instant
import java.util.UUID

object Job:

  enum Status:
    case Queued, Running, Completed, Failed, TimedOut

  final case class JobId(value: String):
    override def toString: String = value

  object JobId:
    def generate(): JobId = JobId(UUID.randomUUID().toString)

  final case class JobRequest(
      code: String,
      language: String,
      apiKey: String
  )

  final case class JobInfo(
      id: JobId,
      request: JobRequest,
      status: Status,
      output: Option[String] = None,
      error: Option[String] = None,
      createdAt: Instant = Instant.now(),
      startedAt: Option[Instant] = None,
      completedAt: Option[Instant] = None,
      executionDurationMs: Option[Long] = None
  ):
    def withStatus(newStatus: Status): JobInfo =
      copy(status = newStatus)

    def withStarted(): JobInfo =
      copy(status = Status.Running, startedAt = Some(Instant.now()))

    def withSuccess(result: String): JobInfo =
      val now = Instant.now()
      val durationMs = startedAt.map(start =>
        java.time.Duration.between(start, now).toMillis
      )
      copy(
        status = Status.Completed,
        output = Some(result),
        completedAt = Some(now),
        executionDurationMs = durationMs
      )

    def withFailure(errorMsg: String): JobInfo =
      val now = Instant.now()
      val durationMs = startedAt.map(start =>
        java.time.Duration.between(start, now).toMillis
      )
      copy(
        status = Status.Failed,
        error = Some(errorMsg),
        completedAt = Some(now),
        executionDurationMs = durationMs
      )

    def isTerminal: Boolean = status match
      case Status.Completed | Status.Failed | Status.TimedOut => true
      case _ => false

  final case class JobSummary(
      id: JobId,
      language: String,
      status: Status,
      createdAt: Instant,
      completedAt: Option[Instant],
      executionDurationMs: Option[Long]
  )

  object JobSummary:
    def from(job: JobInfo): JobSummary =
      JobSummary(
        id = job.id,
        language = job.request.language,
        status = job.status,
        createdAt = job.createdAt,
        completedAt = job.completedAt,
        executionDurationMs = job.executionDurationMs
      )
