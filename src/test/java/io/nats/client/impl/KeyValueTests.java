// Copyright 2021 The NATS Authors
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
import io.nats.client.api.*;
import io.nats.client.support.NatsKeyValueUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static io.nats.client.JetStreamOptions.DEFAULT_JS_OPTIONS;
import static io.nats.client.api.KeyValuePurgeOptions.DEFAULT_THRESHOLD_MILLIS;
import static io.nats.client.api.KeyValueWatchOption.*;
import static io.nats.client.support.NatsConstants.DOT;
import static org.junit.jupiter.api.Assertions.*;

public class KeyValueTests extends JetStreamTestBase {

    @Test
    public void testWorkflow() throws Exception {
        long now = ZonedDateTime.now().toEpochSecond();

        String byteKey = "byteKey";
        String stringKey = "stringKey";
        String longKey = "longKey";
        String notFoundKey = "notFound";
        String byteValue1 = "Byte Value 1";
        String byteValue2 = "Byte Value 2";
        String stringValue1 = "String Value 1";
        String stringValue2 = "String Value 2";

        runInJsServer(nc -> {
            // get the kv management context
            KeyValueManagement kvm = nc.keyValueManagement();
            nc.keyValueManagement(KeyValueOptions.builder(DEFAULT_JS_OPTIONS).build()); // coverage

            // create the bucket
            KeyValueConfiguration kvc = KeyValueConfiguration.builder()
                .name(BUCKET)
                .description(PLAIN)
                .maxHistoryPerKey(3)
                .storageType(StorageType.Memory)
                .build();

            KeyValueStatus status = kvm.create(kvc);
            assertInitialStatus(status);

            // get the kv context for the specific bucket
            KeyValue kv = nc.keyValue(BUCKET);
            assertEquals(BUCKET, kv.getBucketName());
            status = kv.getStatus();
            assertInitialStatus(status);

            KeyValue kv2 = nc.keyValue(BUCKET, KeyValueOptions.builder(DEFAULT_JS_OPTIONS).build()); // coverage
            assertEquals(BUCKET, kv2.getBucketName());
            assertInitialStatus(kv2.getStatus());

            // Put some keys. Each key is put in a subject in the bucket (stream)
            // The put returns the sequence number in the bucket (stream)
            assertEquals(1, kv.put(byteKey, byteValue1.getBytes()));
            assertEquals(2, kv.put(stringKey, stringValue1));
            assertEquals(3, kv.put(longKey, 1));

            // retrieve the values. all types are stored as bytes
            // so you can always get the bytes directly
            assertEquals(byteValue1, new String(kv.get(byteKey).getValue()));
            assertEquals(stringValue1, new String(kv.get(stringKey).getValue()));
            assertEquals("1", new String(kv.get(longKey).getValue()));

            // if you know the value is not binary and can safely be read
            // as a UTF-8 string, the getStringValue method is ok to use
            assertEquals(byteValue1, kv.get(byteKey).getValueAsString());
            assertEquals(stringValue1, kv.get(stringKey).getValueAsString());
            assertEquals("1", kv.get(longKey).getValueAsString());

            // if you know the value is a long, you can use
            // the getLongValue method
            // if it's not a number a NumberFormatException is thrown
            assertEquals(1, kv.get(longKey).getValueAsLong());
            assertThrows(NumberFormatException.class, () -> kv.get(stringKey).getValueAsLong());

            // going to manually track history for verification later
            List<Object> byteHistory = new ArrayList<>();
            List<Object> stringHistory = new ArrayList<>();
            List<Object> longHistory = new ArrayList<>();

            // entry gives detail about the latest entry of the key
            byteHistory.add(
                assertEntry(BUCKET, byteKey, KeyValueOperation.PUT, 1, byteValue1, now, kv.get(byteKey)));

            stringHistory.add(
                assertEntry(BUCKET, stringKey, KeyValueOperation.PUT, 2, stringValue1, now, kv.get(stringKey)));

            longHistory.add(
                assertEntry(BUCKET, longKey, KeyValueOperation.PUT, 3, "1", now, kv.get(longKey)));

            // history gives detail about the key
            assertHistory(byteHistory, kv.history(byteKey));
            assertHistory(stringHistory, kv.history(stringKey));
            assertHistory(longHistory, kv.history(longKey));

            // let's check the bucket info
            status = kvm.getStatus(BUCKET);
            assertState(status, 3, 3);
            status = kvm.getBucketInfo(BUCKET); // coverage for deprecated
            assertState(status, 3, 3);

            // delete a key. Its entry will still exist, but its value is null
            kv.delete(byteKey);
            assertNull(kv.get(byteKey));
            byteHistory.add(KeyValueOperation.DELETE);
            assertHistory(byteHistory, kv.history(byteKey));

            // hashCode coverage
            assertEquals(byteHistory.get(0).hashCode(), byteHistory.get(0).hashCode());
            assertNotEquals(byteHistory.get(0).hashCode(), byteHistory.get(1).hashCode());

            // let's check the bucket info
            status = kvm.getStatus(BUCKET);
            assertState(status, 4, 4);

            // if the key has been deleted no etnry is returned
            assertNull(kv.get(byteKey));

            // if the key does not exist (no history) there is no entry
            assertNull(kv.get(notFoundKey));

            // Update values. You can even update a deleted key
            assertEquals(5, kv.put(byteKey, byteValue2.getBytes()));
            assertEquals(6, kv.put(stringKey, stringValue2));
            assertEquals(7, kv.put(longKey, 2));

            // values after updates
            assertEquals(byteValue2, new String(kv.get(byteKey).getValue()));
            assertEquals(stringValue2, kv.get(stringKey).getValueAsString());
            assertEquals(2, kv.get(longKey).getValueAsLong());

            // entry and history after update
            byteHistory.add(
                assertEntry(BUCKET, byteKey, KeyValueOperation.PUT, 5, byteValue2, now, kv.get(byteKey)));
            assertHistory(byteHistory, kv.history(byteKey));

            stringHistory.add(
                assertEntry(BUCKET, stringKey, KeyValueOperation.PUT, 6, stringValue2, now, kv.get(stringKey)));
            assertHistory(stringHistory, kv.history(stringKey));

            longHistory.add(
                assertEntry(BUCKET, longKey, KeyValueOperation.PUT, 7, "2", now, kv.get(longKey)));
            assertHistory(longHistory, kv.history(longKey));

            // let's check the bucket info
            status = kvm.getStatus(BUCKET);
            assertState(status, 7, 7);

            // make sure it only keeps the correct amount of history
            assertEquals(8, kv.put(longKey, 3));
            assertEquals(3, kv.get(longKey).getValueAsLong());

            longHistory.add(
                assertEntry(BUCKET, longKey, KeyValueOperation.PUT, 8, "3", now, kv.get(longKey)));
            assertHistory(longHistory, kv.history(longKey));

            status = kvm.getStatus(BUCKET);
            assertState(status, 8, 8);

            // this would be the 4th entry for the longKey
            // sp the total records will stay the same
            assertEquals(9, kv.put(longKey, 4));
            assertEquals(4, kv.get(longKey).getValueAsLong());

            // history only retains 3 records
            longHistory.remove(0);
            longHistory.add(
                assertEntry(BUCKET, longKey, KeyValueOperation.PUT, 9, "4", now, kv.get(longKey)));
            assertHistory(longHistory, kv.history(longKey));

            // record count does not increase
            status = kvm.getStatus(BUCKET);
            assertState(status, 8, 9);

            // should have exactly these 3 keys
            assertKeys(kv.keys(), byteKey, stringKey, longKey);

            // purge
            kv.purge(longKey);
            longHistory.clear();
            assertNull(kv.get(longKey));
            longHistory.add(KeyValueOperation.PURGE);
            assertHistory(longHistory, kv.history(longKey));

            status = kvm.getStatus(BUCKET);
            assertState(status, 6, 10);

            // only 2 keys now
            assertKeys(kv.keys(), byteKey, stringKey);

            kv.purge(byteKey);
            byteHistory.clear();
            assertNull(kv.get(byteKey));
            byteHistory.add(KeyValueOperation.PURGE);
            assertHistory(byteHistory, kv.history(byteKey));

            status = kvm.getStatus(BUCKET);
            assertState(status, 4, 11);

            // only 1 key now
            assertKeys(kv.keys(), stringKey);

            kv.purge(stringKey);
            stringHistory.clear();
            assertNull(kv.get(stringKey));
            stringHistory.add(KeyValueOperation.PURGE);
            assertHistory(stringHistory, kv.history(stringKey));

            status = kvm.getStatus(BUCKET);
            assertState(status, 3, 12);

            // no more keys left
            assertKeys(kv.keys());

            // clear things
            KeyValuePurgeOptions kvpo = KeyValuePurgeOptions.builder().deleteMarkersNoThreshold().build();
            kv.purgeDeletes(kvpo);
            status = kvm.getStatus(BUCKET);
            assertState(status, 0, 12);

            longHistory.clear();
            assertHistory(longHistory, kv.history(longKey));

            stringHistory.clear();
            assertHistory(stringHistory, kv.history(stringKey));

            // put some more
            assertEquals(13, kv.put(longKey, 110));
            longHistory.add(
                assertEntry(BUCKET, longKey, KeyValueOperation.PUT, 13, "110", now, kv.get(longKey)));

            assertEquals(14, kv.put(longKey, 111));
            longHistory.add(
                assertEntry(BUCKET, longKey, KeyValueOperation.PUT, 14, "111", now, kv.get(longKey)));

            assertEquals(15, kv.put(longKey, 112));
            longHistory.add(
                assertEntry(BUCKET, longKey, KeyValueOperation.PUT, 15, "112", now, kv.get(longKey)));

            assertEquals(16, kv.put(stringKey, stringValue1));
            stringHistory.add(
                assertEntry(BUCKET, stringKey, KeyValueOperation.PUT, 16, stringValue1, now, kv.get(stringKey)));

            assertEquals(17, kv.put(stringKey, stringValue2));
            stringHistory.add(
                assertEntry(BUCKET, stringKey, KeyValueOperation.PUT, 17, stringValue2, now, kv.get(stringKey)));

            assertHistory(longHistory, kv.history(longKey));
            assertHistory(stringHistory, kv.history(stringKey));

            status = kvm.getStatus(BUCKET);
            assertState(status, 5, 17);

            // delete the bucket
            kvm.delete(BUCKET);
            assertThrows(JetStreamApiException.class, () -> kvm.delete(BUCKET));
            assertThrows(JetStreamApiException.class, () -> kvm.getStatus(BUCKET));

            assertEquals(0, kvm.getBucketNames().size());
        });
    }

