package br.com.shiftcatcher.benchmark

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/poc/benchmark")
class BenchmarkController(
    private val service: BenchmarkService,
) {
    /** `EP-035`: replays a labelled corpus. Persists no opportunity and sends no message. */
    @PostMapping("/start")
    fun start(
        @RequestBody(required = false) request: BenchmarkRequest?,
    ): BenchmarkStartResponse = service.start(request)

    /** `EP-036`: the report, once the run is over. */
    @GetMapping("/{benchmarkId}")
    fun detail(
        @PathVariable benchmarkId: String,
    ): BenchmarkRunResponse = service.detail(benchmarkId)
}
