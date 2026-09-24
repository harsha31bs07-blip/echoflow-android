package com.echoflow.core.safety

import com.echoflow.core.text.phrases

/**
 * Every word list SafetyGuard uses, in one place so it can be reviewed and extended with
 * dumped fixtures. English and Hindi (Devanagari) for now.
 *
 * Weighting: a STRONG phrase trips its kind on its own; MEDIUM phrases only count from short
 * labels (option rows, headings) and need three distinct hits. STRONG text phrases also only
 * count from short labels, so offer banners like "10% off with Paytm UPI" on menu or product
 * pages don't trip it.
 */
object SafetyLexicon {
    /** Labels longer than this are treated as prose (offers, descriptions), not headings/options. */
    const val SHORT_LABEL_MAX_TOKENS = 6
    const val OPTION_LABEL_MAX_TOKENS = 4
    /** OTP prompts are distinctive enough to match inside sentences up to this length. */
    const val PROSE_MAX_TOKENS = 14

    // ---- PAYMENT ----
    val paymentStrong = phrases(
        "payment options", "payment option", "payment methods", "payment method",
        "select payment", "choose payment", "payment mode", "pay using", "pay with",
        "complete payment", "complete your payment", "enter card details", "add new card",
        "card number", "cvv", "upi id", "enter upi", "net banking", "netbanking",
        "saved cards", "pay securely",
        "भुगतान विकल्प", "भुगतान का तरीका", "कार्ड नंबर",
    )
    val paymentMedium = phrases(
        "upi", "cards", "credit card", "debit card", "wallets", "wallet", "pay later",
        "cash on delivery", "pay on delivery", "emi", "gift card", "payment", "payments",
        "bhim", "gpay", "google pay", "phonepe", "paytm", "amazon pay", "cred pay", "mobikwik",
        "भुगतान", "यूपीआई",
    )
    /** Non-English pay-button labels; English "pay" is handled by [isPayButton]. */
    val payButtonStart = phrases("पे करें", "भुगतान करें")

    /** "Pay", "Pay now", "Pay ₹349", "Pay Rs 349", "Pay securely" — but not "Pay on delivery" or "Pay bills". */
    fun isPayButton(tokens: List<String>): Boolean {
        if (payButtonStart.any { it.startsOf(tokens) }) return true
        if (tokens.firstOrNull() != "pay") return false
        if (tokens.size == 1) return true
        val next = tokens[1]
        return next in setOf("now", "₹", "rs", "inr", "securely", "and") || next.first().isDigit()
    }
    /** Editable-field labels (hint, content description, view id) that are payment credentials. */
    val paymentFieldLabels = phrases(
        "card number", "card no", "cvv", "cvc", "expiry", "exp date", "mm yy", "valid thru",
        "name on card", "cardholder", "card holder", "upi id", "vpa",
    )

    // ---- OTP ----
    val otpTerms = phrases(
        "otp", "one time password", "one time code", "verification code", "security code",
        "enter code", "enter the code", "ओटीपी", "सत्यापन कोड",
    )
    val otpScreenPhrases = phrases(
        "enter otp", "enter the otp", "digit otp", "enter verification code", "resend otp", "resend code",
        "otp sent", "sent an otp", "sent a code", "sent you a code", "verify otp", "ओटीपी दर्ज करें",
    )

    // ---- PASSWORD ----
    val passwordFieldLabels = phrases(
        "password", "passcode", "pin", "mpin", "upi pin", "atm pin", "पासवर्ड", "पिन",
    )
    /** Postal "PIN code" is an address field, not a secret. */
    val pinCodeExclusions = phrases("pin code", "pincode", "zip", "postal")

    // ---- LOGIN ----
    val loginStrong = phrases(
        "login to continue", "log in to continue", "sign in to continue", "login or sign up",
        "login or signup", "log in or sign up", "login signup", "login to place", "login to proceed",
        "sign in to your account", "continue with google", "continue with phone", "choose an account",
        "लॉग इन करें", "साइन इन करें",
    )
    val loginHeadings = phrases("login", "log in", "sign in", "sign up", "लॉग इन", "साइन इन")
    val loginVerbs = phrases(
        "login", "log in", "sign in", "get otp", "send otp", "request otp", "verify",
        "लॉग इन", "साइन इन",
    )
    val identityFieldLabels = phrases(
        "phone number", "mobile number", "mobile", "phone", "email", "e mail", "username",
        "user name", "user id", "email or mobile", "मोबाइल नंबर", "ईमेल",
    )

    // ---- Packages ----
    /** Exact package names or prefixes ending in '.' */
    val paymentPackages = listOf(
        "net.one97.paytm", "com.phonepe.app", "com.google.android.apps.nbu.paisa.user",
        "in.org.npci.upiapp", "com.dreamplug.androidapp", "com.mobikwik_new",
        "com.freecharge.android", "com.sbi.lotusintouch", "com.csam.icici.bank.imobile",
        "com.snapwork.hdfc", "com.axis.mobile", "com.msf.kbank.mobile", "com.enstage.wibmo.hdfc",
        "com.razorpay.", "in.juspay.", "com.payu.",
    )
    val loginPackages = listOf(
        "com.google.android.gms", "com.google.android.gsf.login", "com.truecaller",
    )

    // ---- Actions ----
    /** Taps that move *toward* payment without committing money; the next screen is checked. */
    val navigateToPayment = phrases(
        "proceed to pay", "proceed to payment", "continue to payment", "proceed to checkout",
        "go to checkout", "checkout", "check out", "proceed to buy", "select payment method",
    )
    /** Irreversible commits (plus pay buttons, see [isPayButton]). Never tapped by automation, even if demonstrated. */
    val commitStart = phrases(
        "place order", "place your order", "confirm order", "confirm and pay",
        "complete purchase", "complete order", "complete payment", "submit order", "buy now",
        "slide to pay", "swipe to pay", "make payment", "order and pay",
        "भुगतान करें", "पे करें", "ऑर्डर करें",
    )
    val commitAnywhere = phrases(
        "place order", "place your order", "confirm order", "confirm and pay", "slide to pay",
        "swipe to pay", "buy now",
    )
    val destructive = phrases(
        "remove", "delete", "clear cart", "clear all", "empty cart", "cancel order",
        "cancel booking", "cancel subscription", "log out", "logout", "sign out", "deactivate",
        "discard", "start afresh", "replace cart", "unsubscribe", "reset", "हटाएं",
    )
}
