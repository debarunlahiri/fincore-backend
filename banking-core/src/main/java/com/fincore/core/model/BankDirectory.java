package com.fincore.core.model;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Small built-in directory standing in for the RBI IFSC register and the NPCI UPI handle list. It
 * lets the app show which bank an IFSC or UPI id belongs to and reject malformed codes. Unknown but
 * well-formed codes are accepted.
 */
public final class BankDirectory {

    /** 4 letters, a literal zero, then 6 letters or digits (e.g. HDFC0001234). */
    public static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");

    public static final Pattern ACCOUNT_NUMBER = Pattern.compile("^\\d{9,18}$");
    public static final Pattern UPI_ID =
            Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9.\\-_]{1,63}@[a-zA-Z][a-zA-Z0-9]{1,31}$");

    private static final Map<String, String> BANKS =
            Map.ofEntries(
                    Map.entry("SBIN", "State Bank of India"),
                    Map.entry("HDFC", "HDFC Bank"),
                    Map.entry("ICIC", "ICICI Bank"),
                    Map.entry("UTIB", "Axis Bank"),
                    Map.entry("KKBK", "Kotak Mahindra Bank"),
                    Map.entry("PUNB", "Punjab National Bank"),
                    Map.entry("BARB", "Bank of Baroda"),
                    Map.entry("CNRB", "Canara Bank"),
                    Map.entry("UBIN", "Union Bank of India"),
                    Map.entry("IDIB", "Indian Bank"),
                    Map.entry("BKID", "Bank of India"),
                    Map.entry("YESB", "Yes Bank"),
                    Map.entry("INDB", "IndusInd Bank"),
                    Map.entry("IDFB", "IDFC First Bank"),
                    Map.entry("FDRL", "Federal Bank"),
                    Map.entry("FINC", "FinCore Bank"));

    private static final Map<String, String> UPI_HANDLES =
            Map.ofEntries(
                    Map.entry("okhdfcbank", "HDFC Bank"),
                    Map.entry("hdfcbank", "HDFC Bank"),
                    Map.entry("oksbi", "State Bank of India"),
                    Map.entry("sbi", "State Bank of India"),
                    Map.entry("okicici", "ICICI Bank"),
                    Map.entry("icici", "ICICI Bank"),
                    Map.entry("okaxis", "Axis Bank"),
                    Map.entry("axisbank", "Axis Bank"),
                    Map.entry("ybl", "PhonePe (Yes Bank)"),
                    Map.entry("ibl", "PhonePe (ICICI Bank)"),
                    Map.entry("paytm", "Paytm Payments Bank"),
                    Map.entry("apl", "Amazon Pay"),
                    Map.entry("upi", "BHIM UPI"),
                    Map.entry("fincore", "FinCore Bank"));

    private BankDirectory() {}

    public static boolean isValidIfsc(String ifsc) {
        return ifsc != null && IFSC.matcher(ifsc).matches();
    }

    /** The bank for a well-formed IFSC; empty when the bank code is not in the directory. */
    public static Optional<String> bankForIfsc(String ifsc) {
        return isValidIfsc(ifsc)
                ? Optional.ofNullable(BANKS.get(ifsc.substring(0, 4)))
                : Optional.empty();
    }

    public static String providerForHandle(String upiId) {
        String handle = upiId.substring(upiId.indexOf('@') + 1).toLowerCase();
        return UPI_HANDLES.getOrDefault(handle, "UPI");
    }
}
