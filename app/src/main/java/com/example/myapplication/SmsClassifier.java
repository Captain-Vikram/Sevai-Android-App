package com.example.myapplication;

import java.util.regex.Pattern;

/**
 * Heuristic-based SMS classifier that uses a scoring model to determine if a
 * message is a financial transaction or promotional content.
 */
public class SmsClassifier {

    // Patterns for positive indicators (financial transactions)
    private static final Pattern TRANSACTION_KEYWORDS = Pattern.compile(
            "\\b(credited|debited|transferred|withdrawn|deposited|paid|received)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern CURRENCY_PATTERN = Pattern.compile(
            "\\b(Rs\\.?|INR|₹)\\s*\\d+",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern ACCOUNT_PATTERN = Pattern.compile(
            "\\b(A/c|Acc|Account|A/C No)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern BALANCE_PATTERN = Pattern.compile(
            "\\b(AvlBal|Avl Bal|Available Balance|Total Bal|Balance)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern UPI_PATTERN = Pattern.compile(
            "\\b(UPI Ref No|UPI ID|UPI|Transaction ID|Txn ID|TXN)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern BANK_CODES = Pattern.compile(
            "\\b(HDFC|ICICI|SBI|AXIS|PNB|BOB|KOTAK|PAYTM|PHONEPE|GPAY|GOOGLEPAY)\\b",
            Pattern.CASE_INSENSITIVE
    );

    // Patterns for negative indicators (promotional content)
    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PROMO_KEYWORDS = Pattern.compile(
            "\\b(offer|discount|sale|deals|cashback|winner|congratulations|claim|valid|hurry|limited time|free|shop now|buy now|click here)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SPAM_INDICATORS = Pattern.compile(
            "\\b(unsubscribe|opt-out|reply stop|marketing|advertisement|promo code)\\b",
            Pattern.CASE_INSENSITIVE
    );

    // Classification threshold
    private static final int FINANCIAL_THRESHOLD = 3;

    // Extraction patterns
    private static final Pattern AMOUNT_WITH_CURRENCY = Pattern.compile(
            "\\b(Rs\\.?|INR|₹)\\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TYPE_DEBIT = Pattern.compile("\\b(Dr\\.?|debited|debit|spent|withdrawn)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPE_CREDIT = Pattern.compile("\\b(Cr\\.?|credited|credit|received|deposited)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEDIUM_PATTERN = Pattern.compile("\\b(UPI|IMPS|NEFT|RTGS|ATM|POS|CARD|VISA|MASTER|RUPAY|NET BANKING)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TARGET_TO = Pattern.compile("\\bto\\s+([A-Za-z0-9@._\\- ]{2,40})", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final Pattern TARGET_FROM = Pattern.compile("\\bfrom\\s+([A-Za-z0-9@._\\- ]{2,40})", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final Pattern REF_PATTERN = Pattern.compile("\\b(Ref(?:erence)?(?: No\\.?| #|:)\\s*([A-Za-z0-9\\-]+)|UPI Ref(?: No)?\\s*([A-Za-z0-9\\-]+)|Txn(?: ID| No)?\\s*([A-Za-z0-9\\-]+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_INLINE = Pattern.compile("(\\b\\d{4}-\\d{2}-\\d{2}\\b|\\b\\d{2}[/\\-]\\d{2}[/\\-]\\d{2,4}\\b|\\b\\d{1,2}-[A-Za-z]{3}-\\d{2}\\b)");

    /**
     * Classification result with score details
     */
    public static class ClassificationResult {

        private final boolean isFinancial;
        private final int score;
        private final String category;

        // Extracted fields (nullable)
        private final Double amount;
        private final String currency;
        private final String type; // credit | debit
        private final String medium; // UPI | CARD | ATM | etc
        private final String targetParty; // to/from party
        private final String description; // reference or note
        private final String extractedDate; // date found in message

        public ClassificationResult(boolean isFinancial, int score, String category,
                Double amount, String currency, String type,
                String medium, String targetParty, String description,
                String extractedDate) {
            this.isFinancial = isFinancial;
            this.score = score;
            this.category = category;
            this.amount = amount;
            this.currency = currency;
            this.type = type;
            this.medium = medium;
            this.targetParty = targetParty;
            this.description = description;
            this.extractedDate = extractedDate;
        }

        public boolean isFinancial() {
            return isFinancial;
        }

        public int getScore() {
            return score;
        }

        public String getCategory() {
            return category;
        }

        public Double getAmount() {
            return amount;
        }

        public String getCurrency() {
            return currency;
        }

        public String getType() {
            return type;
        }

        public String getMedium() {
            return medium;
        }

        public String getTargetParty() {
            return targetParty;
        }

        public String getDescription() {
            return description;
        }

        public String getExtractedDate() {
            return extractedDate;
        }
    }

    /**
     * Classifies an SMS message using heuristic scoring
     *
     * @param message The SMS message body
     * @return ClassificationResult with score and category
     */
    public static ClassificationResult classify(String message) {
        if (message == null || message.trim().isEmpty()) {
            return new ClassificationResult(false, 0, "Empty", null, null, null, null, null, null, null);
        }

        int score = 0;

        // Positive indicators (add points)
        if (TRANSACTION_KEYWORDS.matcher(message).find()) {
            score += 3; // Very strong indicator
        }

        if (CURRENCY_PATTERN.matcher(message).find()) {
            score += 2; // Strong indicator
        }

        if (ACCOUNT_PATTERN.matcher(message).find()) {
            score += 2; // Strong indicator
        }

        if (BALANCE_PATTERN.matcher(message).find()) {
            score += 2; // Very specific to bank updates
        }

        if (UPI_PATTERN.matcher(message).find()) {
            score += 1; // Common in UPI transactions
        }

        if (BANK_CODES.matcher(message).find()) {
            score += 1; // Bank/payment service mentioned
        }

        // Negative indicators (subtract points)
        if (URL_PATTERN.matcher(message).find()) {
            score -= 3; // Very strong indicator of promotional content
        }

        if (PROMO_KEYWORDS.matcher(message).find()) {
            score -= 2; // Strong indicator of promotional content
        }

        if (SPAM_INDICATORS.matcher(message).find()) {
            score -= 2; // Marketing/spam indicators
        }

        // Determine category
        boolean isFinancial = score >= FINANCIAL_THRESHOLD;
        String category;

        if (score >= 5) {
            category = "Financial - High Confidence";
        } else if (score >= FINANCIAL_THRESHOLD) {
            category = "Financial - Medium Confidence";
        } else if (score >= 0) {
            category = "Informational";
        } else if (score >= -3) {
            category = "Promotional";
        } else {
            category = "Spam/Marketing";
        }

        // Defaults for extracted fields
        Double amount = null;
        String currency = null;
        String type = null;
        String medium = null;
        String targetParty = null;
        String description = null;
        String extractedDate = null;

        // Extract only for financial-like messages
        if (isFinancial) {
            // Amount and currency
            java.util.regex.Matcher mAmt = AMOUNT_WITH_CURRENCY.matcher(message);
            if (mAmt.find()) {
                currency = mAmt.group(1);
                String amtStr = mAmt.group(2).replace(",", "");
                try {
                    amount = Double.parseDouble(amtStr);
                } catch (NumberFormatException ignored) {
                    // leave amount as null
                }
            }

            // Type (credit/debit)
            if (TYPE_DEBIT.matcher(message).find()) {
                type = "debit";
            }
            if (TYPE_CREDIT.matcher(message).find()) {
                type = (type == null) ? "credit" : type;
            }

            // Medium
            java.util.regex.Matcher mMed = MEDIUM_PATTERN.matcher(message);
            if (mMed.find()) {
                medium = mMed.group(1).toUpperCase();
            }

            // Target party (prefer 'to', else 'from')
            java.util.regex.Matcher mTo = TARGET_TO.matcher(message);
            if (mTo.find()) {
                targetParty = mTo.group(1).trim();
            } else {
                java.util.regex.Matcher mFrom = TARGET_FROM.matcher(message);
                if (mFrom.find()) {
                    targetParty = mFrom.group(1).trim();
                }
            }

            // Reference / description
            java.util.regex.Matcher mRef = REF_PATTERN.matcher(message);
            if (mRef.find()) {
                // Pick first non-null capturing group among 2..4
                for (int i = 2; i <= 4; i++) {
                    if (i <= mRef.groupCount()) {
                        String g = mRef.group(i);
                        if (g != null && !g.isEmpty()) {
                            description = g;
                            break;
                        }
                    }
                }
                if (description == null) {
                    description = mRef.group(0);
                }
            }

            // Inline date
            java.util.regex.Matcher mDate = DATE_INLINE.matcher(message);
            if (mDate.find()) {
                extractedDate = mDate.group(1);
            }
        }

        return new ClassificationResult(isFinancial, score, category,
                amount, currency, type, medium, targetParty, description, extractedDate);
    }

    /**
     * Quick check if message is likely financial
     *
     * @param message The SMS message body
     * @return true if classified as financial transaction
     */
    public static boolean isFinancialTransaction(String message) {
        return classify(message).isFinancial();
    }
}
