package io.nats.service;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.Subscription;
import io.nats.client.support.DateTimeUtils;

import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SERVICE IS AN EXPERIMENTAL API SUBJECT TO CHANGE
 */
class EndpointContext {
    private static final String QGROUP = "q";

    private final Connection conn;
    private final ServiceEndpoint se;
    private final ServiceMessageHandler handler;
    private final boolean recordStats;
    private final String qGroup;

    private final boolean internalDispatcher;
    private final Dispatcher dispatcher;

    private Subscription sub;

    private ZonedDateTime started;
    private String lastError;
    private final AtomicLong numRequests;
    private final AtomicLong numErrors;
    private final AtomicLong processingTime;

    EndpointContext(Connection conn, Dispatcher internalDispatcher, boolean recordStats, ServiceEndpoint se) {
        this.conn = conn;
        this.se = se;
        handler = se.getHandler();
        this.recordStats = recordStats;
        qGroup = recordStats ? QGROUP : null;

        if (se.getDispatcher() == null) {
            dispatcher = internalDispatcher;
            this.internalDispatcher = true;
        }
        else {
            dispatcher = se.getDispatcher();
            this.internalDispatcher = false;
        }

        numRequests = new AtomicLong();
        numErrors = new AtomicLong();
        processingTime = new AtomicLong();
        started = DateTimeUtils.gmtNow();
    }

    void start() {
        sub = qGroup == null
            ? dispatcher.subscribe(se.getSubject(), this::onMessage)
            : dispatcher.subscribe(se.getSubject(), qGroup, this::onMessage);
        started = DateTimeUtils.gmtNow();
    }

    public void onMessage(Message msg) throws InterruptedException {
        long start = System.nanoTime();
        ServiceMessage smsg = new ServiceMessage(msg);
        try {
            if (recordStats) {
                numRequests.incrementAndGet();
            }
            handler.onMessage(smsg);
        }
        catch (Throwable t) {
            if (recordStats) {
                numErrors.incrementAndGet();
                lastError = t.toString();
            }
            try {
                smsg.respondStandardError(conn, lastError, 500);
            } catch (RuntimeException ignore) {}
        }
        finally {
            if (recordStats) {
                processingTime.addAndGet(System.nanoTime() - start);
            }
        }
    }

    EndpointResponse getEndpointStats() {
        return new EndpointResponse(
            se.getEndpoint().getName(),
            se.getSubject(),
            numRequests.get(),
            numErrors.get(),
            processingTime.get(),
            lastError,
            se.getStatsDataSupplier() == null ? null : se.getStatsDataSupplier().get(),
            started);
    }

    void reset() {
        numRequests.set(0);
        numErrors.set(0);
        processingTime.set(0);
        lastError = null;
        started = DateTimeUtils.gmtNow();
    }

    boolean isNotInternalDispatcher() {
        return !internalDispatcher;
    }

    Subscription getSub() {
        return sub;
    }
}
