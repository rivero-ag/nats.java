// Copyright 2020 The NATS Authors
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.nats.client.impl;

import io.nats.client.*;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.nats.client.api.ConsumerConfiguration.builder;
import static io.nats.client.support.Status.*;
import static org.junit.jupiter.api.Assertions.*;

public class JetStreamPullTests extends JetStreamTestBase {

    @Test
    public void testFetch() throws Exception {
        runInJsServer(nc -> {
            // Create our JetStream context.
            JetStream js = nc.jetStream();

            // create the stream.
            createDefaultTestStream(nc);

            long fetchMs = 3000;
            Duration fetchDur = Duration.ofMillis(fetchMs);
            Duration ackWaitDur = Duration.ofMillis(fetchMs * 2);

            ConsumerConfiguration cc = ConsumerConfiguration.builder()
                .ackWait(ackWaitDur)
                .build();

            PullSubscribeOptions options = PullSubscribeOptions.builder()
                .durable(DURABLE)
                .configuration(cc)
                .build();

            JetStreamSubscription sub = js.subscribe(SUBJECT, options);
            assertSubscription(sub, STREAM, DURABLE, null, true);
            nc.flush(Duration.ofSeconds(1)); // flush outgoing communication with/to the server

            List<Message> messages = sub.fetch(10, fetchDur);
            validateRead(0, messages.size());
            messages.forEach(Message::ack);
            sleep(ackWaitDur.toMillis()); // let the pull expire

            jsPublish(js, SUBJECT, "A", 10);
            messages = sub.fetch(10, fetchDur);
            validateRead(10, messages.size());
            messages.forEach(Message::ack);

            jsPublish(js, SUBJECT, "B", 20);
            messages = sub.fetch(10, fetchDur);
            validateRead(10, messages.size());
            messages.forEach(Message::ack);

            messages = sub.fetch(10, fetchDur);
            validateRead(10, messages.size());
            messages.forEach(Message::ack);

            jsPublish(js, SUBJECT, "C", 5);
            messages = sub.fetch(10, fetchDur);
            validateRead(5, messages.size());
            messages.forEach(Message::ack);
            sleep(fetchMs); // let the pull expire

            jsPublish(js, SUBJECT, "D", 15);
            messages = sub.fetch(10, fetchDur);
            validateRead(10, messages.size());
            messages.forEach(Message::ack);

            messages = sub.fetch(10, fetchDur);
            validateRead(5, messages.size());
            messages.forEach(Message::ack);

            jsPublish(js, SUBJECT, "E", 10);
            messages = sub.fetch(10, fetchDur);
            validateRead(10, messages.size());
            sleep(ackWaitDur.toMillis()); // let the acks wait expire, pull will also expire it's shorter

            // message were not ack'ed
            messages = sub.fetch(10, fetchDur);
            validateRead(10, messages.size());
            messages.forEach(Message::ack);

            assertThrows(IllegalArgumentException.class, () -> sub.fetch(10, null));
            assertThrows(IllegalArgumentException.class, () -> sub.fetch(10, Duration.ofSeconds(-1)));
        });
    }

    @Test
    public void testIterate() throws Exception {
        runInJsServer(nc -> {
            // Create our JetStream context.
            JetStream js = nc.jetStream();

            // create the stream.
            createDefaultTestStream(nc);

            long fetchMs = 5000;
            Duration fetchDur = Duration.ofMillis(fetchMs);
            Duration ackWaitDur = Duration.ofMillis(fetchMs * 2);

            ConsumerConfiguration cc = ConsumerConfiguration.builder()
                    .ackWait(ackWaitDur)
                    .build();

            PullSubscribeOptions options = PullSubscribeOptions.builder()
                    .durable(DURABLE)
                    .configuration(cc)
                    .build();

            JetStreamSubscription sub = js.subscribe(SUBJECT, options);
            assertSubscription(sub, STREAM, DURABLE, null, true);
            nc.flush(Duration.ofSeconds(1)); // flush outgoing communication with/to the server

            Iterator<Message> iterator = sub.iterate(10, fetchDur);
            List<Message> messages = readMessages(iterator);
            validateRead(0, messages.size());
            messages.forEach(Message::ack);

            jsPublish(js, SUBJECT, "A", 10);
            iterator = sub.iterate(10, fetchDur);
            messages = readMessages(iterator);
            validateRead(10, messages.size());
            messages.forEach(Message::ack);

            jsPublish(js, SUBJECT, "B", 20);
            iterator = sub.iterate(10, fetchDur);
            messages = readMessages(iterator);
            validateRead(10, messages.size());
            messages.forEach(Message::ack);

            iterator = sub.iterate(10, fetchDur);
            messages = readMessages(iterator);
            validateRead(10, messages.size());
            messages.forEach(Message::ack);

            jsPublish(js, SUBJECT, "C", 5);
            iterator = sub.iterate(10, fetchDur);
            messages = readMessages(iterator);
            validateRead(5, messages.size());
            messages.forEach(Message::ack);
            sleep(fetchMs); // give time for the pull to expire

            jsPublish(js, SUBJECT, "D", 15);
            iterator = sub.iterate(10, fetchDur);
            messages = readMessages(iterator);
            validateRead(10, messages.size());
            messages.forEach(Message::ack);

            iterator = sub.iterate(10, fetchDur);
            messages = readMessages(iterator);
            validateRead(5, messages.size());
            messages.forEach(Message::ack);
            sleep(fetchMs); // give time for the pull to expire

            jsPublish(js, SUBJECT, "E", 10);
            iterator = sub.iterate(10, fetchDur);
            messages = readMessages(iterator);
            validateRead(10, messages.size());
            sleep(ackWaitDur.toMillis()); // give time for the pull and the ack wait to expire

            iterator = sub.iterate(10, fetchDur);
            messages = readMessages(iterator);
            validateRead(10, messages.size());
            messages.forEach(Message::ack);

            jsPublish(js, SUBJECT, "F", 1);
            iterator = sub.iterate(1, fetchDur);
            iterator.hasNext(); // calling hasNext twice in a row is for coverage
            iterator.hasNext(); // calling hasNext twice in a row is for coverage
        });
    }

