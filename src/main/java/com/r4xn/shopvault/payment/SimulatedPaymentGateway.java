package com.r4xn.shopvault.payment;

import com.r4xn.shopvault.config.AppProperties;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Stand-in provider used until a real one is wired in: it issues unguessable references and a
 * checkout URL; confirmations are sent with scripts/send-test-webhook.sh. To go live, add a
 * PaymentGateway for your provider annotated with
 * {@code @ConditionalOnProperty(name = "app.payments.provider", havingValue = "yourprovider")}
 * and set {@code PAYMENT_PROVIDER=yourprovider}.
 */
@Component
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "simulated", matchIfMissing = true)
public class SimulatedPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentGateway.class);

    private final SecureRandom random = new SecureRandom();
    private final String checkoutBaseUrl;

    public SimulatedPaymentGateway(AppProperties props, Environment env) {
        if (!env.matchesProfiles("dev | test")) {
            log.warn("Using the SIMULATED payment gateway: no real money is collected. "
                    + "Configure a real provider before taking orders.");
        }
        String base = props.payments().checkoutBaseUrl();
        this.checkoutBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Override
    public String createPayment(UUID orderId, long amountCents, String currency) {
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        return "pay_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public String checkoutUrl(String paymentReference) {
        return checkoutBaseUrl + "/" + paymentReference;
    }
}