    private static void assertState(KeyValueStatus status, int entryCount, int lastSeq) {
        assertEquals(entryCount, status.getEntryCount());
        assertEquals(lastSeq, status.getBackingStreamInfo().getStreamState().getLastSequence());
        assertEquals(status.getByteCount(), status.getBackingStreamInfo().getStreamState().getByteCount());
    }

    private void assertInitialStatus(KeyValueStatus status) {
        KeyValueConfiguration kvc;
        kvc = status.getConfiguration();
        assertEquals(BUCKET, status.getBucketName());
        assertEquals(BUCKET, kvc.getBucketName());
        assertEquals(PLAIN, status.getDescription());
        assertEquals(PLAIN, kvc.getDescription());
        assertEquals(NatsKeyValueUtil.toStreamName(BUCKET), kvc.getBackingConfig().getName());
        assertEquals(3, status.getMaxHistoryPerKey());
        assertEquals(3, kvc.getMaxHistoryPerKey());
        assertEquals(-1, status.getMaxBucketSize());
        assertEquals(-1, kvc.getMaxBucketSize());
        assertEquals(-1, status.getMaxValueSize());
        assertEquals(-1, kvc.getMaxValueSize());
        assertEquals(Duration.ZERO, status.getTtl());
        assertEquals(Duration.ZERO, kvc.getTtl());
        assertEquals(StorageType.Memory, status.getStorageType());
        assertEquals(StorageType.Memory, kvc.getStorageType());
        assertNull(status.getPlacement());
        assertNull(status.getRepublish());
        assertEquals(1, status.getReplicas());
        assertEquals(1, kvc.getReplicas());
        assertEquals(0, status.getEntryCount());
        assertEquals("JetStream", status.getBackingStore());

        assertTrue(status.toString().contains(BUCKET));
        assertTrue(status.toString().contains(PLAIN));
    }

    @Test
    public void testGetRevision() throws Exception {
        runInJsServer(nc -> {
            KeyValueManagement kvm = nc.keyValueManagement();

            kvm.create(KeyValueConfiguration.builder()
                .name(BUCKET)
                .storageType(StorageType.Memory)
                .maxHistoryPerKey(2)
                .build());

            KeyValue kv = nc.keyValue(BUCKET);
            long seq1 = kv.put(KEY, 1);
            long seq2 = kv.put(KEY, 2);
            long seq3 = kv.put(KEY, 3);

            KeyValueEntry kve = kv.get(KEY);
            assertNotNull(kve);
            assertEquals(3, kve.getValueAsLong());

            kve = kv.get(KEY, seq3);
            assertNotNull(kve);
            assertEquals(3, kve.getValueAsLong());

            kve = kv.get(KEY, seq2);
            assertNotNull(kve);
            assertEquals(2, kve.getValueAsLong());

            kve = kv.get(KEY, seq1);
            assertNull(kve);

            kve = kv.get("notkey", seq3);
            assertNull(kve);
        });
    }

    @Test
    public void testKeys() throws Exception {
        runInJsServer(nc -> {
            KeyValueManagement kvm = nc.keyValueManagement();

            // create bucket
            kvm.create(KeyValueConfiguration.builder()
                .name(BUCKET)
                .storageType(StorageType.Memory)
                .build());

            KeyValue kv = nc.keyValue(BUCKET);
            for (int x = 1; x <= 10; x++) {
                kv.put("k" + x, x);
            }

            List<String> keys = kv.keys();
            assertEquals(10, keys.size());

            kv.delete("k1");
            kv.delete("k3");
            kv.delete("k5");
            kv.purge("k7");
            kv.purge("k9");

            keys = kv.keys();
            assertEquals(5, keys.size());

            for (int x = 2; x <= 10; x += 2) {
                assertTrue(keys.contains("k" + x));
            }

            String keyWithDot = "part1.part2.part3";
            kv.put(keyWithDot, "key has dot");
            KeyValueEntry kve = kv.get(keyWithDot);
            assertEquals(keyWithDot, kve.getKey());
        });
    }

    @Test
    public void testMaxHistoryPerKey() throws Exception {
        runInJsServer(nc -> {
            KeyValueManagement kvm = nc.keyValueManagement();

            // default maxHistoryPerKey is 1
            kvm.create(KeyValueConfiguration.builder()
                .name(bucket(1))
                .storageType(StorageType.Memory)
                .build());

            KeyValue kv = nc.keyValue(bucket(1));
            kv.put(KEY, 1);
            kv.put(KEY, 2);

            List<KeyValueEntry> history = kv.history(KEY);
            assertEquals(1, history.size());
            assertEquals(2, history.get(0).getValueAsLong());

            kvm.create(KeyValueConfiguration.builder()
                .name(bucket(2))
                .maxHistoryPerKey(2)
                .storageType(StorageType.Memory)
                .build());

            kv = nc.keyValue(bucket(2));
            kv.put(KEY, 1);
            kv.put(KEY, 2);
            kv.put(KEY, 3);

            history = kv.history(KEY);
            assertEquals(2, history.size());
            assertEquals(2, history.get(0).getValueAsLong());
            assertEquals(3, history.get(1).getValueAsLong());
        });
    }