    @Test
    public void testBasic() throws Exception {
        runInJsServer(nc -> {
            // Create our JetStream context.
            JetStream js = nc.jetStream();

            // create the stream.
            createDefaultTestStream(nc);

            // Build our subscription options.
            PullSubscribeOptions options = PullSubscribeOptions.builder().durable(DURABLE).build();

            // Subscribe synchronously.
            JetStreamSubscription sub = js.subscribe(SUBJECT, options);
            assertSubscription(sub, STREAM, DURABLE, null, true);
            nc.flush(Duration.ofSeconds(1)); // flush outgoing communication with/to the server

            // publish some amount of messages, but not entire pull size
            jsPublish(js, SUBJECT, "A", 4);

            // start the pull
            sub.pull(10);

            // read what is available, expect 4
            List<Message> messages = readMessagesAck(sub);
            int total = messages.size();
            validateRedAndTotal(4, messages.size(), 4, total);

            // publish some more covering our initial pull and more
            jsPublish(js, SUBJECT, "B", 10);

            // read what is available, expect 6 more
            messages = readMessagesAck(sub);
            total += messages.size();
            validateRedAndTotal(6, messages.size(), 10, total);

            // read what is available, should be zero since we didn't re-pull
            messages = readMessagesAck(sub);
            total += messages.size();
            validateRedAndTotal(0, messages.size(), 10, total);

            // re-issue the pull
            sub.pull(PullRequestOptions.builder(10).build()); // coverage of the build api

            // read what is available, should be 4 left over
            messages = readMessagesAck(sub);
            total += messages.size();
            validateRedAndTotal(4, messages.size(), 14, total);

            // publish some more
            jsPublish(js, SUBJECT, "C", 10);

            // read what is available, should be 6 since we didn't finish the last batch
            messages = readMessagesAck(sub);
            total += messages.size();
            validateRedAndTotal(6, messages.size(), 20, total);

            // re-issue the pull, but a smaller amount
            sub.pull(2);

            // read what is available, should be 5 since we changed the pull size
            messages = readMessagesAck(sub);
            total += messages.size();
            validateRedAndTotal(2, messages.size(),22, total);

            // re-issue the pull, since we got the full batch size
            sub.pull(2);

            // read what is available, should be zero since we didn't re-pull
            messages = readMessagesAck(sub);
            total += messages.size();
            validateRedAndTotal(2, messages.size(), 24, total);

            // re-issue the pull, any amount there are no messages
            sub.pull(1);

            // read what is available, there are none
            messages = readMessagesAck(sub);
            total += messages.size();
            validateRedAndTotal(0, messages.size(), 24, total);

            // publish some more to test null timeout
            jsPublish(js, SUBJECT, "D", 10);
            sub = js.subscribe(SUBJECT, PullSubscribeOptions.builder().durable(durable(2)).build());
            sub.pull(10);
            sleep(500);
            messages = readMessagesAck(sub, null);
            validateRedAndTotal(10, messages.size(), 10, messages.size());

            // publish some more to test never timeout
            jsPublish(js, SUBJECT, "E", 10);
            sub = js.subscribe(SUBJECT, PullSubscribeOptions.builder().durable(durable(2)).build());
            sub.pull(10);
            sleep(500);
            messages = readMessagesAck(sub, Duration.ZERO, 10);
            validateRedAndTotal(10, messages.size(), 10, messages.size());
        });
    }

