package com.r4xn.shopvault.order;

import com.r4xn.shopvault.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Stock reserved by unpaid orders is released after app.shop.payment-timeout. */
@Component
class OrderExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryJob.class);

    private final OrderService orders;
    private final Clock clock;
    private final Duration timeout;

    OrderExpiryJob(OrderService orders, Clock clock, AppProperties props) {
        this.orders = orders;
        this.clock = clock;
        this.timeout = props.shop().paymentTimeout();
    }

    @Scheduled(fixedDelayString = "${app.jobs.order-expiry-interval:PT5M}",
            initialDelayString = "${app.jobs.order-expiry-initial-delay:PT1M}")
    void expireUnpaidOrders() {
        int expired = orders.expireUnpaidOrders(clock.instant().minus(timeout));
        if (expired > 0) {
            log.info("Expired {} unpaid order(s) and released their stock", expired);
        }
    }
}
