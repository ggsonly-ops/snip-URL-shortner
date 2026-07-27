package dev.snip.web;

import dev.snip.analytics.AnalyticsService;
import dev.snip.dto.Dtos.AnalyticsResponse;
import dev.snip.dto.Dtos.CreateLinkRequest;
import dev.snip.dto.Dtos.LinkResponse;
import dev.snip.dto.Dtos.PageResponse;
import dev.snip.dto.Dtos.UpdateLinkRequest;
import dev.snip.service.LinkService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;

@RestController
@RequestMapping("/api/links")
@RequiredArgsConstructor
@Validated
public class LinkController {

    private final LinkService service;
    private final AnalyticsService analytics;
    private final QrCodeService qrCodes;

    @PostMapping
    public ResponseEntity<LinkResponse> create(
            @Valid @RequestBody CreateLinkRequest req,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {

        LinkResponse res = service.create(req, ApiKeys.normalise(apiKey));
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create(res.shortUrl()))
                .body(res);
    }

    @GetMapping
    public PageResponse<LinkResponse> list(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(ApiKeys.normalise(apiKey), page, size);
    }

    @GetMapping("/{code}")
    public LinkResponse get(@PathVariable String code,
                            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return service.get(code, ApiKeys.normalise(apiKey));
    }

    @PatchMapping("/{code}")
    public LinkResponse update(@PathVariable String code,
                               @Valid @RequestBody UpdateLinkRequest req,
                               @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return service.update(code, req, ApiKeys.normalise(apiKey));
    }

    @DeleteMapping("/{code}")
    public ResponseEntity<Void> delete(@PathVariable String code,
                                       @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        service.delete(code, ApiKeys.normalise(apiKey));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{code}/analytics")
    public AnalyticsResponse analytics(@PathVariable String code,
                                       @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days,
                                       @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return analytics.analytics(code, days, ApiKeys.normalise(apiKey));
    }

    @GetMapping(value = "/{code}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qr(@PathVariable String code,
                                     @RequestParam(defaultValue = "240") @Min(64) @Max(1024) int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(qrCodes.pngFor(code, size));
    }
}