    @Test
    public void testCreateUpdate() throws Exception {
        runInJsServer(nc -> {
            KeyValueManagement kvm = nc.keyValueManagement();

            assertThrows(JetStreamApiException.class, () -> kvm.getStatus(BUCKET));

            KeyValueStatus kvs = kvm.create(KeyValueConfiguration.builder()
                .name(BUCKET)
                .storageType(StorageType.Memory)
                .build());

            assertEquals(BUCKET, kvs.getBucketName());
            assertNull(kvs.getDescription());
            assertEquals(1, kvs.getMaxHistoryPerKey());
            assertEquals(-1, kvs.getMaxBucketSize());
            assertEquals(-1, kvs.getMaxValueSize());
            assertEquals(Duration.ZERO, kvs.getTtl());
            assertEquals(StorageType.Memory, kvs.getStorageType());
            assertEquals(1, kvs.getReplicas());
            assertEquals(0, kvs.getEntryCount());
            assertEquals("JetStream", kvs.getBackingStore());

            KeyValue kv = nc.keyValue(BUCKET);
            kv.put(KEY, 1);
            kv.put(KEY, 2);

            List<KeyValueEntry> history = kv.history(KEY);
            assertEquals(1, history.size());
            assertEquals(2, history.get(0).getValueAsLong());

            KeyValueConfiguration kvc = KeyValueConfiguration.builder(kvs.getConfiguration())
                .description(PLAIN)
                .maxHistoryPerKey(3)
                .maxBucketSize(10_000)
                .maxValueSize(100)
                .ttl(Duration.ofHours(1))
                .build();

            kvs = kvm.update(kvc);

            assertEquals(BUCKET, kvs.getBucketName());
            assertEquals(PLAIN, kvs.getDescription());
            assertEquals(3, kvs.getMaxHistoryPerKey());
            assertEquals(10_000, kvs.getMaxBucketSize());
            assertEquals(100, kvs.getMaxValueSize());
            assertEquals(Duration.ofHours(1), kvs.getTtl());
            assertEquals(StorageType.Memory, kvs.getStorageType());
            assertEquals(1, kvs.getReplicas());
            assertEquals(1, kvs.getEntryCount());
            assertEquals("JetStream", kvs.getBackingStore());

            history = kv.history(KEY);
            assertEquals(1, history.size());
            assertEquals(2, history.get(0).getValueAsLong());

            KeyValueConfiguration kvcStor = KeyValueConfiguration.builder(kvs.getConfiguration())
                .storageType(StorageType.File)
                .build();
            assertThrows(JetStreamApiException.class, () -> kvm.update(kvcStor));
        });
    }

    @Test
    public void testHistoryDeletePurge() throws Exception {
        runInJsServer(nc -> {
            KeyValueManagement kvm = nc.keyValueManagement();

            // create bucket
            kvm.create(KeyValueConfiguration.builder()
                .name(BUCKET)
                .storageType(StorageType.Memory)
                .maxHistoryPerKey(64)
                .build());

            KeyValue kv = nc.keyValue(BUCKET);
            kv.put(KEY, "a");
            kv.put(KEY, "b");
            kv.put(KEY, "c");
            List<KeyValueEntry> list = kv.history(KEY);
            assertEquals(3, list.size());

            kv.delete(KEY);
            list = kv.history(KEY);
            assertEquals(4, list.size());

            kv.purge(KEY);
            list = kv.history(KEY);
            assertEquals(1, list.size());
        });
    }

    @Test
    public void testPurgeDeletes() throws Exception {
        runInJsServer(nc -> {
            KeyValueManagement kvm = nc.keyValueManagement();

            // create bucket
            kvm.create(KeyValueConfiguration.builder()
                .name(BUCKET)
                .storageType(StorageType.Memory)
                .maxHistoryPerKey(64)
                .build());

            KeyValue kv = nc.keyValue(BUCKET);
            kv.put(key(1), "a");
            kv.delete(key(1));
            kv.put(key(2), "b");
            kv.put(key(3), "c");
            kv.put(key(4), "d");
            kv.purge(key(4));

            JetStream js = nc.jetStream();
            assertPurgeDeleteEntries(js, new String[]{"a", null, "b", "c", null});

            // default purge deletes uses the default threshold
            // so no markers will be deleted
            kv.purgeDeletes();
            assertPurgeDeleteEntries(js, new String[]{null, "b", "c", null});

            // deleteMarkersThreshold of 0 the default threshold
            // so no markers will be deleted
            kv.purgeDeletes(KeyValuePurgeOptions.builder().deleteMarkersThreshold(0).build());
            assertPurgeDeleteEntries(js, new String[]{null, "b", "c", null});

            // no threshold causes all to be removed
            kv.purgeDeletes(KeyValuePurgeOptions.builder().deleteMarkersNoThreshold().build());
            assertPurgeDeleteEntries(js, new String[]{"b", "c"});
        });
    }

    private void assertPurgeDeleteEntries(JetStream js, String[] expected) throws IOException, JetStreamApiException, InterruptedException {
        JetStreamSubscription sub = js.subscribe(NatsKeyValueUtil.toStreamSubject(BUCKET));

        for (String s : expected) {
            Message m = sub.nextMessage(1000);
            KeyValueEntry kve = new KeyValueEntry(m);
            if (s == null) {
                assertNotEquals(KeyValueOperation.PUT, kve.getOperation());
                assertEquals(0, kve.getDataLen());
            }
            else {
                assertEquals(KeyValueOperation.PUT, kve.getOperation());
                assertEquals(s, kve.getValueAsString());
            }
        }

        sub.unsubscribe();
    }

    @Test
    public void testCreateAndUpdate() throws Exception {
        runInJsServer(nc -> {
            KeyValueManagement kvm = nc.keyValueManagement();

            // create bucket
            kvm.create(KeyValueConfiguration.builder()
                .name(BUCKET)
                .storageType(StorageType.Memory)
                .maxHistoryPerKey(64)
                .build());

            KeyValue kv = nc.keyValue(BUCKET);

            // 1. allowed to create something that does not exist
            long rev1 = kv.create(KEY, "a".getBytes());

            // 2. allowed to update with proper revision
            kv.update(KEY, "ab".getBytes(), rev1);

            // 3. not allowed to update with wrong revision
            assertThrows(JetStreamApiException.class, () -> kv.update(KEY, "zzz".getBytes(), rev1));

            // 4. not allowed to create a key that exists
            assertThrows(JetStreamApiException.class, () -> kv.create(KEY, "zzz".getBytes()));

            // 5. not allowed to update a key that does not exist
            assertThrows(JetStreamApiException.class, () -> kv.update(KEY, "zzz".getBytes(), 1));

            // 6. allowed to create a key that is deleted
            kv.delete(KEY);
            kv.create(KEY, "abc".getBytes());

            // 7. allowed to update a key that is deleted, as long as you have it's revision
            kv.delete(KEY);
            nc.flush(Duration.ofSeconds(1));

            sleep(200); // a little pause to make sure things get flushed
            List<KeyValueEntry> hist = kv.history(KEY);
            kv.update(KEY, "abcd".getBytes(), hist.get(hist.size() - 1).getRevision());

            // 8. allowed to create a key that is purged
            kv.purge(KEY);
            kv.create(KEY, "abcde".getBytes());

            // 9. allowed to update a key that is deleted, as long as you have its revision
            kv.purge(KEY);

            sleep(200); // a little pause to make sure things get flushed
            hist = kv.history(KEY);
            kv.update(KEY, "abcdef".getBytes(), hist.get(hist.size() - 1).getRevision());
        });
    }

