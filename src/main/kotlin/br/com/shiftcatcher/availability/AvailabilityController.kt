package br.com.shiftcatcher.availability

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/availability")
class AvailabilityController(
    private val service: AvailabilityService,
) {
    /** `EP-040`: the merged view — what she typed and what this system claimed for her. */
    @GetMapping
    fun list(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): CommitmentListResponse = service.list(from, to)

    @PostMapping
    fun create(
        @RequestBody request: CreateAvailabilityRequest,
    ): AvailabilityEntryResponse = service.create(request)

    /** Deletes a hand-kept entry. A shift claimed here is taken back with `EP-037`, not deleted. */
    @DeleteMapping("/{entryId}")
    fun delete(
        @PathVariable entryId: String,
    ): AvailabilityEntryResponse = service.delete(entryId)
}