    @Test
    public void testNoWait() throws Exception {
        runInJsServer(nc -> {
            // Create our JetStream context.
            JetStream js = nc.jetStream();

            // create the stream.
            createDefaultTestStream(nc);

            // Build our subscription options.
            PullSubscribeOptions options = PullSubscribeOptions.builder().durable(DURABLE).build();

            // Subscribe synchronously.
            JetStreamSubscription sub = js.subscribe(SUBJECT, options);
            assertSubscription(sub, STREAM, DURABLE, null, true);
            nc.flush(Duration.ofSeconds(1)); // flush outgoing communication with/to the server

            // publish 10 messages
            // no wait, batch size 10, there are 10 messages, we will read them all and not trip nowait
            jsPublish(js, SUBJECT, "A", 10);
            sub.pullNoWait(10);
            List<Message> messages = readMessagesAck(sub);
            assertEquals(10, messages.size());
            assertAllJetStream(messages);

            // publish 20 messages
            // no wait, batch size 10, there are 20 messages, we will read 10
            jsPublish(js, SUBJECT, "B", 20);
            sub.pullNoWait(10);
            messages = readMessagesAck(sub);
            assertEquals(10, messages.size());

            // there are still ten messages
            // no wait, batch size 10, there are 20 messages, we will read 10
            sub.pullNoWait(10);
            messages = readMessagesAck(sub);
            assertEquals(10, messages.size());

            // publish 5 messages
            // no wait, batch size 10, there are 5 messages, we WILL trip nowait
            jsPublish(js, SUBJECT, "C", 5);
            sub.pullNoWait(10);
            messages = readMessagesAck(sub);
            assertEquals(5, messages.size());

            // publish 12 messages
            // no wait, batch size 10, there are more than batch messages we will read 10
            jsPublish(js, SUBJECT, "D", 12);
            sub.pullNoWait(10);
            messages = readMessagesAck(sub);
            assertEquals(10, messages.size());

            // 2 messages left
            // no wait, less than batch size will trip nowait
            sub.pullNoWait(10);
            messages = readMessagesAck(sub);
            assertEquals(2, messages.size());
        });
    }

    @Test
    public void testPullExpires() throws Exception {
        runInJsServer(nc -> {
            // Create our JetStream context.
            JetStream js = nc.jetStream();

            // create the stream.
            createDefaultTestStream(nc);

            // Build our subscription options.
            PullSubscribeOptions options = PullSubscribeOptions.builder().durable(DURABLE).build();

            // Subscribe synchronously.
            JetStreamSubscription sub = js.subscribe(SUBJECT, options);
            assertSubscription(sub, STREAM, DURABLE, null, true);
            nc.flush(Duration.ofSeconds(1)); // flush outgoing communication with/to the server

            long expires = 500; // millis

            // publish 10 messages
            jsPublish(js, SUBJECT, "A", 5);
            sub.pullExpiresIn(10, Duration.ofMillis(expires)); // using Duration version here
            List<Message> messages = readMessagesAck(sub);
            assertEquals(5, messages.size());
            assertAllJetStream(messages);
            sleep(expires); // make sure the pull actually expires

            jsPublish(js, SUBJECT, "B", 10);
            sub.pullExpiresIn(10, Duration.ofMillis(expires)); // using Duration version here
            messages = readMessagesAck(sub);
            assertEquals(10, messages.size());
            sleep(expires); // make sure the pull actually expires

            jsPublish(js, SUBJECT, "C", 5);
            sub.pullExpiresIn(10, Duration.ofMillis(expires)); // using Duration version here
            messages = readMessagesAck(sub);
            assertEquals(5, messages.size());
            assertAllJetStream(messages);
            sleep(expires); // make sure the pull actually expires

            jsPublish(js, SUBJECT, "D", 10);
            sub.pull(10);
            messages = readMessagesAck(sub);
            assertEquals(10, messages.size());

            jsPublish(js, SUBJECT, "E", 5);
            sub.pullExpiresIn(10, expires); // using millis version here
            messages = readMessagesAck(sub);
            assertEquals(5, messages.size());
            assertAllJetStream(messages);
            sleep(expires); // make sure the pull actually expires

            jsPublish(js, SUBJECT, "F", 10);
            sub.pullNoWait(10);
            messages = readMessagesAck(sub);
            assertEquals(10, messages.size());

            jsPublish(js, SUBJECT, "G", 5);
            sub.pullExpiresIn(10, expires); // using millis version here
            messages = readMessagesAck(sub);
            assertEquals(5, messages.size());
            assertAllJetStream(messages);
            sleep(expires); // make sure the pull actually expires

            jsPublish(js, SUBJECT, "H", 10);
            messages = sub.fetch(10, expires);
            assertEquals(10, messages.size());
            assertAllJetStream(messages);

            jsPublish(js, SUBJECT, "I", 5);
            sub.pullExpiresIn(10, expires);
            messages = readMessagesAck(sub);
            assertEquals(5, messages.size());
            assertAllJetStream(messages);
            sleep(expires); // make sure the pull actually expires

            jsPublish(js, SUBJECT, "J", 10);
            Iterator<Message> i = sub.iterate(10, expires);
            int count = 0;
            while (i.hasNext()) {
                assertIsJetStream(i.next());
                ++count;
            }
            assertEquals(10, count);

            assertThrows(IllegalArgumentException.class, () -> sub.pullExpiresIn(10, null));
            assertThrows(IllegalArgumentException.class, () -> sub.pullExpiresIn(10, Duration.ofSeconds(-1)));
            assertThrows(IllegalArgumentException.class, () -> sub.pullExpiresIn(10, -1000));
        });
    }