    private void assertKeys(List<String> apiKeys, String... manualKeys) {
        assertEquals(manualKeys.length, apiKeys.size());
        for (String k : manualKeys) {
            assertTrue(apiKeys.contains(k));
        }
    }

    private void assertHistory(List<Object> manualHistory, List<KeyValueEntry> apiHistory) {
        assertEquals(apiHistory.size(), manualHistory.size());
        for (int x = 0; x < apiHistory.size(); x++) {
            Object o = manualHistory.get(x);
            if (o instanceof KeyValueOperation) {
                assertEquals((KeyValueOperation)o, apiHistory.get(x).getOperation());
            }
            else {
                assertKvEquals((KeyValueEntry)o, apiHistory.get(x));
            }
        }
    }

    @SuppressWarnings("SameParameterValue")
    private KeyValueEntry assertEntry(String bucket, String key, KeyValueOperation op, long seq, String value, long now, KeyValueEntry entry) {
        assertEquals(bucket, entry.getBucket());
        assertEquals(key, entry.getKey());
        assertEquals(op, entry.getOperation());
        assertEquals(seq, entry.getRevision());
        assertEquals(0, entry.getDelta());
        if (op == KeyValueOperation.PUT) {
            assertEquals(value, new String(entry.getValue()));
        }
        else {
            assertNull(entry.getValue());
        }
        assertTrue(now <= entry.getCreated().toEpochSecond());

        // coverage
        assertNotNull(entry.toString());
        return entry;
    }

    private void assertKvEquals(KeyValueEntry kv1, KeyValueEntry kv2) {
        assertEquals(kv1.getOperation(), kv2.getOperation());
        assertEquals(kv1.getRevision(), kv2.getRevision());
        assertEquals(kv1.getBucket(), kv2.getBucket());
        assertEquals(kv1.getKey(), kv2.getKey());
        assertTrue(Arrays.equals(kv1.getValue(), kv2.getValue()));
        long es1 = kv1.getCreated().toEpochSecond();
        long es2 = kv2.getCreated().toEpochSecond();
        assertEquals(es1, es2);
    }

    @Test
    public void testManageGetBucketNamesStatuses() throws Exception {
        runInJsServer(nc -> {
            KeyValueManagement kvm = nc.keyValueManagement();

            // create bucket 1
            kvm.create(KeyValueConfiguration.builder()
                .name(bucket(1))
                .storageType(StorageType.Memory)
                .build());

            // create bucket 2
            kvm.create(KeyValueConfiguration.builder()
                .name(bucket(2))
                .storageType(StorageType.Memory)
                .build());

            createMemoryStream(nc, stream(1));
            createMemoryStream(nc, stream(2));

            List<KeyValueStatus> infos = kvm.getStatuses();
            assertEquals(2, infos.size());
            List<String> buckets = new ArrayList<>();
            for (KeyValueStatus status : infos) {
                buckets.add(status.getBucketName());
            }
            assertEquals(2, buckets.size());
            assertTrue(buckets.contains(bucket(1)));
            assertTrue(buckets.contains(bucket(2)));

            buckets = kvm.getBucketNames();
            assertTrue(buckets.contains(bucket(1)));
            assertTrue(buckets.contains(bucket(2)));
        });
    }

    static class TestKeyValueWatcher implements KeyValueWatcher {
        public String name;
        public List<KeyValueEntry> entries = new ArrayList<>();
        public KeyValueWatchOption[] watchOptions;
        public boolean beforeWatcher;
        public boolean metaOnly;
        public int endOfDataReceived;
        public boolean endBeforeEntries;

        public TestKeyValueWatcher(String name, boolean beforeWatcher, KeyValueWatchOption... watchOptions) {
            this.name = name;
            this.beforeWatcher = beforeWatcher;
            this.watchOptions = watchOptions;
            for (KeyValueWatchOption wo : watchOptions) {
                if (wo == META_ONLY) {
                    metaOnly = true;
                    break;
                }
            }
        }

        @Override
        public String toString() {
            return "TestKeyValueWatcher{" +
                "name='" + name + '\'' +
                ", beforeWatcher=" + beforeWatcher +
                ", metaOnly=" + metaOnly +
                ", watchOptions=" + Arrays.toString(watchOptions) +
                '}';
        }

        @Override
        public void watch(KeyValueEntry kve) {
            entries.add(kve);
        }

        @Override
        public void endOfData() {
            if (++endOfDataReceived == 1 && entries.size() == 0) {
                endBeforeEntries = true;
            }
        }
    }

    static String TEST_WATCH_KEY_NULL = "key.nl";
    static String TEST_WATCH_KEY_1 = "key.1";
    static String TEST_WATCH_KEY_2 = "key.2";

    interface TestWatchSubSupplier {
        NatsKeyValueWatchSubscription get(KeyValue kv) throws Exception;
    }

