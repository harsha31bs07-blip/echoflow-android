package com.echoflow.core.safety

import com.echoflow.core.model.Bounds
import com.echoflow.core.model.InputTypes
import com.echoflow.core.model.ScreenSnapshot
import com.echoflow.core.safety.SensitiveKind.LOGIN
import com.echoflow.core.safety.SensitiveKind.OPAQUE_UNKNOWN
import com.echoflow.core.safety.SensitiveKind.OTP
import com.echoflow.core.safety.SensitiveKind.PASSWORD
import com.echoflow.core.safety.SensitiveKind.PAYMENT
import com.echoflow.core.testing.screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenSafetyClassifierTest {
    private val classifier = ScreenSafetyClassifier(ownPackage = "com.echoflow")

    private fun assertKind(kind: SensitiveKind, s: ScreenSnapshot) {
        val v = classifier.classify(s)
        assertTrue(kind in v.kinds, "expected $kind but got ${v.summary()} / ${v.signals}")
    }

    private fun assertSafe(s: ScreenSnapshot) {
        val v = classifier.classify(s)
        assertFalse(v.isSensitive, "expected SAFE but got ${v.summary()}")
    }

    // ---------- PASSWORD ----------

    @Test fun `password flag trips`() = assertKind(PASSWORD, screen {
        text("Welcome back"); edit(hint = "Email"); edit(hint = "Enter", password = true); button("Continue")
    })

    @Test fun `password input type trips even without isPassword`() = assertKind(PASSWORD, screen {
        edit(hint = "Secret", inputType = InputTypes.TYPE_CLASS_TEXT or InputTypes.TYPE_TEXT_VARIATION_PASSWORD)
    })

    @Test fun `UPI PIN field trips`() = assertKind(PASSWORD, screen(pkg = "com.example.shop") {
        text("Enter UPI PIN"); edit(hint = "UPI PIN", inputType = InputTypes.TYPE_CLASS_NUMBER, maxLength = 6)
    })

    @Test fun `address pincode field is not a secret`() = assertSafe(screen {
        text("Add delivery address")
        edit(hint = "House / Flat no.")
        edit(hint = "Pincode", viewId = "in.swiggy.android:id/pin_code", inputType = InputTypes.TYPE_CLASS_NUMBER, maxLength = 6)
        edit(hint = "Receiver's phone number", inputType = InputTypes.TYPE_CLASS_PHONE)
        button("Save address")
    })

    // ---------- OTP ----------

    @Test fun `six single-char boxes trip OTP`() = assertKind(OTP, screen {
        text("Verify your number")
        repeat(6) { edit(inputType = InputTypes.TYPE_CLASS_NUMBER, maxLength = 1) }
    })

    @Test fun `numeric field next to OTP prompt trips`() = assertKind(OTP, screen {
        text("Enter the OTP sent to +91 98xxxx1234")
        edit(inputType = InputTypes.TYPE_CLASS_NUMBER, maxLength = 6)
    })

    @Test fun `resend OTP without a readable field still trips`() = assertKind(OTP, screen {
        text("We sent you a code"); text("Resend OTP in 00:25")
    })

    @Test fun `Hindi OTP prompt trips`() = assertKind(OTP, screen {
        text("ओटीपी दर्ज करें"); edit(inputType = InputTypes.TYPE_CLASS_NUMBER, maxLength = 4)
    })

    @Test fun `delivery OTP on order tracking does not trip`() = assertSafe(screen {
        text("Your order is on the way"); text("Delivery OTP: 4821"); text("Share OTP with your delivery partner only at the door")
    })

    // ---------- PAYMENT ----------

    @Test fun `payment options page trips`() = assertKind(PAYMENT, screen {
        text("Payment Options"); text("UPI"); text("Credit / Debit Cards"); text("Net Banking"); text("Wallets")
        text("Cash on Delivery"); button("Pay ₹349")
    })

    @Test fun `card entry form trips`() = assertKind(PAYMENT, screen(pkg = "in.amazon.mShop.android.shopping") {
        text("Add a credit or debit card"); edit(hint = "Card number"); edit(hint = "Expiry (MM/YY)"); edit(hint = "CVV", maxLength = 3)
    })

    @Test fun `three short payment option rows trip without a heading`() = assertKind(PAYMENT, screen {
        val a = container(clickable = true); text("UPI", a)
        val b = container(clickable = true); text("Cards", b)
        val c = container(clickable = true); text("Wallets", c)
    })

    @Test fun `cart with pay-using selector trips`() = assertKind(PAYMENT, screen(pkg = "com.application.zomato") {
        text("Margherita Pizza x 2"); text("Grand total ₹458"); text("Pay using"); text("Google Pay UPI"); button("Place Order")
    })

    @Test fun `payment app package trips`() = assertKind(PAYMENT, screen(pkg = "com.phonepe.app") { text("Enter amount") })

    @Test fun `payment gateway package prefix trips`() = assertKind(PAYMENT, screen(pkg = "com.razorpay.checkout") { text("Hello") })

    @Test fun `Hindi payment heading trips`() = assertKind(PAYMENT, screen { text("भुगतान विकल्प"); text("यूपीआई") })

    // ---------- CHECKOUT (commit button, no credentials) ----------

    private fun assertCheckout(s: ScreenSnapshot, amount: String? = null) {
        val v = classifier.classify(s)
        assertTrue(v.isCheckout, "expected CHECKOUT but got ${v.summary()}")
        assertEquals("CHECKOUT", v.label)
        if (amount != null) assertEquals(amount, v.checkout?.amount)
    }

    @Test fun `bare pay button is checkout, not a payment hand-off`() =
        assertCheckout(screen { text("Order summary"); button("Pay now") })

    @Test fun `swiggy-style cart with one-tap saved-card pay is checkout`() = assertCheckout(screen {
        text("Hugo (Fried Chicken & Mozza Sandwich)"); text("−"); text("1"); text("+")
        icon("Click here to change delivery address")
        icon("Currently paying using Debit card ####, Change payment method")
        icon("Pay ₹632 using Debit card")
    }, amount = "₹632")

    @Test fun `place order bag is checkout`() = assertCheckout(screen(pkg = "com.myntra.android") {
        text("BAG"); text("ADDRESS"); text("PAYMENT"); text("Men Running Shoes"); button("PLACE ORDER")
    })

    @Test fun `zomato cart with a pay-using summary and place order is checkout`() = assertCheckout(screen(pkg = "com.application.zomato") {
        text("Brik Oven"); text("Margherita Pizza"); text("Mini – 6 inches"); text("1"); button("Edit")
        text("Add more items"); text("Zomato Money Balance: ₹0 •"); button("Add Money")
        val pay = container(clickable = true); text("PAY USING", pay); text("Google Pay UPI", pay)
        val checkout = container(clickable = true); text("₹285.44", checkout); text("TOTAL", checkout); text("Place Order", checkout)
    }, amount = "₹285")

    @Test fun `a pay-using summary without a checkout button still trips`() =
        assertKind(PAYMENT, screen { val pay = container(clickable = true); text("PAY USING", pay); text("Google Pay UPI", pay) })

    @Test fun `payment options page with a pay button is still PAYMENT`() {
        val s = screen { text("Payment Options"); text("Preferred Payment"); icon("Saved card"); text("Pay₹632"); text("Google Pay") }
        assertKind(PAYMENT, s)
        assertFalse(classifier.classify(s).isCheckout, "PAYMENT wins over CHECKOUT")
    }

    @Test fun `a navigation tab called Pay is not checkout`() {
        val s = screen(pkg = "in.amazon.mShop.android.shopping") {
            add { com.echoflow.core.model.UiElement(it, className = "android.widget.TextView", text = "Pay", viewId = "in.amazon.mShop.android.shopping:id/tiles_nav_item_text", bounds = com.echoflow.core.model.Bounds(0, 0, 200, 100)) }
            text("Deliver to 560001")
        }
        assertEquals("SAFE", classifier.classify(s).label, classifier.classify(s).summary())
    }

    @Test fun `product page with buy now is not checkout`() {
        val v = classifier.classify(screen(pkg = "in.amazon.mShop.android.shopping") { text("Running Shoes"); button("Add to Cart"); button("Buy Now") })
        assertFalse(v.isCheckout, v.summary())
        assertEquals("SAFE", v.label)
    }

    @Test fun `amounts are read from pay buttons`() {
        fun amount(label: String) = SafetyLexicon.amountOf(com.echoflow.core.text.TextNormalizer.tokens(label))
        assertEquals("₹632", amount("Pay ₹632 using Debit card"))
        assertEquals("₹1,299", amount("Pay ₹1,299"))
        assertEquals("₹349", amount("Pay Rs. 349.00"))
        assertEquals(null, amount("Pay now"))
    }

    @Test fun `restaurant menu with bank and wallet offers is safe`() = assertSafe(screen {
        edit(hint = "Search for dishes")
        text("Flat ₹100 off with Paytm UPI above ₹499")
        text("10% off on HDFC Credit Cards | Use code HDFC10")
        text("Margherita Pizza"); text("₹199"); button("ADD")
        text("Garlic Bread"); text("₹99"); button("ADD")
    })

    @Test fun `product page with pay on delivery and EMI offers is safe`() = assertSafe(screen(pkg = "in.amazon.mShop.android.shopping") {
        text("Bank Offer: 10% instant discount on SBI Credit Card EMI transactions")
        text("No Cost EMI available on select cards. Please check EMI options")
        text("Pay on Delivery"); text("Free Delivery"); text("10 days Replacement")
        button("Add to Cart"); button("Buy Now")
    })

    @Test fun `home page with a wallet widget is safe`() = assertSafe(screen(pkg = "in.amazon.mShop.android.shopping") {
        edit(hint = "Search Amazon.in")
        text("Amazon Pay"); text("Scan any QR"); text("Pay bills"); text("Send money")
        text("Deals of the day")
    })

    @Test fun `cart with proceed to pay is safe`() = assertSafe(screen {
        text("Margherita Pizza"); text("₹398"); text("Bill Details"); text("Delivery fee ₹30"); button("Proceed to Pay")
    })

    @Test fun `bag with a checkout stepper mentioning PAYMENT is safe`() = assertSafe(screen(pkg = "com.myntra.android") {
        text("BAG"); text("ADDRESS"); text("PAYMENT"); text("Men Running Shoes"); button("PLACE ORDER")
    })

    // ---------- LOGIN ----------

    @Test fun `phone field with get OTP trips login`() = assertKind(LOGIN, screen {
        text("Login"); edit(hint = "Enter mobile number", inputType = InputTypes.TYPE_CLASS_PHONE); button("Get OTP")
    })

    @Test fun `login gate text trips`() = assertKind(LOGIN, screen {
        text("Almost there"); text("Login or Signup to place your order"); button("Continue with phone number")
    })

    @Test fun `Google account picker trips login`() = assertKind(LOGIN, screen(pkg = "com.google.android.gms") {
        text("Choose an account"); text("to continue to Swiggy")
    })

    @Test fun `Play services location dialog is safe`() = assertSafe(screen(pkg = "com.google.android.gms") {
        text("For a better experience, turn on device location"); button("No thanks"); button("Turn on")
    })

    @Test fun `truecaller sheet trips login`() = assertKind(LOGIN, screen(pkg = "com.truecaller") { button("Continue") })

    // ---------- OPAQUE ----------

    @Test fun `full-screen empty WebView is opaque`() = assertKind(OPAQUE_UNKNOWN, screen {
        node("android.webkit.WebView", bounds = Bounds(0, 0, 1080, 2200))
    })

    @Test fun `screen with nothing readable is opaque`() = assertKind(OPAQUE_UNKNOWN, screen {
        node("android.widget.FrameLayout"); node("android.view.View", clickable = true)
    })

    @Test fun `loading screen is not opaque`() = assertSafe(screen {
        node("android.widget.FrameLayout"); node("android.widget.ProgressBar")
    })

    @Test fun `readable WebView is judged by its content`() {
        val s = screen {
            val web = node("android.webkit.WebView", bounds = Bounds(0, 0, 1080, 2200))
            text("Choose a payment method", web); text("UPI", web); text("Cards", web); text("Netbanking", web)
        }
        val v = classifier.classify(s)
        assertEquals(setOf(PAYMENT), v.kinds, v.summary())
    }

    // ---------- plumbing ----------

    @Test fun `own overlay text is ignored`() = assertSafe(screen {
        text("Margherita Pizza"); overlayText("PAYMENT — pay button: Pay now", ownPkg = "com.echoflow")
    })

    @Test fun `typed text in fields is never used as evidence`() = assertSafe(screen {
        edit(hint = "Search for dishes", typed = "password otp pay now card number")
        text("Popular dishes")
    })

    @Test fun `summary names kinds and evidence`() {
        val v = classifier.classify(screen { text("Payment Options") })
        assertEquals("PAYMENT — payment-phrase: payment options; payment-option: payment", v.summary())
    }
}
