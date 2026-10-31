package com.fightexchange.admin

import com.fightexchange.ingest.source.SourceAlertJob
import com.fightexchange.ingest.source.SourceStatusService
import com.fightexchange.ingest.source.SourceSync
import com.fightexchange.ingest.source.SyncOutcome
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import io.micronaut.serde.annotation.Serdeable
import java.time.Instant

@Serdeable
data class SourceStatusDto(
    val source: String,
    val registered: Boolean,
    val lastSuccessAt: Instant?,
    val lastError: String?,
    val lastErrorAt: Instant?,
    val creditsLeft: Int?,
)

@Serdeable
data class SourcesDto(
    val sources: List<SourceStatusDto>,
    val alerts: List<String>,
)

@Serdeable
data class SyncResultDto(
    val source: String,
    val submitted: Int,
    val creditsLeft: Int?,
)

/** Source operations (plan.md F5): health of every source and a manual sync. */
@Controller("/api/v1/admin/sources")
class AdminSourceController(
    private val sync: SourceSync,
    private val status: SourceStatusService,
    private val alertJob: SourceAlertJob,
) {
    @Get
    fun list(): SourcesDto {
        val stored = status.list().associateBy { it.source }
        val rows =
            (sync.sources + stored.keys).sorted().map { name ->
                val s = stored[name]
                SourceStatusDto(
                    name,
                    name in sync.sources,
                    s?.lastSuccessAt,
                    s?.lastError,
                    s?.lastErrorAt,
                    s?.creditsLeft,
                )
            }
        return SourcesDto(rows, alertJob.alerts().map { "${it.source}: ${it.reason}" })
    }

    @Post("/{source}/sync")
    fun sync(
        @PathVariable source: String,
    ): HttpResponse<*> =
        when (val outcome = sync.sync(source)) {
            is SyncOutcome.Done ->
                HttpResponse.ok(SyncResultDto(source, outcome.result.submitted, outcome.result.creditsLeft))
            is SyncOutcome.Failed ->
                HttpResponse.status<ErrorDto>(HttpStatus.BAD_GATEWAY).body(ErrorDto(outcome.message))
            SyncOutcome.UnknownSource -> HttpResponse.notFound<Any>()
        }
}