    @Test
    public void testWatch() throws Exception {
        Object[] key1AllExpecteds = new Object[]{
            "a", "aa", KeyValueOperation.DELETE, "aaa", KeyValueOperation.DELETE, KeyValueOperation.PURGE
        };

        Object[] noExpecteds = new Object[0];
        Object[] purgeOnlyExpecteds = new Object[]{KeyValueOperation.PURGE};

        Object[] key2AllExpecteds = new Object[]{
            "z", "zz", KeyValueOperation.DELETE, "zzz"
        };

        Object[] key2AfterExpecteds = new Object[]{"zzz"};

        Object[] allExpecteds = new Object[]{
            "a", "aa", "z", "zz",
            KeyValueOperation.DELETE, KeyValueOperation.DELETE,
            "aaa", "zzz",
            KeyValueOperation.DELETE, KeyValueOperation.PURGE,
            null
        };

        Object[] allPutsExpecteds = new Object[]{
            "a", "aa", "z", "zz", "aaa", "zzz", null
        };

        TestKeyValueWatcher key1FullWatcher = new TestKeyValueWatcher("key1FullWatcher", true);
        TestKeyValueWatcher key1MetaWatcher = new TestKeyValueWatcher("key1MetaWatcher", true, META_ONLY);
        TestKeyValueWatcher key1StartNewWatcher = new TestKeyValueWatcher("key1StartNewWatcher", true, META_ONLY, UPDATES_ONLY);
        TestKeyValueWatcher key1StartAllWatcher = new TestKeyValueWatcher("key1StartAllWatcher", true, META_ONLY);
        TestKeyValueWatcher key2FullWatcher = new TestKeyValueWatcher("key2FullWatcher", true);
        TestKeyValueWatcher key2MetaWatcher = new TestKeyValueWatcher("key2MetaWatcher", true, META_ONLY);
        TestKeyValueWatcher allAllFullWatcher = new TestKeyValueWatcher("allAllFullWatcher", true);
        TestKeyValueWatcher allAllMetaWatcher = new TestKeyValueWatcher("allAllMetaWatcher", true, META_ONLY);
        TestKeyValueWatcher allIgDelFullWatcher = new TestKeyValueWatcher("allIgDelFullWatcher", true, IGNORE_DELETE);
        TestKeyValueWatcher allIgDelMetaWatcher = new TestKeyValueWatcher("allIgDelMetaWatcher", true, META_ONLY, IGNORE_DELETE);
        TestKeyValueWatcher starFullWatcher = new TestKeyValueWatcher("starFullWatcher", true);
        TestKeyValueWatcher starMetaWatcher = new TestKeyValueWatcher("starMetaWatcher", true, META_ONLY);
        TestKeyValueWatcher gtFullWatcher = new TestKeyValueWatcher("gtFullWatcher", true);
        TestKeyValueWatcher gtMetaWatcher = new TestKeyValueWatcher("gtMetaWatcher", true, META_ONLY);
        TestKeyValueWatcher key1AfterWatcher = new TestKeyValueWatcher("key1AfterWatcher", false, META_ONLY);
        TestKeyValueWatcher key1AfterIgDelWatcher = new TestKeyValueWatcher("key1AfterIgDelWatcher", false, META_ONLY, IGNORE_DELETE);
        TestKeyValueWatcher key1AfterStartNewWatcher = new TestKeyValueWatcher("key1AfterStartNewWatcher", false, META_ONLY, UPDATES_ONLY);
        TestKeyValueWatcher key1AfterStartFirstWatcher = new TestKeyValueWatcher("key1AfterStartFirstWatcher", false, META_ONLY, INCLUDE_HISTORY);
        TestKeyValueWatcher key2AfterWatcher = new TestKeyValueWatcher("key2AfterWatcher", false, META_ONLY);
        TestKeyValueWatcher key2AfterStartNewWatcher = new TestKeyValueWatcher("key2AfterStartNewWatcher", false, META_ONLY, UPDATES_ONLY);
        TestKeyValueWatcher key2AfterStartFirstWatcher = new TestKeyValueWatcher("key2AfterStartFirstWatcher", false, META_ONLY, INCLUDE_HISTORY);

        runInJsServer(nc -> {
            _testWatch(nc, key1FullWatcher, key1AllExpecteds, kv -> kv.watch(TEST_WATCH_KEY_1, key1FullWatcher, key1FullWatcher.watchOptions));
            _testWatch(nc, key1MetaWatcher, key1AllExpecteds, kv -> kv.watch(TEST_WATCH_KEY_1, key1MetaWatcher, key1MetaWatcher.watchOptions));
            _testWatch(nc, key1StartNewWatcher, key1AllExpecteds, kv -> kv.watch(TEST_WATCH_KEY_1, key1StartNewWatcher, key1StartNewWatcher.watchOptions));
            _testWatch(nc, key1StartAllWatcher, key1AllExpecteds, kv -> kv.watch(TEST_WATCH_KEY_1, key1StartAllWatcher, key1StartAllWatcher.watchOptions));
            _testWatch(nc, key2FullWatcher, key2AllExpecteds, kv -> kv.watch(TEST_WATCH_KEY_2, key2FullWatcher, key2FullWatcher.watchOptions));
            _testWatch(nc, key2MetaWatcher, key2AllExpecteds, kv -> kv.watch(TEST_WATCH_KEY_2, key2MetaWatcher, key2MetaWatcher.watchOptions));
            _testWatch(nc, allAllFullWatcher, allExpecteds, kv -> kv.watchAll(allAllFullWatcher, allAllFullWatcher.watchOptions));
            _testWatch(nc, allAllMetaWatcher, allExpecteds, kv -> kv.watchAll(allAllMetaWatcher, allAllMetaWatcher.watchOptions));
            _testWatch(nc, allIgDelFullWatcher, allPutsExpecteds, kv -> kv.watchAll(allIgDelFullWatcher, allIgDelFullWatcher.watchOptions));
            _testWatch(nc, allIgDelMetaWatcher, allPutsExpecteds, kv -> kv.watchAll(allIgDelMetaWatcher, allIgDelMetaWatcher.watchOptions));
            _testWatch(nc, starFullWatcher, allExpecteds, kv -> kv.watch("key.*", starFullWatcher, starFullWatcher.watchOptions));
            _testWatch(nc, starMetaWatcher, allExpecteds, kv -> kv.watch("key.*", starMetaWatcher, starMetaWatcher.watchOptions));
            _testWatch(nc, gtFullWatcher, allExpecteds, kv -> kv.watch("key.>", gtFullWatcher, gtFullWatcher.watchOptions));
            _testWatch(nc, gtMetaWatcher, allExpecteds, kv -> kv.watch("key.>", gtMetaWatcher, gtMetaWatcher.watchOptions));
            _testWatch(nc, key1AfterWatcher, purgeOnlyExpecteds, kv -> kv.watch(TEST_WATCH_KEY_1, key1AfterWatcher, key1AfterWatcher.watchOptions));
            _testWatch(nc, key1AfterIgDelWatcher, noExpecteds, kv -> kv.watch(TEST_WATCH_KEY_1, key1AfterIgDelWatcher, key1AfterIgDelWatcher.watchOptions));
            _testWatch(nc, key1AfterStartNewWatcher, noExpecteds, kv -> kv.watch(TEST_WATCH_KEY_1, key1AfterStartNewWatcher, key1AfterStartNewWatcher.watchOptions));
            _testWatch(nc, key1AfterStartFirstWatcher, purgeOnlyExpecteds, kv -> kv.watch(TEST_WATCH_KEY_1, key1AfterStartFirstWatcher, key1AfterStartFirstWatcher.watchOptions));
            _testWatch(nc, key2AfterWatcher, key2AfterExpecteds, kv -> kv.watch(TEST_WATCH_KEY_2, key2AfterWatcher, key2AfterWatcher.watchOptions));
            _testWatch(nc, key2AfterStartNewWatcher, noExpecteds, kv -> kv.watch(TEST_WATCH_KEY_2, key2AfterStartNewWatcher, key2AfterStartNewWatcher.watchOptions));
            _testWatch(nc, key2AfterStartFirstWatcher, key2AllExpecteds, kv -> kv.watch(TEST_WATCH_KEY_2, key2AfterStartFirstWatcher, key2AfterStartFirstWatcher.watchOptions));
        });
    }

    private void _testWatch(Connection nc, TestKeyValueWatcher watcher, Object[] expectedKves, TestWatchSubSupplier supplier) throws Exception {
        KeyValueManagement kvm = nc.keyValueManagement();

        String bucket = watcher.name + "Bucket";
        kvm.create(KeyValueConfiguration.builder()
            .name(bucket)
            .maxHistoryPerKey(10)
            .storageType(StorageType.Memory)
            .build());

        KeyValue kv = nc.keyValue(bucket);

        NatsKeyValueWatchSubscription sub = null;

        if (watcher.beforeWatcher) {
            sub = supplier.get(kv);
        }

        kv.put(TEST_WATCH_KEY_1, "a");
        kv.put(TEST_WATCH_KEY_1, "aa");
        kv.put(TEST_WATCH_KEY_2, "z");
        kv.put(TEST_WATCH_KEY_2, "zz");
        kv.delete(TEST_WATCH_KEY_1);
        kv.delete(TEST_WATCH_KEY_2);
        kv.put(TEST_WATCH_KEY_1, "aaa");
        kv.put(TEST_WATCH_KEY_2, "zzz");
        kv.delete(TEST_WATCH_KEY_1);
        kv.purge(TEST_WATCH_KEY_1);
        kv.put(TEST_WATCH_KEY_NULL, (byte[]) null);

        if (!watcher.beforeWatcher) {
            sub = supplier.get(kv);
        }

        sleep(1500); // give time for the watches to get messages

        validateWatcher(expectedKves, watcher);
        //noinspection ConstantConditions
        sub.unsubscribe();
        kvm.delete(bucket);
    }