    @Test
    public void testAckNak() throws Exception {
        runInJsServer(nc -> {
            // Create our JetStream context.
            JetStream js = nc.jetStream();

            // create the stream.
            createDefaultTestStream(nc);

            PullSubscribeOptions pso = PullSubscribeOptions.builder().durable(DURABLE).build();
            JetStreamSubscription sub = js.subscribe(SUBJECT, pso);
            nc.flush(Duration.ofSeconds(1)); // flush outgoing communication with/to the server

            // NAK
            jsPublish(js, SUBJECT, "NAK", 1);

            sub.pull(1);

            Message message = sub.nextMessage(Duration.ofSeconds(1));
            assertNotNull(message);
            String data = new String(message.getData());
            assertEquals("NAK1", data);
            message.nak();

            sub.pull(1);
            message = sub.nextMessage(Duration.ofSeconds(1));
            assertNotNull(message);
            data = new String(message.getData());
            assertEquals("NAK1", data);
            message.ack();

            sub.pull(1);
            assertNull(sub.nextMessage(Duration.ofSeconds(1)));
        });
    }

    @Test
    public void testAckTerm() throws Exception {
        runInJsServer(nc -> {
            // Create our JetStream context.
            JetStream js = nc.jetStream();

            // create the stream.
            createDefaultTestStream(nc);

            PullSubscribeOptions pso = PullSubscribeOptions.builder().durable(DURABLE).build();
            JetStreamSubscription sub = js.subscribe(SUBJECT, pso);
            nc.flush(Duration.ofSeconds(1)); // flush outgoing communication with/to the server

            // TERM
            jsPublish(js, SUBJECT, "TERM", 1);

            sub.pull(1);
            Message message = sub.nextMessage(Duration.ofSeconds(1));
            assertNotNull(message);
            String data = new String(message.getData());
            assertEquals("TERM1", data);
            message.term();

            sub.pull(1);
            assertNull(sub.nextMessage(Duration.ofSeconds(1)));
        });
    }

    @Test
    public void testAckReplySyncCoverage() throws Exception {
        runInJsServer(nc -> {
            // create the stream.
            createDefaultTestStream(nc);

            // Create our JetStream context.
            JetStream js = nc.jetStream();

            JetStreamSubscription sub = js.subscribe(SUBJECT);
            nc.flush(Duration.ofSeconds(1)); // flush outgoing communication with/to the server

            jsPublish(js, SUBJECT, "COVERAGE", 1);

            Message message = sub.nextMessage(Duration.ofSeconds(1));
            assertNotNull(message);

            NatsJetStreamMessage njsm = (NatsJetStreamMessage)message;

            njsm.replyTo = "$JS.ACK.stream.LS0k4eeN.1.1.1.1627472530542070600.0";

            assertThrows(TimeoutException.class, () -> njsm.ackSync(Duration.ofSeconds(1)));
        });
    }

    @Test
    public void testAckWaitTimeout() throws Exception {
        runInJsServer(nc -> {
            // create the stream.
            createDefaultTestStream(nc);

            // Create our JetStream context.
            JetStream js = nc.jetStream();

            ConsumerConfiguration cc = ConsumerConfiguration.builder()
                .ackWait(1500)
                .build();
            PullSubscribeOptions pso = PullSubscribeOptions.builder()
                .durable(DURABLE)
                .configuration(cc)
                .build();

            JetStreamSubscription sub = js.subscribe(SUBJECT, pso);
            nc.flush(Duration.ofSeconds(1)); // flush outgoing communication with/to the server

            // Ack Wait timeout
            jsPublish(js, SUBJECT, "WAIT", 2);

            sub.pull(2);
            Message m = sub.nextMessage(1000);
            assertNotNull(m);
            assertEquals("WAIT1", new String(m.getData()));

            m = sub.nextMessage(1000);
            assertNotNull(m);
            assertEquals("WAIT2", new String(m.getData()));

            sleep(2000);

            sub.pull(2);
            m = sub.nextMessage(1000);
            assertNotNull(m);
            assertEquals("WAIT1", new String(m.getData()));
            m.ack();

            m = sub.nextMessage(1000);
            assertNotNull(m);
            assertEquals("WAIT2", new String(m.getData()));
            m.ack();

            sub.pull(2);
            m = sub.nextMessage(1000);
            assertNull(m);
        });
    }

