package jobs

import jobs.Job.*

object JobJsonSupport:

  def jobSubmittedToJson(jobId: JobId, status: Status): String =
    s"""{
       |  "job_id": "${jobId.value}",
       |  "status": "${status.toString.toLowerCase}"
       |}""".stripMargin

  def jobInfoToJson(job: JobInfo): String =
    val output = job.output.map(o => s""""${escapeJson(o)}"""").getOrElse("null")
    val error = job.error.map(e => s""""${escapeJson(e)}"""").getOrElse("null")
    val startedAt = job.startedAt.map(t => s""""${t.toString}"""").getOrElse("null")
    val completedAt = job.completedAt.map(t => s""""${t.toString}"""").getOrElse("null")
    val duration = job.executionDurationMs.map(_.toString).getOrElse("null")

    s"""{
       |  "job_id": "${job.id.value}",
       |  "language": "${job.request.language}",
       |  "status": "${job.status.toString.toLowerCase}",
       |  "output": $output,
       |  "error": $error,
       |  "created_at": "${job.createdAt.toString}",
       |  "started_at": $startedAt,
       |  "completed_at": $completedAt,
       |  "execution_duration_ms": $duration
       |}""".stripMargin

  def jobNotFoundToJson(jobId: JobId): String =
    s"""{
       |  "error": "Job not found",
       |  "job_id": "${jobId.value}"
       |}""".stripMargin

  def jobListToJson(jobs: List[JobSummary], total: Int, limit: Int, offset: Int): String =
    val jobsJson = jobs.map { job =>
      val completedAt = job.completedAt.map(t => s""""${t.toString}"""").getOrElse("null")
      val duration = job.executionDurationMs.map(_.toString).getOrElse("null")

      s"""{
         |    "job_id": "${job.id.value}",
         |    "language": "${job.language}",
         |    "status": "${job.status.toString.toLowerCase}",
         |    "created_at": "${job.createdAt.toString}",
         |    "completed_at": $completedAt,
         |    "execution_duration_ms": $duration
         |  }""".stripMargin
    }.mkString(",\n")

    s"""{
       |  "jobs": [
       |$jobsJson
       |  ],
       |  "pagination": {
       |    "total": $total,
       |    "limit": $limit,
       |    "offset": $offset
       |  }
       |}""".stripMargin

  private def escapeJson(s: String): String =
    s.replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