    private void validateWatcher(Object[] expectedKves, TestKeyValueWatcher watcher) {
        assertEquals(expectedKves.length, watcher.entries.size());
        assertEquals(1, watcher.endOfDataReceived);

        if (expectedKves.length > 0) {
            assertEquals(watcher.beforeWatcher, watcher.endBeforeEntries);
        }

        int aix = 0;
        ZonedDateTime lastCreated = ZonedDateTime.of(2000, 4, 1, 0, 0, 0, 0, ZoneId.systemDefault());
        long lastRevision = -1;

        for (KeyValueEntry kve : watcher.entries) {

            assertTrue(kve.getCreated().isAfter(lastCreated) || kve.getCreated().isEqual(lastCreated));
            lastCreated = kve.getCreated();

            assertTrue(lastRevision < kve.getRevision());
            lastRevision = kve.getRevision();

            Object expected = expectedKves[aix++];
            if (expected == null) {
                assertSame(KeyValueOperation.PUT, kve.getOperation());
                assertTrue(kve.getValue() == null || kve.getValue().length == 0);
                assertEquals(0, kve.getDataLen());
            }
            else if (expected instanceof String) {
                assertSame(KeyValueOperation.PUT, kve.getOperation());
                String s = (String) expected;
                if (watcher.metaOnly) {
                    assertTrue(kve.getValue() == null || kve.getValue().length == 0);
                    assertEquals(s.length(), kve.getDataLen());
                }
                else {
                    assertNotNull(kve.getValue());
                    assertEquals(s.length(), kve.getDataLen());
                    assertEquals(s, kve.getValueAsString());
                }
            }
            else {
                assertTrue(kve.getValue() == null || kve.getValue().length == 0);
                assertEquals(0, kve.getDataLen());
                assertSame(expected, kve.getOperation());
            }
        }
    }

    static final String BUCKET_CREATED_BY_USER_A = "bucketA";
    static final String BUCKET_CREATED_BY_USER_I = "bucketI";

    @Test
    public void testWithAccount() throws Exception {

        try (NatsTestServer ts = new NatsTestServer("src/test/resources/kv_account.conf", false)) {
            Options acctA = new Options.Builder().server(ts.getURI()).userInfo("a", "a").build();
            Options acctI = new Options.Builder().server(ts.getURI()).userInfo("i", "i").inboxPrefix("ForI").build();

            try (Connection connUserA = Nats.connect(acctA); Connection connUserI = Nats.connect(acctI)) {

                // some prep
                KeyValueOptions jsOpt_UserI_BucketA_WithPrefix =
                    KeyValueOptions.builder().jsPrefix("FromA").build();

                assertNotNull(jsOpt_UserI_BucketA_WithPrefix.getJetStreamOptions());

                KeyValueOptions jsOpt_UserI_BucketI_WithPrefix =
                    KeyValueOptions.builder().jsPrefix("FromA").build();

                assertNotNull(jsOpt_UserI_BucketI_WithPrefix.getJetStreamOptions());

                KeyValueManagement kvmUserA = connUserA.keyValueManagement();
                KeyValueManagement kvmUserIBcktA = connUserI.keyValueManagement(jsOpt_UserI_BucketA_WithPrefix);
                KeyValueManagement kvmUserIBcktI = connUserI.keyValueManagement(jsOpt_UserI_BucketI_WithPrefix);

                KeyValueConfiguration kvcA = KeyValueConfiguration.builder()
                    .name(BUCKET_CREATED_BY_USER_A).storageType(StorageType.Memory).maxHistoryPerKey(64).build();

                KeyValueConfiguration kvcI = KeyValueConfiguration.builder()
                    .name(BUCKET_CREATED_BY_USER_I).storageType(StorageType.Memory).maxHistoryPerKey(64).build();

                // testing KVM API
                assertEquals(BUCKET_CREATED_BY_USER_A, kvmUserA.create(kvcA).getBucketName());
                assertEquals(BUCKET_CREATED_BY_USER_I, kvmUserIBcktI.create(kvcI).getBucketName());

                assertKvAccountBucketNames(kvmUserA.getBucketNames());
                assertKvAccountBucketNames(kvmUserIBcktI.getBucketNames());

                assertEquals(BUCKET_CREATED_BY_USER_A, kvmUserA.getStatus(BUCKET_CREATED_BY_USER_A).getBucketName());
                assertEquals(BUCKET_CREATED_BY_USER_A, kvmUserIBcktA.getStatus(BUCKET_CREATED_BY_USER_A).getBucketName());
                assertEquals(BUCKET_CREATED_BY_USER_I, kvmUserA.getStatus(BUCKET_CREATED_BY_USER_I).getBucketName());
                assertEquals(BUCKET_CREATED_BY_USER_I, kvmUserIBcktI.getStatus(BUCKET_CREATED_BY_USER_I).getBucketName());

                // some more prep
                KeyValue kv_connA_bucketA = connUserA.keyValue(BUCKET_CREATED_BY_USER_A);
                KeyValue kv_connA_bucketI = connUserA.keyValue(BUCKET_CREATED_BY_USER_I);
                KeyValue kv_connI_bucketA = connUserI.keyValue(BUCKET_CREATED_BY_USER_A, jsOpt_UserI_BucketA_WithPrefix);
                KeyValue kv_connI_bucketI = connUserI.keyValue(BUCKET_CREATED_BY_USER_I, jsOpt_UserI_BucketI_WithPrefix);

                // check the names
                assertEquals(BUCKET_CREATED_BY_USER_A, kv_connA_bucketA.getBucketName());
                assertEquals(BUCKET_CREATED_BY_USER_A, kv_connI_bucketA.getBucketName());
                assertEquals(BUCKET_CREATED_BY_USER_I, kv_connA_bucketI.getBucketName());
                assertEquals(BUCKET_CREATED_BY_USER_I, kv_connI_bucketI.getBucketName());

                TestKeyValueWatcher watcher_connA_BucketA = new TestKeyValueWatcher("watcher_connA_BucketA", true);
                TestKeyValueWatcher watcher_connA_BucketI = new TestKeyValueWatcher("watcher_connA_BucketI", true);
                TestKeyValueWatcher watcher_connI_BucketA = new TestKeyValueWatcher("watcher_connI_BucketA", true);
                TestKeyValueWatcher watcher_connI_BucketI = new TestKeyValueWatcher("watcher_connI_BucketI", true);

                kv_connA_bucketA.watchAll(watcher_connA_BucketA);
                kv_connA_bucketI.watchAll(watcher_connA_BucketI);
                kv_connI_bucketA.watchAll(watcher_connI_BucketA);
                kv_connI_bucketI.watchAll(watcher_connI_BucketI);

                // bucket a from user a: AA, check AA, IA
                assertKveAccount(kv_connA_bucketA, key(11), kv_connA_bucketA, kv_connI_bucketA);

                // bucket a from user i: IA, check AA, IA
                assertKveAccount(kv_connI_bucketA, key(12), kv_connA_bucketA, kv_connI_bucketA);

                // bucket i from user a: AI, check AI, II
                assertKveAccount(kv_connA_bucketI, key(21), kv_connA_bucketI, kv_connI_bucketI);

                // bucket i from user i: II, check AI, II
                assertKveAccount(kv_connI_bucketI, key(22), kv_connA_bucketI, kv_connI_bucketI);

                // check keys from each kv
                assertKvAccountKeys(kv_connA_bucketA.keys(), key(11), key(12));
                assertKvAccountKeys(kv_connI_bucketA.keys(), key(11), key(12));
                assertKvAccountKeys(kv_connA_bucketI.keys(), key(21), key(22));
                assertKvAccountKeys(kv_connI_bucketI.keys(), key(21), key(22));

                Object[] expecteds = new Object[]{
                    data(0), data(1), KeyValueOperation.DELETE, KeyValueOperation.PURGE, data(2),
                    data(0), data(1), KeyValueOperation.DELETE, KeyValueOperation.PURGE, data(2)
                };

                validateWatcher(expecteds, watcher_connA_BucketA);
                validateWatcher(expecteds, watcher_connA_BucketI);
                validateWatcher(expecteds, watcher_connI_BucketA);
                validateWatcher(expecteds, watcher_connI_BucketI);
            }
        }
    }

    private void assertKvAccountBucketNames(List<String> bnames) {
        assertEquals(2, bnames.size());
        assertTrue(bnames.contains(BUCKET_CREATED_BY_USER_A));
        assertTrue(bnames.contains(BUCKET_CREATED_BY_USER_I));
    }