    @Test
    public void testDurable() throws Exception {
        runInJsServer(nc -> {
            // create the stream.
            createDefaultTestStream(nc);

            // Create our JetStream context.
            JetStream js = nc.jetStream();

            // Build our subscription options normally
            PullSubscribeOptions options1 = PullSubscribeOptions.builder().durable(DURABLE).build();
            _testDurable(js, () -> js.subscribe(SUBJECT, options1));

            // bind long form
            PullSubscribeOptions options2 = PullSubscribeOptions.builder()
                .stream(STREAM)
                .durable(DURABLE)
                .bind(true)
                .build();
            _testDurable(js, () -> js.subscribe(null, options2));

            // bind short form
            PullSubscribeOptions options3 = PullSubscribeOptions.bind(STREAM, DURABLE);
            _testDurable(js, () -> js.subscribe(null, options3));
        });
    }

    private void _testDurable(JetStream js, SubscriptionSupplier supplier) throws IOException, JetStreamApiException, InterruptedException {
        jsPublish(js, SUBJECT, 2);

        JetStreamSubscription sub = supplier.get();

        // start the pull
        sub.pullNoWait(4);

        List<Message> messages = readMessagesAck(sub);
        validateRedAndTotal(2, messages.size(), 2, 2);

        sub.unsubscribe();
    }

    private interface SubscriptionSupplier {
        JetStreamSubscription get() throws IOException, JetStreamApiException;
    }

    @Test
    public void testPullRequestOptionsBuilder() {
        assertThrows(IllegalArgumentException.class, () -> PullRequestOptions.builder(0).build());
        assertThrows(IllegalArgumentException.class, () -> PullRequestOptions.builder(-1).build());

        PullRequestOptions pro = PullRequestOptions.builder(11).build();
        assertEquals(11, pro.getBatchSize());
        assertEquals(0, pro.getMaxBytes());
        assertNull(pro.getExpiresIn());
        assertNull(pro.getIdleHeartbeat());
        assertFalse(pro.isNoWait());

        pro = PullRequestOptions.noWait(21).build();
        assertEquals(21, pro.getBatchSize());
        assertEquals(0, pro.getMaxBytes());
        assertNull(pro.getExpiresIn());
        assertNull(pro.getIdleHeartbeat());
        assertTrue(pro.isNoWait());

        pro = PullRequestOptions.builder(31)
            .maxBytes(32)
            .expiresIn(33)
            .idleHeartbeat(34)
            .noWait()
            .build();
        assertEquals(31, pro.getBatchSize());
        assertEquals(32, pro.getMaxBytes());
        assertEquals(33, pro.getExpiresIn().toMillis());
        assertEquals(34, pro.getIdleHeartbeat().toMillis());
        assertTrue(pro.isNoWait());

        pro = PullRequestOptions.builder(41)
            .expiresIn(Duration.ofMillis(43))
            .idleHeartbeat(Duration.ofMillis(44))
            .noWait(false) // just coverage of this method
            .build();
        assertEquals(41, pro.getBatchSize());
        assertEquals(0, pro.getMaxBytes());
        assertEquals(43, pro.getExpiresIn().toMillis());
        assertEquals(44, pro.getIdleHeartbeat().toMillis());
        assertFalse(pro.isNoWait());
    }

    interface ConflictSetup {
        JetStreamSubscription setup(Connection nc, JetStreamManagement jsm, JetStream js, TestHandler handler) throws Exception;
    }

    private boolean versionIsBefore(Connection nc, String targetVersion) {
        return targetVersion != null && nc.getServerInfo().isOlderThanVersion(targetVersion);
    }

    interface BuilderCustomizer {
        ConsumerConfiguration.Builder customize(ConsumerConfiguration.Builder b);
    }

    private PullSubscribeOptions makePso(BuilderCustomizer c) {
        return c.customize(ConsumerConfiguration.builder().ackPolicy(AckPolicy.None)).buildPullSubscribeOptions();
    }

    static final int TYPE_ERROR = 1;
    static final int TYPE_WARNING = 2;
    static final int TYPE_NONE = 0;
    private void testConflictStatus(String statusText, int type, String targetVersion, ConflictSetup setup) throws Exception {
        TestHandler handler = new TestHandler();
        AtomicBoolean skip = new AtomicBoolean(false);
        runInJsServer(handler, nc -> {
            skip.set(versionIsBefore(nc, targetVersion));
            if (skip.get()) {
                return;
            }
            createDefaultTestStream(nc);
            JetStreamManagement jsm = nc.jetStreamManagement();
            JetStream js = nc.jetStream();
            prepHandlerWait(type, handler);
            JetStreamSubscription sub = setup.setup(nc, jsm, js, handler);
            if (sub.getDispatcher() == null) {
                if (type == TYPE_ERROR) {
                    assertThrows(JetStreamStatusException.class, () -> sub.nextMessage(5000));
                }
                else {
                    sub.nextMessage(5000);
                }
            }
            checkHandler(statusText, type, handler);
        });
    }

