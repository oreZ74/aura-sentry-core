package de.orez.aura_sentry_core.controller.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for the {@code /api/remediation/apply-tags} endpoint.
 *
 * <p>Validation rules enforced at the controller layer <em>before</em> any
 * shell command is constructed.
 */
public record RemediationRequestDto(

        @NotBlank(message = "resourceId must not be blank")
        @Pattern(
                regexp = "^/subscriptions/[a-f0-9\\-]{36}/resourceGroups/[a-zA-Z0-9_\\-.]{1,90}/providers/[a-zA-Z0-9.]+/[a-zA-Z0-9_\\-]+/[a-zA-Z0-9_\\-]{1,260}$",
                message = "resourceId must be a valid Azure ARM resource ID"
        )
        String resourceId,

        @NotNull(message = "tags map must not be null")
        @Size(min = 1, max = 15, message = "between 1 and 15 tags allowed")
        Map<@Pattern(regexp = "^[a-zA-Z0-9_\\-]{1,512}$", message = "tag key must be alphanumeric with hyphens/underscores")
         @NotBlank
         String,
         @Pattern(regexp = "^[a-zA-Z0-9_\\- .:@]{0,256}$", message = "tag value contains illegal characters")
         @NotNull
         String> tags
) {
}