    private void assertKvAccountKeys(List<String> keys, String key1, String key2) {
        assertEquals(2, keys.size());
        assertTrue(keys.contains(key1));
        assertTrue(keys.contains(key2));
    }

    private void assertKveAccount(KeyValue kvWorker, String key, KeyValue kvUserA, KeyValue kvUserI) throws IOException, JetStreamApiException, InterruptedException {
        kvWorker.create(key, dataBytes(0));
        assertKveAccountGet(kvUserA, kvUserI, key, data(0));

        kvWorker.put(key, dataBytes(1));
        assertKveAccountGet(kvUserA, kvUserI, key, data(1));

        kvWorker.delete(key);
        KeyValueEntry kveUserA = kvUserA.get(key);
        KeyValueEntry kveUserI = kvUserI.get(key);
        assertNull(kveUserA);
        assertNull(kveUserI);

        assertKveAccountHistory(kvUserA.history(key), data(0), data(1), KeyValueOperation.DELETE);
        assertKveAccountHistory(kvUserI.history(key), data(0), data(1), KeyValueOperation.DELETE);

        kvWorker.purge(key);
        assertKveAccountHistory(kvUserA.history(key), KeyValueOperation.PURGE);
        assertKveAccountHistory(kvUserI.history(key), KeyValueOperation.PURGE);

        // leave data for keys checking
        kvWorker.put(key, dataBytes(2));
        assertKveAccountGet(kvUserA, kvUserI, key, data(2));
    }

    private void assertKveAccountHistory(List<KeyValueEntry> history, Object... expecteds) {
        assertEquals(expecteds.length, history.size());
        for (int x = 0; x < expecteds.length; x++) {
            if (expecteds[x] instanceof String) {
                assertEquals(expecteds[x], history.get(x).getValueAsString());
            }
            else {
                assertEquals(expecteds[x], history.get(x).getOperation());
            }
        }
    }

    private void assertKveAccountGet(KeyValue kvUserA, KeyValue kvUserI, String key, String data) throws IOException, JetStreamApiException {
        KeyValueEntry kveUserA = kvUserA.get(key);
        KeyValueEntry kveUserI = kvUserI.get(key);
        assertNotNull(kveUserA);
        assertNotNull(kveUserI);
        assertEquals(kveUserA, kveUserI);
        assertEquals(data, kveUserA.getValueAsString());
        assertEquals(KeyValueOperation.PUT, kveUserA.getOperation());
    }

    @SuppressWarnings({"SimplifiableAssertion", "ConstantConditions"})
    @Test
    public void testCoverBucketAndKey() {
        NatsKeyValueUtil.BucketAndKey bak1 = new NatsKeyValueUtil.BucketAndKey(DOT + BUCKET + DOT + KEY);
        NatsKeyValueUtil.BucketAndKey bak2 = new NatsKeyValueUtil.BucketAndKey(DOT + BUCKET + DOT + KEY);
        NatsKeyValueUtil.BucketAndKey bak3 = new NatsKeyValueUtil.BucketAndKey(DOT + bucket(1) + DOT + KEY);
        NatsKeyValueUtil.BucketAndKey bak4 = new NatsKeyValueUtil.BucketAndKey(DOT + BUCKET + DOT + key(1));

        assertEquals(BUCKET, bak1.bucket);
        assertEquals(KEY, bak1.key);
        assertEquals(bak1, bak1);
        assertEquals(bak1, bak2);
        assertEquals(bak2, bak1);
        assertNotEquals(bak1, bak3);
        assertNotEquals(bak1, bak4);
        assertNotEquals(bak3, bak1);
        assertNotEquals(bak4, bak1);

        assertFalse(bak4.equals(null));
        assertFalse(bak4.equals(new Object()));
    }

    @Test
    public void testCoverPrefix() {
        assertTrue(NatsKeyValueUtil.hasPrefix("KV_has"));
        assertFalse(NatsKeyValueUtil.hasPrefix("doesn't"));
        assertEquals("has", NatsKeyValueUtil.trimPrefix("KV_has"));
        assertEquals("doesn't", NatsKeyValueUtil.trimPrefix("doesn't"));

    }

    @Test
    public void testKeyValueEntryEqualsImpl() throws Exception {
        runInJsServer(nc -> {
            KeyValueManagement kvm = nc.keyValueManagement();

            // create bucket 1
            kvm.create(KeyValueConfiguration.builder()
                .name(bucket(1))
                .storageType(StorageType.Memory)
                .build());

            // create bucket 2
            kvm.create(KeyValueConfiguration.builder()
                .name(bucket(2))
                .storageType(StorageType.Memory)
                .build());

            KeyValue kv1 = nc.keyValue(bucket(1));
            KeyValue kv2 = nc.keyValue(bucket(2));
            kv1.put(key(1), "ONE");
            kv1.put(key(2), "TWO");
            kv2.put(key(1), "ONE");

            KeyValueEntry kve1_1 = kv1.get(key(1));
            KeyValueEntry kve1_2 = kv1.get(key(2));
            KeyValueEntry kve2_1 = kv2.get(key(1));

            assertEquals(kve1_1, kve1_1);
            assertEquals(kve1_1, kv1.get(key(1)));
            assertNotEquals(kve1_1, kve1_2);
            assertNotEquals(kve1_1, kve2_1);

            kv1.put(key(1), "ONE-PRIME");
            assertNotEquals(kve1_1, kv1.get(key(1)));

            // coverage
            assertNotEquals(kve1_1, null);
            assertNotEquals(new Object(), kve1_1);
        });
    }

    @Test
    public void testKeyValueOptionsBuilderCoverage() {
        assertKvo(DEFAULT_JS_OPTIONS, KeyValueOptions.builder().build());
        assertKvo(DEFAULT_JS_OPTIONS, KeyValueOptions.builder().jetStreamOptions(DEFAULT_JS_OPTIONS).build());
        assertKvo(DEFAULT_JS_OPTIONS, KeyValueOptions.builder((KeyValueOptions) null).build());
        assertKvo(DEFAULT_JS_OPTIONS, KeyValueOptions.builder(KeyValueOptions.builder().build()).build());
        assertKvo(DEFAULT_JS_OPTIONS, KeyValueOptions.builder(DEFAULT_JS_OPTIONS).build());

        KeyValueOptions kvo = KeyValueOptions.builder().jsPrefix("prefix").build();
        assertEquals("prefix.", kvo.getJetStreamOptions().getPrefix());
        assertFalse(kvo.getJetStreamOptions().isDefaultPrefix());

        kvo = KeyValueOptions.builder().jsDomain("domain").build();
        assertEquals("$JS.domain.API.", kvo.getJetStreamOptions().getPrefix());
        assertFalse(kvo.getJetStreamOptions().isDefaultPrefix());

        kvo = KeyValueOptions.builder().jsRequestTimeout(Duration.ofSeconds(10)).build();
        assertEquals(Duration.ofSeconds(10), kvo.getJetStreamOptions().getRequestTimeout());
    }

    private void assertKvo(JetStreamOptions expected, KeyValueOptions kvo) {
        JetStreamOptions jso = kvo.getJetStreamOptions();
        assertEquals(expected.getRequestTimeout(), jso.getRequestTimeout());
        assertEquals(expected.getPrefix(), jso.getPrefix());
        assertEquals(expected.isDefaultPrefix(), jso.isDefaultPrefix());
        assertEquals(expected.isPublishNoAck(), jso.isPublishNoAck());
    }