    private void prepHandlerWait(int type, TestHandler handler) {
        if (type == TYPE_ERROR) {
            handler.prepForPullStatusError();
        }
        else if (type == TYPE_WARNING) {
            handler.prepForPullStatusWarning();
        }
    }

    private void checkHandler(String statusText, int type, TestHandler handler) {
        if (type == TYPE_ERROR) {
            handler.pullStatusErrorOrWait(statusText, 2000);
        }
        else if (type == TYPE_WARNING) {
            handler.pullStatusWarningOrWait(statusText, 2000);
        }
    }

    @Test
    public void testExceedsMaxWaitingSyncSub() throws Exception {
        testConflictStatus(EXCEEDED_MAX_WAITING, TYPE_WARNING, "2.9.0", (nc, jsm, js, handler) -> {
            PullSubscribeOptions so = makePso(b -> b.maxPullWaiting(1));
            JetStreamSubscription sub = js.subscribe(SUBJECT, so);
            sub.pull(1);
            sub.pull(1);
            return sub;
        });
    }

    @Test
    public void testExceedsMaxWaitingAsyncSub() throws Exception {
        testConflictStatus(EXCEEDED_MAX_WAITING, TYPE_WARNING, "2.9.0", (nc, jsm, js, handler) -> {
            Dispatcher d = nc.createDispatcher();
            PullSubscribeOptions so = makePso(b -> b.maxPullWaiting(1));
            JetStreamSubscription sub = js.subscribe(SUBJECT, d, m -> {}, so);
            sub.pull(1);
            sub.pull(1);
            return sub;
        });
    }

    @Test
    public void testExceedsMaxRequestBatchSyncSub() throws Exception {
        testConflictStatus(EXCEEDED_MAX_REQUEST_BATCH, TYPE_WARNING, "2.9.0", (nc, jsm, js, handler) -> {
            PullSubscribeOptions so = makePso(b -> b.maxBatch(1));
            JetStreamSubscription sub = js.subscribe(SUBJECT, so);
            sub.pull(2);
            return sub;
        });
    }

    @Test
    public void testExceedsMaxRequestBatchAsyncSub() throws Exception {
        testConflictStatus(EXCEEDED_MAX_REQUEST_BATCH, TYPE_WARNING, "2.9.0", (nc, jsm, js, handler) -> {
            Dispatcher d = nc.createDispatcher();
            PullSubscribeOptions so = makePso(b -> b.maxBatch(1));
            JetStreamSubscription sub = js.subscribe(SUBJECT, d, m -> {}, so);
            sub.pull(2);
            return sub;
        });
    }

    @Test
    public void testMessageSizeExceedsMaxBytesSyncSub() throws Exception {
        testConflictStatus(MESSAGE_SIZE_EXCEEDS_MAX_BYTES, TYPE_NONE, "2.9.0", (nc, jsm, js, handler) -> {
            PullSubscribeOptions so = makePso(b -> b);
            js.publish(SUBJECT, new byte[1000]);
            JetStreamSubscription sub = js.subscribe(SUBJECT, so);
            sub.pull(PullRequestOptions.builder(1).maxBytes(100).build());
            return sub;
        });
    }

    @Test
    public void testMessageSizeExceedsMaxBytesAsyncSub() throws Exception {
        testConflictStatus(MESSAGE_SIZE_EXCEEDS_MAX_BYTES, TYPE_NONE, "2.9.0", (nc, jsm, js, handler) -> {
            Dispatcher d = nc.createDispatcher();
            PullSubscribeOptions so = makePso(b -> b);
            js.publish(SUBJECT, new byte[1000]);
            JetStreamSubscription sub = js.subscribe(SUBJECT, d, m -> {}, so);
            sub.pull(PullRequestOptions.builder(1).maxBytes(100).build());
            return sub;
        });
    }

    @Test
    public void testExceedsMaxRequestExpiresSyncSub() throws Exception {
        testConflictStatus(EXCEEDED_MAX_REQUEST_EXPIRES, TYPE_WARNING, "2.9.0", (nc, jsm, js, handler) -> {
            PullSubscribeOptions so = makePso(b -> b.maxExpires(1000));
            JetStreamSubscription sub = js.subscribe(SUBJECT, so);
            sub.pullExpiresIn(1, 2000);
            return sub;
        });
    }

