package com.shop.billing;

import com.shop.mail.ReceiptMailer;

public final class InvoiceService {
    private final ReceiptMailer mailer = new ReceiptMailer();

    public void paid(String email, String orderId) {
        mailer.send(email, orderId);
    }
}
