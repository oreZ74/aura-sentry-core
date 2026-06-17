package de.orez.aura_sentry_core.model;

/**
 * Couples a cost amount with its billing currency as reported by the
 * Azure Cost Management API.
 *
 * <p>The {@link #currencySymbol()} helper maps ISO-4217 codes to their
 * display symbols for the frontend. Unknown codes fall back to {@code "€"}.
 *
 * @param amount   month-to-date cost (may be {@code 0.0} for Free-Tier resources)
 * @param currency ISO-4217 currency code (e.g. {@code "EUR"}, {@code "USD"})
 */
public record BillingInfo(double amount, String currency) {

    /**
     * Returns the display symbol for the billing currency.
     *
     * @return a short currency symbol such as {@code "€"}, {@code "$"}, or
     *         the ISO code itself for currencies without a common symbol.
     */
    public static String currencySymbol(String currency) {
        if (currency == null) {
            return "€";
        }
        return switch (currency.toUpperCase()) {
            case "EUR" -> "€";
            case "USD" -> "$";
            case "GBP" -> "£";
            case "JPY", "CNY" -> "¥";
            case "CHF" -> "CHF ";
            case "KRW" -> "₩";
            case "INR" -> "₹";
            case "BRL" -> "R$";
            case "TRY" -> "₺";
            case "SEK", "NOK", "DKK" -> "kr ";
            default -> "€";
        };
    }

    public String currencySymbol() {
        return currencySymbol(currency);
    }
}