    @Test
    public void testExceedsMaxRequestExpiresAsyncSub() throws Exception {
        testConflictStatus(EXCEEDED_MAX_REQUEST_EXPIRES, TYPE_WARNING, "2.9.0", (nc, jsm, js, handler) -> {
            Dispatcher d = nc.createDispatcher();
            PullSubscribeOptions so = makePso(b -> b.maxExpires(1000));
            JetStreamSubscription sub = js.subscribe(SUBJECT, d, m -> {}, so);
            sub.pullExpiresIn(1, 2000);
            return sub;
        });
    }

    @Test
    public void testConsumerIsPushBasedSyncSub() throws Exception {
        testConflictStatus(CONSUMER_IS_PUSH_BASED, TYPE_ERROR, "2.9.0", (nc, jsm, js, handler) -> {
            jsm.addOrUpdateConsumer(STREAM, builder().durable(durable(1)).ackPolicy(AckPolicy.None).build());
            PullSubscribeOptions so = PullSubscribeOptions.bind(STREAM, durable(1));
            JetStreamSubscription sub = js.subscribe(null, so);
            jsm.deleteConsumer(STREAM, durable(1));
            // consumer with same name but is push now
            jsm.addOrUpdateConsumer(STREAM, builder().durable(durable(1)).deliverSubject(deliver(1)).build());
            sub.pull(1);
            return sub;
        });
    }

    @Test
    public void testConsumerIsPushBasedAsyncSub() throws Exception {
        testConflictStatus(CONSUMER_IS_PUSH_BASED, TYPE_ERROR, "2.9.0", (nc, jsm, js, handler) -> {
            jsm.addOrUpdateConsumer(STREAM, builder().durable(durable(1)).ackPolicy(AckPolicy.None).build());
            Dispatcher d = nc.createDispatcher();
            PullSubscribeOptions so = PullSubscribeOptions.bind(STREAM, durable(1));
            JetStreamSubscription sub = js.subscribe(null, d, m -> {}, so);
            jsm.deleteConsumer(STREAM, durable(1));
            // consumer with same name but is push now
            jsm.addOrUpdateConsumer(STREAM, builder().durable(durable(1)).deliverSubject(deliver(1)).build());
            sub.pull(1);
            return sub;
        });
    }

    @Test
    public void testConsumerDeletedSyncSub() throws Exception {
        testConflictStatus(CONSUMER_DELETED, TYPE_ERROR, "2.9.6", (nc, jsm, js, handler) -> {
            jsm.addOrUpdateConsumer(STREAM, builder().durable(durable(1)).ackPolicy(AckPolicy.None).build());
            PullSubscribeOptions so = PullSubscribeOptions.bind(STREAM, durable(1));
            JetStreamSubscription sub = js.subscribe(null, so);
            sub.pullExpiresIn(1, 10000);
            jsm.deleteConsumer(STREAM, durable(1));
            return sub;
        });
    }

    @Test
    public void testConsumerDeletedAsyncSub() throws Exception {
        testConflictStatus(CONSUMER_DELETED, TYPE_ERROR, "2.9.6", (nc, jsm, js, handler) -> {
            jsm.addOrUpdateConsumer(STREAM, builder().durable(durable(1)).ackPolicy(AckPolicy.None).build());
            Dispatcher d = nc.createDispatcher();
            PullSubscribeOptions so = PullSubscribeOptions.bind(STREAM, durable(1));
            JetStreamSubscription sub = js.subscribe(null, d, m -> {}, so);
            sub.pullExpiresIn(1, 10000);
            jsm.deleteConsumer(STREAM, durable(1));
            return sub;
        });
    }

    @Test
    public void testBadRequestSyncSub() throws Exception {
        testConflictStatus(BAD_REQUEST, TYPE_ERROR, "2.9.0", (nc, jsm, js, handler) -> {
            PullSubscribeOptions so = makePso(b -> b);
            JetStreamSubscription sub = js.subscribe(SUBJECT, so);
            sub.pull(PullRequestOptions.builder(1).noWait().idleHeartbeat(1).build());
            return sub;
        });
    }

    @Test
    public void testBadRequestAsyncSub() throws Exception {
        testConflictStatus(BAD_REQUEST, TYPE_ERROR, "2.9.0", (nc, jsm, js, handler) -> {
            Dispatcher d = nc.createDispatcher();
            PullSubscribeOptions so = makePso(b -> b);
            JetStreamSubscription sub = js.subscribe(SUBJECT, d, m -> {}, so);
            sub.pull(PullRequestOptions.builder(1).noWait().idleHeartbeat(1).build());
            return sub;
        });
    }

    @Test
    public void testNotFoundSyncSub() throws Exception {
        testConflictStatus(NO_MESSAGES, TYPE_NONE, "2.9.0", (nc, jsm, js, handler) -> {
            PullSubscribeOptions so = makePso(b -> b);
            JetStreamSubscription sub = js.subscribe(SUBJECT, so);
            sub.pullNoWait(1);
            return sub;
        });
    }