    @Test
    public void testKeyValuePurgeOptionsBuilderCoverage() {
        assertEquals(DEFAULT_THRESHOLD_MILLIS,
            KeyValuePurgeOptions.builder().deleteMarkersThreshold(null).build()
                .getDeleteMarkersThresholdMillis());

        assertEquals(DEFAULT_THRESHOLD_MILLIS,
            KeyValuePurgeOptions.builder().deleteMarkersThreshold(Duration.ZERO).build()
                .getDeleteMarkersThresholdMillis());

        assertEquals(1,
            KeyValuePurgeOptions.builder().deleteMarkersThreshold(Duration.ofMillis(1)).build()
                .getDeleteMarkersThresholdMillis());

        assertEquals(-1,
            KeyValuePurgeOptions.builder().deleteMarkersThreshold(Duration.ofMillis(-1)).build()
                .getDeleteMarkersThresholdMillis());

        assertEquals(DEFAULT_THRESHOLD_MILLIS,
            KeyValuePurgeOptions.builder().deleteMarkersThreshold(0).build()
                .getDeleteMarkersThresholdMillis());

        assertEquals(1,
            KeyValuePurgeOptions.builder().deleteMarkersThreshold(1).build()
                .getDeleteMarkersThresholdMillis());

        assertEquals(-1,
            KeyValuePurgeOptions.builder().deleteMarkersThreshold(-1).build()
                .getDeleteMarkersThresholdMillis());

        assertEquals(-1,
            KeyValuePurgeOptions.builder().deleteMarkersNoThreshold().build()
                .getDeleteMarkersThresholdMillis());
    }

    @Test
    public void testCreateDiscardPolicy() throws Exception {
        runInJsServer(nc -> {
            KeyValueManagement kvm = nc.keyValueManagement();

            // create bucket
            KeyValueStatus status = kvm.create(KeyValueConfiguration.builder()
                .name(bucket(1))
                .storageType(StorageType.Memory)
                .build());

            DiscardPolicy dp = status.getConfiguration().getBackingConfig().getDiscardPolicy();
            if (nc.getServerInfo().isSameOrNewerThanVersion("2.7.2")) {
                assertEquals(DiscardPolicy.New, dp);
            }
            else {
                assertTrue(dp == DiscardPolicy.New || dp == DiscardPolicy.Old);
            }
        });
    }

    @Test
    public void testMiscCoverage() throws Exception {
        runInJsServer(nc -> {
            KeyValueManagement kvm = nc.keyValueManagement();

            // create bucket
            kvm.create(KeyValueConfiguration.builder()
                .name(BUCKET)
                .storageType(StorageType.Memory)
                .build());

            KeyValue kv = nc.keyValue(BUCKET);
            kv.put("a", "a");
            KeyValueEntry kve = kv.get("a");
            assertThrows(NumberFormatException.class, kve::getValueAsLong);

            kv.delete("a");
            List<KeyValueEntry> list = kv.history("a");
            assertNull(list.get(0).getValueAsString());
            assertNull(list.get(0).getValueAsLong());
        });
    }

    @Test
    public void testMirrorSoureceBuilderPrefixConversion() throws Exception {
        KeyValueConfiguration kvc = KeyValueConfiguration.builder()
            .name(BUCKET)
            .mirror(Mirror.builder().name("name").build())
            .build();
        assertEquals("KV_name", kvc.getBackingConfig().getMirror().getName());

        kvc = KeyValueConfiguration.builder()
            .name(BUCKET)
            .mirror(Mirror.builder().name("KV_name").build())
            .build();
        assertEquals("KV_name", kvc.getBackingConfig().getMirror().getName());

        Source s1 = Source.builder().name("s1").build();
        Source s2 = Source.builder().name("s2").build();
        Source s3 = Source.builder().name("s3").build();
        Source s4 = Source.builder().name("s4").build();
        Source s5 = Source.builder().name("KV_s5").build();
        Source s6 = Source.builder().name("KV_s6").build();

        kvc = KeyValueConfiguration.builder()
            .name(BUCKET)
            .sources(s3, s4)
            .sources(Arrays.asList(s1, s2))
            .addSources(s1, s2)
            .addSources(Arrays.asList(s1, s2, null))
            .addSources(s3, s4)
            .addSource(null)
            .addSource(s5)
            .addSource(s5)
            .addSources(s6)
            .addSources((Source[])null)
            .addSources((Collection<Source>)null)
            .build();

        assertEquals(6, kvc.getBackingConfig().getSources().size());
        List<String> names = new ArrayList<>();
        for (Source source : kvc.getBackingConfig().getSources()) {
            names.add(source.getName());
        }
        assertTrue(names.contains("KV_s1"));
        assertTrue(names.contains("KV_s2"));
        assertTrue(names.contains("KV_s3"));
        assertTrue(names.contains("KV_s4"));
        assertTrue(names.contains("KV_s5"));
        assertTrue(names.contains("KV_s6"));
    }

    @Test
    public void testKeyValueMirrorCrossDomains() throws Exception {
        runInJsHubLeaf((hub, leaf) -> {
            KeyValueManagement hubKvm = hub.keyValueManagement();
            KeyValueManagement leafKvm = leaf.keyValueManagement();

            // Create main KV on HUB
            KeyValueStatus hubStatus = hubKvm.create(KeyValueConfiguration.builder()
                .name("TEST")
                .storageType(StorageType.Memory)
                .build());

            KeyValue hubKv = hub.keyValue("TEST");
            hubKv.put("key1", "aaa0");
            hubKv.put("key2", "bb0");
            hubKv.put("key3", "c0");
            hubKv.delete("key3");

            leafKvm.create(KeyValueConfiguration.builder()
                .name("MIRROR")
                .mirror(Mirror.builder()
                    .sourceName("TEST")
                    .domain(null)  // just for coverage!
                    .domain("HUB") // it will take this since it comes last
                    .build())
                .build());

            sleep(200); // make sure things get a chance to propagate
            StreamInfo si = leaf.jetStreamManagement().getStreamInfo("KV_MIRROR");
            if (hub.getServerInfo().isSameOrNewerThanVersion("2.9.0")) {
                assertTrue(si.getConfiguration().getMirrorDirect());
            }
            assertEquals(3, si.getStreamState().getMsgCount());

            KeyValue leafKv = leaf.keyValue("MIRROR");
            _testMirror(hubKv, leafKv, 1);

            // Bind through leafnode connection but to origin KV.
            KeyValue hubViaLeafKv =
                leaf.keyValue("TEST", KeyValueOptions.builder().jsDomain("HUB").build());
            _testMirror(hubKv, hubViaLeafKv, 2);
        });
    }

    private void _testMirror(KeyValue okv, KeyValue mkv, int num) throws Exception {
        mkv.put("key1", "aaa" + num);
        mkv.put("key3", "c" + num);

        sleep(200); // make sure things get a chance to propagate
        KeyValueEntry kve = mkv.get("key3");
        assertEquals("c" + num, kve.getValueAsString());

        mkv.delete("key3");
        sleep(200); // make sure things get a chance to propagate
        assertNull(mkv.get("key3"));

        kve = mkv.get("key1");
        assertEquals("aaa" + num, kve.getValueAsString());

        // Make sure we can create a watcher on the mirror KV.
        TestKeyValueWatcher mWatcher = new TestKeyValueWatcher("mirrorWatcher" + num, false);
        try (NatsKeyValueWatchSubscription mWatchSub = mkv.watchAll(mWatcher)) {
            sleep(200); // give the messages time to propagate
        }
        validateWatcher(new Object[]{"bb0", "aaa" + num, KeyValueOperation.DELETE}, mWatcher);

        // Does the origin data match?
        if (okv != null) {
            TestKeyValueWatcher oWatcher = new TestKeyValueWatcher("originWatcher" + num, false);
            try (NatsKeyValueWatchSubscription oWatchSub = okv.watchAll(oWatcher)) {
                sleep(200); // give the messages time to propagate
            }
            validateWatcher(new Object[]{"bb0", "aaa" + num, KeyValueOperation.DELETE}, oWatcher);
        }
    }
}