    @Test
    public void testNotFoundAsyncSub() throws Exception {
        testConflictStatus(NO_MESSAGES, TYPE_NONE, "2.9.0", (nc, jsm, js, handler) -> {
            Dispatcher d = nc.createDispatcher();
            PullSubscribeOptions so = makePso(b -> b);
            JetStreamSubscription sub = js.subscribe(SUBJECT, d, m -> {}, so);
            sub.pullNoWait(1);
            return sub;
        });
    }

    @Test
    public void testExceedsMaxRequestBytes1stMessageSyncSub() throws Exception {
        testConflictStatus(EXCEEDED_MAX_REQUEST_MAX_BYTES, TYPE_WARNING, "2.9.0", (nc, jsm, js, handler) -> {
            PullSubscribeOptions so = makePso(b -> b.maxBytes(1));
            JetStreamSubscription sub = js.subscribe(SUBJECT, so);
            sub.pull(PullRequestOptions.builder(1).maxBytes(2).build());
            return sub;
        });
    }

    @Test
    public void testExceedsMaxRequestBytes1stMessageAsyncSub() throws Exception {
        testConflictStatus(EXCEEDED_MAX_REQUEST_MAX_BYTES, TYPE_WARNING, "2.9.0", (nc, jsm, js, handler) -> {
            Dispatcher d = nc.createDispatcher();
            PullSubscribeOptions so = makePso(b -> b.maxBytes(1));
            JetStreamSubscription sub = js.subscribe(SUBJECT, d, m -> {}, so);
            sub.pull(PullRequestOptions.builder(1).maxBytes(2).build());
            return sub;
        });
    }

    @Test
    public void testExceedsMaxRequestBytesNthMessageSyncSub() throws Exception {
        TestHandler handler = new TestHandler();
        AtomicBoolean skip = new AtomicBoolean(false);
        runInJsServer(handler, nc -> {
            skip.set(versionIsBefore(nc, "2.9.1"));
            if (skip.get()) {
                return;
            }
            createDefaultTestStream(nc);
            JetStreamManagement jsm = nc.jetStreamManagement();
            JetStream js = nc.jetStream();

            jsm.addOrUpdateConsumer(STREAM, builder().durable(durable(1)).ackPolicy(AckPolicy.None).build());
            PullSubscribeOptions so = PullSubscribeOptions.bind(STREAM, durable(1));
            JetStreamSubscription sub = js.subscribe(SUBJECT, so);

            // subject 7 + reply 52 + bytes 100 = 159
            // subject 7 + reply 52 + bytes 100 + headers 21 = 180
            js.publish(SUBJECT, new byte[100]);
            js.publish(SUBJECT, new Headers().add("foo", "bar"), new byte[100]);
            // 1000 - 159 - 180 = 661
            // subject 7 + reply 52 + bytes 610 = 669 > 661
            js.publish(SUBJECT, new byte[610]);

            sub.pull(PullRequestOptions.builder(10).maxBytes(1000).expiresIn(1000).build());
            assertNotNull(sub.nextMessage(500));
            assertNotNull(sub.nextMessage(500));
            assertNull(sub.nextMessage(500));
        });
    }

    @Test
    public void testExceedsMaxRequestBytesExactBytes() throws Exception {
        TestHandler handler = new TestHandler();
        AtomicBoolean skip = new AtomicBoolean(false);
        runInJsServer(handler, nc -> {
            skip.set(versionIsBefore(nc, "2.9.1"));
            if (skip.get()) {
                return;
            }
            createDefaultTestStream(nc);
            JetStreamManagement jsm = nc.jetStreamManagement();
            JetStream js = nc.jetStream();
            jsm.addOrUpdateConsumer(STREAM, builder().durable(durable(1)).ackPolicy(AckPolicy.None).build());
            PullSubscribeOptions so = PullSubscribeOptions.bind(STREAM, durable(1));
            JetStreamSubscription sub = js.subscribe(SUBJECT, so);

            // 159 + 180 + 661 = 1000
            // subject 7 + reply 52 + bytes 100 = 159
            // subject 7 + reply 52 + bytes 100 + headers 21 = 180
            // subject 7 + reply 52 + bytes 602 = 661
            js.publish(SUBJECT, new byte[100]);
            js.publish(SUBJECT, new Headers().add("foo", "bar"), new byte[100]);
            js.publish(SUBJECT, new byte[602]);

            sub.pull(PullRequestOptions.builder(10).maxBytes(1000).expiresIn(1000).build());
            assertNotNull(sub.nextMessage(500));
            assertNotNull(sub.nextMessage(500));
            assertNotNull(sub.nextMessage(500));
            assertNull(sub.nextMessage(500)); // there are no more messages
        });
    }
}
